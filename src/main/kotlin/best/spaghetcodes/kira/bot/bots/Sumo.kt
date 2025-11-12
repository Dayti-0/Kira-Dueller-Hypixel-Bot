package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.bot.tuning.SumoTuner
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class Sumo : BotBase("/play duels_sumo_duel"), MovePriority {

    override fun getName(): String = "Sumo"

    // ================== Start-hop : modes ==================
    private enum class StartHopMode { TIMER, GROUND, HYBRID }
    // -> pris au démarrage dans le tuner (voir onGameStart)
    private var startHopMode: StartHopMode = StartHopMode.HYBRID

    // TIMER (cible ≈0.30s)
    private var START_HOP_TIMER_MS = 300L
    private var START_HOP_TIMER_FUDGE_MS = 40L      // compensation tick (le tuner peut l’ajuster)

    // GROUND (déclenche dès que onGround est vrai X ticks d’affilée)
    private var GROUND_TICKS_REQUIRED = 2
    private var GROUND_MAX_WAIT_MS = 290L           // filet : si on ne touche pas assez tôt

    // Ré-assertions d’avance autour des jumps (évite “saut sur place”)
    private val REASSERT_FWD_1_MS = 30L
    private val REASSERT_FWD_2_MS = 90L

    // Anti-void neutralisé au tout début (pour ne pas couper l’élan du 1er saut)
    private var START_ANTIVOID_DISABLE_MS = 600L

    // Petite sécu pour ignorer les tout premiers ms
    private val startSafeDelayMs = 40L

    // Verrou “anti double-saut zone” juste après le start-hop
    private var blockZoneJumpsForMsAfterStart = 600L

    // ---- Saut "zone" ----
    // SAUTER si distance > 7.0f ; ne JAMAIS sauter si distance ≤ 7.0f
    private val jumpZoneThreshold = 7.0f
    // réarmement anti re-spam: revenir bien à l'intérieur + attendre un délai
    private var rearmInnerDist = 6.2f
    private var zoneRearmDelayMs = 1400L

    // AC / Prefire
    private var attackStartDist = 4.05f
    private var attackLatchMs = 220L
    private var prefireFastApproachDist = 4.6f
    private var prefireLatchMs = 160L

    // Avance / stop court
    private var stopForwardDist = 1.18f
    private var reForwardDist = 2.0f

    // Détection vide
    private var edgeProbeNear = 1.6f
    private var edgeProbeFar = 2.6f

    // ================== Strafe "Burst Sumo" ==================
    private enum class SMode { HOLD, BURST, COAST }

    // Phases : HOLD → BURST (flips irréguliers) → COAST
    private var HOLD_MS = 900..1500
    private var COAST_MS = 500..900
    private var BURST_MS = 300..520
    private var BURST_FLIP_EVERY_MS = 120..200
    private var BURST_SKIP_PROBA = 30

    // Long-strafe option (depuis le tuner)
    private var longStrafeChance = 0
    private var longStrafeDurationMs = 0L

    // Biais centre léger
    private var centerBiasStrength = 100
    private var centerBiasIntervalMs = 300L

    // Close-strafe (tenir plus longtemps quand collés)
    private var closeInnerMin = 360L
    private var closeInnerMax = 520L
    private var closeMidMin = 420L
    private var closeMidMax = 600L
    private var closeFarMin = 520L
    private var closeFarMax = 700L

    private fun closeStrafeDelay(distance: Float): Long {
        val (minDelay, maxDelay) = when {
            distance < 1.8f -> closeInnerMin to closeInnerMax
            distance < 2.6f -> closeMidMin to closeMidMax
            else -> closeFarMin to closeFarMax
        }
        val minVal = min(minDelay, maxDelay).toInt()
        val maxVal = max(minDelay, maxDelay).toInt()
        return RandomUtils.randomIntInRange(minVal, maxVal).toLong()
    }

    private fun attemptLongStrafe(now: Long): Boolean {
        if (longStrafeChance <= 0 || longStrafeDurationMs <= 0L) return false
        if (RandomUtils.randomIntInRange(1, 100) > longStrafeChance) return false
        longStrafeUntil = now + longStrafeDurationMs
        lastStrafeFlip = now
        return true
    }

    // Anti-stagnation
    private var ANTI_STALL_EPS = 0.010f
    private var ANTI_STALL_DELAY = 380L

    // ================== États ==================
    private var gameStartedAt = 0L

    // Start hop
    private var startHopDone = false
    private var startHopFiredAt = 0L
    private var groundTicksSinceStart = 0

    // Zone jump
    private var zoneArmed = true
    private var lastZoneJumpAt = 0L

    // Strafe state
    private var strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
    private var sMode = SMode.HOLD
    private var sModeUntil = 0L
    private var nextBurstFlipAt = 0L
    private var nextCloseStrafeAt = 0L
    private var lastStrafeFlip = 0L
    private var antiStallRef = -1f
    private var antiStallAt = 0L
    private var longStrafeUntil = 0L
    private var autoTuneMistakes = 0
    private var sessionWinsAtStart = 0
    private var sessionLossesAtStart = 0

    // Centre
    private var mySpawnX = 0.0
    private var mySpawnZ = 0.0
    private var oppSpawnX: Double? = null
    private var oppSpawnZ: Double? = null
    private var centerX = 0.0
    private var centerZ = 0.0
    private var centerReady = false

    private var prevDistance = -1f
    private var keepACUntil = 0L
    private var opponentOffEdge = false
    private var tapping = false
    private var tap50 = false

    // ================== Utils ==================
    private fun edgeAhead(dist: Float): Boolean {
        val p = mc.thePlayer ?: return false
        return WorldUtils.blockInFront(p, dist, 0.0f) == Blocks.air
    }

    private fun preferLeftToward(pointX: Double, pointZ: Double): Boolean {
        val p = mc.thePlayer ?: return false
        return WorldUtils.leftOrRightToPoint(p, Vec3(pointX, 0.0, pointZ))
    }

    private fun updateCenterOnce() {
        val p = mc.thePlayer ?: return
        val o = opponent() ?: return
        if (oppSpawnX == null) {
            oppSpawnX = o.posX
            oppSpawnZ = o.posZ
        }
        if (!centerReady && oppSpawnX != null) {
            centerX = (mySpawnX + oppSpawnX!!) / 2.0
            centerZ = (mySpawnZ + oppSpawnZ!!) / 2.0
            centerReady = true
        }
    }

    private fun rFromCenter(x: Double, z: Double): Double = hypot(x - centerX, z - centerZ)

    private fun performStartHop(now: Long) {
        Movement.startForward(); Movement.startSprinting()
        Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
        startHopDone = true
        startHopFiredAt = now
        lastZoneJumpAt = now // empêche un jump "zone" immédiat
        // ré-assertions d’avance pour éviter "saut sur place"
        TimeUtils.setTimeout({ Movement.startForward(); Movement.startSprinting() }, REASSERT_FWD_1_MS.toInt())
        TimeUtils.setTimeout({ Movement.startForward(); Movement.startSprinting() }, REASSERT_FWD_2_MS.toInt())
    }

    // ================== Hooks ==================
    override fun onGameStart() {
        Mouse.startTracking()
        Mouse.stopLeftAC()

        // Sprint immédiat
        Movement.clearAll()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping()

        mc.thePlayer?.let {
            mySpawnX = it.posX
            mySpawnZ = it.posZ
            centerX = mySpawnX
            centerZ = mySpawnZ
            centerReady = false
        }

        gameStartedAt = System.currentTimeMillis()

        startHopDone = false
        startHopFiredAt = 0L
        groundTicksSinceStart = 0

        zoneArmed = true
        lastZoneJumpAt = 0L

        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        sMode = SMode.HOLD
        sModeUntil = 0L
        nextBurstFlipAt = 0L
        nextCloseStrafeAt = 0L
        lastStrafeFlip = 0L
        antiStallRef = -1f
        antiStallAt = 0L

        longStrafeUntil = 0L
        autoTuneMistakes = 0
        sessionWinsAtStart = Session.wins
        sessionLossesAtStart = Session.losses

        // ====== PARAMÈTRES AUTO TUNER ======
        val params = SumoTuner.pickParams()

        // Verrou post-start + zone rearm
        blockZoneJumpsForMsAfterStart = params.blockZoneLockMs
        rearmInnerDist = params.rearmInnerDist
        zoneRearmDelayMs = params.zoneRearmDelayMs

        // Combat
        attackStartDist = params.attackStartDist
        attackLatchMs = params.attackLatchMs
        prefireFastApproachDist = params.prefireApproachDist
        prefireLatchMs = params.prefireLatchMs

        // Avance / stop
        stopForwardDist = params.stopForwardDist
        reForwardDist = params.reForwardDist

        // Anti-void probes
        edgeProbeNear = params.edgeProbeNear
        edgeProbeFar = params.edgeProbeFar

        // Fenêtres de strafe
        val holdMin = min(params.holdMsMin, params.holdMsMax)
        val holdMax = max(params.holdMsMin, params.holdMsMax)
        HOLD_MS = holdMin..holdMax

        val burstMin = min(params.burstMsMin, params.burstMsMax)
        val burstMax = max(params.burstMsMin, params.burstMsMax)
        BURST_MS = burstMin..burstMax

        val coastMin = min(params.coastMsMin, params.coastMsMax)
        val coastMax = max(params.coastMsMin, params.coastMsMax)
        COAST_MS = coastMin..coastMax

        val flipMin = min(params.burstFlipMin, params.burstFlipMax)
        val flipMax = max(params.burstFlipMin, params.burstFlipMax)
        BURST_FLIP_EVERY_MS = flipMin..flipMax

        BURST_SKIP_PROBA = params.burstSkipPercent
        longStrafeChance = params.longStrafeChance
        longStrafeDurationMs = params.longStrafeDurationMs

        // Close-strafe ranges
        closeInnerMin = min(params.closeInnerMin, params.closeInnerMax)
        closeInnerMax = max(params.closeInnerMin, params.closeInnerMax)
        closeMidMin = min(params.closeMidMin, params.closeMidMax)
        closeMidMax = max(params.closeMidMin, params.closeMidMax)
        closeFarMin = min(params.closeFarMin, params.closeFarMax)
        closeFarMax = max(params.closeFarMin, params.closeFarMax)

        // Anti-stall + biais centre
        ANTI_STALL_EPS = params.antiStallEps
        ANTI_STALL_DELAY = params.antiStallDelayMs
        centerBiasStrength = params.centerBiasStrength
        centerBiasIntervalMs = params.centerBiasIntervalMs

        // ====== Nouveaux paramètres Start-hop (depuis le tuner) ======
        START_ANTIVOID_DISABLE_MS = params.startAntivoidDisableMs
        START_HOP_TIMER_FUDGE_MS  = params.startHopTimerFudgeMs
        GROUND_TICKS_REQUIRED     = params.groundTicksRequired
        GROUND_MAX_WAIT_MS        = params.groundMaxWaitMs
        startHopMode = when (params.startHopModeInt) {
            0 -> StartHopMode.TIMER
            1 -> StartHopMode.GROUND
            else -> StartHopMode.HYBRID
        }

        prevDistance = -1f
        keepACUntil = 0L
        opponentOffEdge = false
        tapping = false
        tap50 = false
    }

    override fun onGameEnd() {
        // Feedback au tuner (reward basique + erreurs si tu en incrémentes)
        val win = when {
            Session.wins > sessionWinsAtStart -> true
            Session.losses > sessionLossesAtStart -> false
            else -> opponentOffEdge
        }
        SumoTuner.report(win, autoTuneMistakes)

        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    override fun onFoundOpponent() {
        updateCenterOnce()
        Mouse.startTracking()
    }

    override fun onAttack() {
        // W-tap propre (hitselect OFF)
        val dur = if (tap50) 50 else 100
        tap50 = !tap50
        Combat.wTap(dur)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, dur + 15)
    }

    // ================== Tick ==================
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val now = System.currentTimeMillis()
        val o = opponent()

        // Matchmaking : freeze total si Lobby Movement OFF
        if (o == null) {
            if (kira.config?.lobbyMovement != true) {
                Combat.stopRandomStrafe()
                Mouse.stopTracking()
                Mouse.stopLeftAC()
                Movement.clearAll()
            }
            return
        }

        updateCenterOnce()

        // Sprint permanent + petite re-assert d’avance
        if (!p.isSprinting) Movement.startSprinting()
        Movement.startForward()

        // Stop chase suicidaire si l’ennemi tombe
        val isOppActuallyOffEdge = WorldUtils.entityOffEdge(o)
        opponentOffEdge = isOppActuallyOffEdge ||
                (opponentOffEdge && EntityUtils.getDistanceNoY(p, o) > 17)
        if (opponentOffEdge) {
            Mouse.stopLeftAC(); Movement.clearAll(); Combat.stopRandomStrafe(); Mouse.stopTracking()
            return
        }

        val distance = EntityUtils.getDistanceNoY(p, o)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.10f)

        // Anti-void devant (désactivé pendant les premières START_ANTIVOID_DISABLE_MS)
        val enableAntivoid = now - gameStartedAt >= START_ANTIVOID_DISABLE_MS
        val voidNear = if (enableAntivoid) edgeAhead(edgeProbeNear) else false
        val voidFar  = if (enableAntivoid) edgeAhead(edgeProbeFar)  else false
        val voidFront = voidNear || voidFar
        if (voidFront) { Movement.stopForward(); Movement.startSneaking() } else { Movement.stopSneaking() }

        // ======= START-HOP (TIMER / GROUND / HYBRID) =======
        if (!startHopDone && now - gameStartedAt >= startSafeDelayMs) {
            when (startHopMode) {
                StartHopMode.TIMER -> {
                    if (now - gameStartedAt >= (START_HOP_TIMER_MS - START_HOP_TIMER_FUDGE_MS)) {
                        performStartHop(now)
                    }
                }
                StartHopMode.GROUND -> {
                    if (p.onGround) groundTicksSinceStart++ else groundTicksSinceStart = 0
                    if (groundTicksSinceStart >= GROUND_TICKS_REQUIRED) {
                        performStartHop(now)
                    }
                }
                StartHopMode.HYBRID -> {
                    val since = now - gameStartedAt
                    if (p.onGround) groundTicksSinceStart++ else groundTicksSinceStart = 0
                    val canGroundHop = groundTicksSinceStart >= GROUND_TICKS_REQUIRED
                    val timerSafety = since >= (START_HOP_TIMER_MS - START_HOP_TIMER_FUDGE_MS)
                    val timeoutForce = since >= GROUND_MAX_WAIT_MS
                    if (canGroundHop || timerSafety || timeoutForce) {
                        performStartHop(now)
                    }
                }
            }
        }

        // ===== AC latch / Prefire =====
        val inAttackLatch = (distance <= attackStartDist)
        val inPrefire = (approaching && distance <= prefireFastApproachDist && distance > attackStartDist)
        if (kira.config?.kiraHit == true && (inAttackLatch || inPrefire)) {
            keepACUntil = now + if (inPrefire) prefireLatchMs else attackLatchMs
            Mouse.startLeftAC()
        } else if ((now >= keepACUntil) || kira.config?.kiraHit != true) {
            Mouse.stopLeftAC()
        }

        // ===== Saut "zone" : > 7.0 => jump, ≤ 7.0 => jamais =====
        val postStartLockActive = startHopDone && (now - startHopFiredAt) < blockZoneJumpsForMsAfterStart
        val overZone = distance > jumpZoneThreshold

        if (zoneArmed && !postStartLockActive && !voidFront && overZone) {
            Movement.startForward(); Movement.startSprinting()
            Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            // ré-assertions pour garantir un jump en mouvement
            TimeUtils.setTimeout({ Movement.startForward(); Movement.startSprinting() }, REASSERT_FWD_1_MS.toInt())
            TimeUtils.setTimeout({ Movement.startForward(); Movement.startSprinting() }, REASSERT_FWD_2_MS.toInt())

            zoneArmed = false
            lastZoneJumpAt = now
        } else if (!zoneArmed) {
            // réarmer seulement après être bien rentré ≤ rearmInnerDist ET avoir attendu un délai
            if (distance <= rearmInnerDist && (now - lastZoneJumpAt) >= zoneRearmDelayMs) {
                zoneArmed = true
            }
        }

        // ===== Strafe "Burst Sumo" imprévisible =====
        // 1) Phases
        if (now >= sModeUntil) {
            sMode = when (sMode) {
                SMode.HOLD  -> if (RandomUtils.randomIntInRange(0, 99) < 60) SMode.BURST else SMode.COAST
                SMode.BURST -> if (RandomUtils.randomIntInRange(0, 99) < 55) SMode.COAST else SMode.HOLD
                SMode.COAST -> if (RandomUtils.randomIntInRange(0, 99) < 55) SMode.HOLD else SMode.BURST
            }
            sModeUntil = now + when (sMode) {
                SMode.HOLD  -> RandomUtils.randomIntInRange(HOLD_MS.first, HOLD_MS.last)
                SMode.BURST -> RandomUtils.randomIntInRange(BURST_MS.first, BURST_MS.last)
                SMode.COAST -> RandomUtils.randomIntInRange(COAST_MS.first, COAST_MS.last)
            }
            if (sMode == SMode.BURST) {
                nextBurstFlipAt = now + RandomUtils.randomIntInRange(BURST_FLIP_EVERY_MS.first, BURST_FLIP_EVERY_MS.last)
            }
        }

        // 2) Micro-bursts irréguliers (option long-strafe via tuner)
        if (sMode == SMode.BURST && now >= nextBurstFlipAt) {
            nextBurstFlipAt = now + RandomUtils.randomIntInRange(BURST_FLIP_EVERY_MS.first, BURST_FLIP_EVERY_MS.last)
            if (now >= longStrafeUntil && RandomUtils.randomIntInRange(1, 100) > BURST_SKIP_PROBA) {
                if (!attemptLongStrafe(now)) {
                    strafeDir = -strafeDir
                }
                lastStrafeFlip = now
            }
        }

        // 3) Close-strafe (tenu plus longtemps quand collés)
        val closeCtrl = distance <= 2.6f && p.onGround
        if (closeCtrl && now >= nextCloseStrafeAt) {
            nextCloseStrafeAt = now + closeStrafeDelay(distance)
            if (now >= longStrafeUntil && RandomUtils.randomIntInRange(1, 100) <= 35) {
                if (!attemptLongStrafe(now)) {
                    strafeDir = -strafeDir
                }
                lastStrafeFlip = now
            }
        }

        // 4) Anti-stagnation
        if (antiStallRef < 0f) { antiStallRef = distance; antiStallAt = now }
        else {
            val d = abs(distance - antiStallRef)
            if (d < ANTI_STALL_EPS) {
                if (now - antiStallAt >= ANTI_STALL_DELAY && now >= longStrafeUntil) {
                    if (!attemptLongStrafe(now)) {
                        strafeDir = -strafeDir
                    }
                    lastStrafeFlip = now
                    antiStallRef = distance
                    antiStallAt = now
                }
            } else {
                antiStallRef = distance
                antiStallAt = now
            }
        }

        // 5) Biais centre (léger) + anti-edge
        if (centerReady) {
            val wantLeft = preferLeftToward(centerX, centerZ)
            val enforceCenter = RandomUtils.randomIntInRange(1, 100) <= centerBiasStrength
            if (voidFront) {
                if (wantLeft && strafeDir > 0) { strafeDir = -1; lastStrafeFlip = now }
                if (!wantLeft && strafeDir < 0) { strafeDir = 1; lastStrafeFlip = now }
            } else if (now - lastStrafeFlip > centerBiasIntervalMs && enforceCenter) {
                if (wantLeft && strafeDir > 0) { strafeDir = -1; lastStrafeFlip = now }
                if (!wantLeft && strafeDir < 0) { strafeDir = 1; lastStrafeFlip = now }
            }
        }

        // Anti-vide latéral
        if (Movement.left() && WorldUtils.airOnLeft(p, 1.5f) && p.onGround) Movement.stopLeft()
        if (Movement.right() && WorldUtils.airOnRight(p, 1.5f) && p.onGround) Movement.stopRight()

        // Application du strafe (sans random-strafe lib)
        if (strafeDir < 0) { Movement.stopRight(); Movement.startLeft() }
        else { Movement.stopLeft(); Movement.startRight() }

        // ===== Avant / arrière =====
        if (distance < stopForwardDist || (enableAntivoid && edgeAhead(1.0f))) {
            Movement.stopForward()
        } else if (!voidFront) {
            if (distance > reForwardDist) Movement.startForward()
        }

        // ===== Anti-void arrière =====
        if (WorldUtils.airInBack(p, 2.0f) && p.onGround) {
            Movement.clearLeftRight(); Combat.stopRandomStrafe()
            Movement.startForward()
        }

        prevDistance = distance
    }
}
