package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.bot.tuning.SumoTuner
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.*

class Sumo : BotBase("/play duels_sumo_duel"), MovePriority {

    override fun getName(): String = "Sumo"

    // ================== Start-hop : modes ==================
    private enum class StartHopMode { TIMER, GROUND, HYBRID }
    private var startHopMode: StartHopMode = StartHopMode.HYBRID

    // TIMER (cible ≈0.30s) — tuné par le Tuner
    private var START_HOP_TIMER_MS = 300L
    private var START_HOP_TIMER_FUDGE_MS = 40L

    // GROUND
    private var GROUND_TICKS_REQUIRED = 2
    private var GROUND_MAX_WAIT_MS = 300L

    // Anti “jump sur place”
    private val REASSERT_FWD_1_MS = 30L
    private val REASSERT_FWD_2_MS = 90L

    // Anti-void neutralisé au début
    private var START_ANTIVOID_DISABLE_MS = 600L

    // sécurité au tout début
    private val startSafeDelayMs = 40L

    // verrou anti double-saut zone juste après le start-hop
    private var blockZoneJumpsForMsAfterStart = 600L

    // ---- Zone jump ----
    private val jumpZoneThreshold = 7.0f            // SAUTER si >7.0 ; ne jamais sauter si ≤7.0
    private var rearmInnerDist = 6.2f
    private var zoneRearmDelayMs = 1400L

    // ---- Combat windows (existants) ----
    private var attackStartDist = 4.05f
    private var attackLatchMs = 220L
    private var prefireFastApproachDist = 4.6f
    private var prefireLatchMs = 160L

    // ---- Pré-fire “long” aim-gated (NOUVEAU) ----
    private var hardAttackDist = 4.9f         // AC possible jusqu’à ~5 blocs si aim ok
    private var preAimDot = 0.96              // exigence d’alignement (dot à plat)

    // W-tap + post-hit drive
    private var wTapShortMs = 50L
    private var wTapLongMs = 100L
    private var postHitDriveMs = 140L
    private val NO_JUMP_DURING_WTAP_PAD_MS = 25L

    // Avant / stop court
    private var stopForwardDist = 1.18f
    private var reForwardDist = 2.0f

    // Anti-void (no sneak)
    private var edgeProbeNear = 1.6f
    private var edgeProbeFar = 2.6f
    private var predictiveProbeBonus = 0.6f

    // ================== Strafe v2 ==================
    private enum class SMode { HOLD, BURST, COAST }
    private enum class StrafeStyle { MINIMAL, BURST, HYBRID }

    private var HOLD_MS = 900..1500
    private var COAST_MS = 500..900
    private var BURST_MS = 300..520
    private var BURST_FLIP_EVERY_MS = 120..200
    private var BURST_SKIP_PROBA = 30

    // style/intensité (NOUVEAU)
    private var strafeStyle = StrafeStyle.MINIMAL
    private var strafeIntensity = 25 // 0..100

    // long-strafe existant
    private var longStrafeChance = 0
    private var longStrafeDurationMs = 0L

    // Biais centre / edge
    private var centerBiasStrength = 100
    private var centerBiasIntervalMs = 300L
    private var edgeAggroWeight = 20
    private var edgeAggroRadiusBonus = 0.8f

    // Close-strafe
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

    // ================== Hit-Select assist (NOUVEAU) ==================
    private var hsEnable = true
    private var hsMinMs = 80L       // fenêtre dès 80 ms après avoir été touché
    private var hsMaxMs = 180L      // jusqu’à ~180 ms (milieu de KB)
    private var hsLatchMs = 170L    // durée d’AC pendant HS
    private var hsMinDist = 3.0f
    private var hsMaxDist = 4.9f
    private var hsAimDot = 0.955    // exigence d’alignement (à plat)
    private var hsOnlyIfOppOutside = true

    // tracking du hurtTime pour dater le dernier coup reçu
    private var prevHurtTime = 0
    private var lastGotHitAt = 0L

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

    // Centre
    private var mySpawnX = 0.0
    private var mySpawnZ = 0.0
    private var oppSpawnX: Double? = null
    private var oppSpawnZ: Double? = null
    private var centerX = 0.0
    private var centerZ = 0.0
    private var centerReady = false

    // Divers
    private var prevDistance = -1f
    private var keepACUntil = 0L
    private var opponentOffEdge = false
    private var tapping = false
    private var tap50 = false
    private var postHitDriveUntil = 0L
    private var noJumpUntil = 0L

    // ================== Utils ==================
    private fun blockInFront(dist: Float): Boolean {
        val p = mc.thePlayer ?: return false
        return WorldUtils.blockInFront(p, dist, 0.0f) == Blocks.air
    }

    private fun edgeAheadDynamic(distBase: Float): Boolean {
        val p = mc.thePlayer ?: return false
        val vz = abs(p.motionZ)
        val vx = abs(p.motionX)
        val speed = hypot(vx, vz).toFloat()
        val bonus = if (speed > 0.18f) predictiveProbeBonus else 0f
        val d1 = distBase + bonus
        return blockInFront(d1)
    }

    private fun preferLeftToward(pointX: Double, pointZ: Double): Boolean {
        val p = mc.thePlayer ?: return false
        return WorldUtils.leftOrRightToPoint(p, Vec3(pointX, 0.0, pointZ))
    }

    private fun updateCenterOnce() {
        if (mc.thePlayer == null) return
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

    // dot(look, toOpponent) à plat (XZ)
    private fun aimDotToOpponentFlat(): Double {
        val p = mc.thePlayer ?: return 0.0
        val o = opponent() ?: return 0.0
        val toX = o.posX - p.posX
        val toZ = o.posZ - p.posZ
        val toLen = sqrt(toX * toX + toZ * toZ)
        if (toLen < 1e-6) return 1.0
        val lx = p.lookVec.xCoord
        val lz = p.lookVec.zCoord
        val ll = sqrt(lx * lx + lz * lz)
        if (ll < 1e-6) return 0.0
        val dot = (lx / ll) * (toX / toLen) + (lz / ll) * (toZ / toLen)
        return dot.coerceIn(-1.0, 1.0)
    }

    // Jump robuste: avance+sprint, petit délai si vitesse faible, jump, ré-asserts
    private fun runJumpSmart(preDelayDefaultMs: Int = 12) {
        val p = mc.thePlayer ?: return
        Movement.startForward(); Movement.startSprinting()
        val speed = hypot(abs(p.motionX), abs(p.motionZ)).toFloat()
        val minSpeedForJump = 0.08f
        val pre = if (speed < minSpeedForJump) max(12, preDelayDefaultMs + 12) else preDelayDefaultMs
        TimeUtils.setTimeout({
            Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            TimeUtils.setTimeout({ Movement.startForward(); Movement.startSprinting() }, REASSERT_FWD_1_MS.toInt())
            TimeUtils.setTimeout({ Movement.startForward(); Movement.startSprinting() }, REASSERT_FWD_2_MS.toInt())
        }, pre)
    }

    private fun performStartHop(now: Long) {
        runJumpSmart(12)
        startHopDone = true
        startHopFiredAt = now
        lastZoneJumpAt = now
    }

    private fun updateGotHitStamp(now: Long) {
        val ht = mc.thePlayer?.hurtTime ?: 0
        if (ht > 0 && prevHurtTime == 0) lastGotHitAt = now
        prevHurtTime = ht
    }

    private fun opponentMoreOutside(): Boolean {
        if (!centerReady) return false
        val p = mc.thePlayer ?: return false
        val o = opponent() ?: return false
        val myR = rFromCenter(p.posX, p.posZ)
        val oppR = rFromCenter(o.posX, o.posZ)
        return oppR > myR + edgeAggroRadiusBonus
    }

    private fun tryHitSelect(now: Long, distance: Float, voidFront: Boolean) {
        if (!hsEnable || lastGotHitAt <= 0L) return
        val dt = now - lastGotHitAt
        if (dt < hsMinMs || dt > hsMaxMs) return
        if (distance < hsMinDist || distance > hsMaxDist) return
        if (voidFront) return
        if (hsOnlyIfOppOutside && !opponentMoreOutside()) return
        val dot = aimDotToOpponentFlat()
        if (dot < hsAimDot) return

        // pas de jump/strafe pendant l’impact, on pousse droit
        Movement.stopJumping()
        Movement.clearLeftRight()
        Movement.startForward(); Movement.startSprinting()

        // mini w-tap court si dispo (sinon force sprint)
        Combat.wTap(max(40, (wTapShortMs - 10)).toInt())

        // ouvre AC pour verrouiller le hit
        if (kira.config?.kiraHit == true) {
            keepACUntil = now + hsLatchMs
            Mouse.startLeftAC()
        }
    }

    // ================== Hooks ==================
    override fun onGameStart() {
        Mouse.startTracking()
        Mouse.stopLeftAC()

        Movement.clearAll()
        Movement.stopSneaking()   // NO-SNEAK garanti
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

        prevDistance = -1f
        keepACUntil = 0L
        opponentOffEdge = false
        tapping = false
        tap50 = false
        postHitDriveUntil = 0L
        noJumpUntil = 0L

        prevHurtTime = mc.thePlayer?.hurtTime ?: 0
        lastGotHitAt = 0L

        // ====== PARAMS TUNER ======
        val params = if (kira.isTunerEnabled) {
            try {
                SumoTuner.pickParams()
            } catch (_: Throwable) {
                SumoTuner.defaults()
            }
        } else {
            SumoTuner.defaults()
        }

        blockZoneJumpsForMsAfterStart = params.blockZoneLockMs
        rearmInnerDist = params.rearmInnerDist
        zoneRearmDelayMs = params.zoneRearmDelayMs

        attackStartDist = params.attackStartDist
        attackLatchMs = params.attackLatchMs
        prefireFastApproachDist = params.prefireApproachDist
        prefireLatchMs = params.prefireLatchMs

        // Pré-fire long
        hardAttackDist = params.hardAttackDist
        preAimDot = params.preAimDot.toDouble()

        wTapShortMs = params.wTapShortMs
        wTapLongMs = params.wTapLongMs
        postHitDriveMs = params.postHitDriveMs

        stopForwardDist = params.stopForwardDist
        reForwardDist = params.reForwardDist

        edgeProbeNear = params.edgeProbeNear
        edgeProbeFar = params.edgeProbeFar
        predictiveProbeBonus = params.predictiveProbeBonus

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

        // style/intensité de strafe
        strafeStyle = when (params.strafeStyleInt) {
            1 -> StrafeStyle.BURST
            2 -> StrafeStyle.HYBRID
            else -> StrafeStyle.MINIMAL
        }
        strafeIntensity = params.strafeIntensity.coerceIn(0, 100)

        closeInnerMin = min(params.closeInnerMin, params.closeInnerMax)
        closeInnerMax = max(params.closeInnerMin, params.closeInnerMax)
        closeMidMin = min(params.closeMidMin, params.closeMidMax)
        closeMidMax = max(params.closeMidMin, params.closeMidMax)
        closeFarMin = min(params.closeFarMin, params.closeFarMax)
        closeFarMax = max(params.closeFarMin, params.closeFarMax)

        ANTI_STALL_EPS = params.antiStallEps
        ANTI_STALL_DELAY = params.antiStallDelayMs
        centerBiasStrength = params.centerBiasStrength
        centerBiasIntervalMs = params.centerBiasIntervalMs
        edgeAggroWeight = params.edgeAggroWeight
        edgeAggroRadiusBonus = params.edgeAggroRadiusBonus

        // Start-Hop
        START_ANTIVOID_DISABLE_MS = params.startAntivoidDisableMs
        START_HOP_TIMER_FUDGE_MS  = params.startHopTimerFudgeMs
        START_HOP_TIMER_MS        = params.startHopTimerTargetMs
        GROUND_TICKS_REQUIRED     = params.groundTicksRequired
        GROUND_MAX_WAIT_MS        = params.groundMaxWaitMs
        startHopMode = when (params.startHopModeInt) {
            0 -> StartHopMode.TIMER
            1 -> StartHopMode.GROUND
            else -> StartHopMode.HYBRID
        }

        // Hit-Select assist
        hsEnable = params.hsEnableInt != 0
        hsMinMs = params.hsMinMs
        hsMaxMs = params.hsMaxMs
        hsLatchMs = params.hsLatchMs
        hsMinDist = params.hsMinDist
        hsMaxDist = params.hsMaxDist
        hsAimDot = params.hsAimDot.toDouble()
        hsOnlyIfOppOutside = params.hsOnlyIfOppOutsideInt != 0
    }

    override fun onGameEnd() {
        val win = when {
            Session.wins > Session.losses -> true
            Session.losses > Session.wins -> false
            else -> opponentOffEdge
        }
        if (kira.isTunerEnabled) {
            SumoTuner.report(win, 0)
        }
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Movement.stopSneaking()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    override fun onFoundOpponent() {
        updateCenterOnce()
        Mouse.startTracking()
    }

    override fun onAttack() {
        // W-tap — jamais de saut pendant le W-tap
        val now = System.currentTimeMillis()
        val dur = if (tap50) wTapShortMs else wTapLongMs
        tap50 = !tap50
        Combat.wTap(dur.toInt())
        tapping = true
        noJumpUntil = now + dur + NO_JUMP_DURING_WTAP_PAD_MS
        postHitDriveUntil = now + postHitDriveMs
        TimeUtils.setTimeout({ tapping = false }, dur.toInt() + 15)
    }

    // ================== Tick ==================
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val now = System.currentTimeMillis()
        val o = opponent()

        // Matchmaking : freeze si lobbyMovement OFF
        if (o == null) {
            if (kira.config?.lobbyMovement != true) {
                Combat.stopRandomStrafe()
                Mouse.stopTracking()
                Mouse.stopLeftAC()
                Movement.clearAll()
                Movement.stopSneaking()
            }
            return
        }

        updateCenterOnce()
        updateGotHitStamp(now)

        // Sprint permanent + NO-SNEAK
        Movement.stopSneaking()
        if (!p.isSprinting) Movement.startSprinting()
        Movement.startForward()

        // Stop chase si l’ennemi tombe
        val isOppActuallyOffEdge = WorldUtils.entityOffEdge(o)
        opponentOffEdge = isOppActuallyOffEdge ||
                (opponentOffEdge && EntityUtils.getDistanceNoY(p, o) > 17)
        if (opponentOffEdge) {
            Mouse.stopLeftAC(); Movement.clearAll(); Combat.stopRandomStrafe(); Mouse.stopTracking()
            Movement.stopSneaking()
            return
        }

        val distance = EntityUtils.getDistanceNoY(p, o)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.10f)

        // Anti-void devant (no-sneak)
        val enableAntivoid = now - gameStartedAt >= START_ANTIVOID_DISABLE_MS
        val voidNear = if (enableAntivoid) edgeAheadDynamic(edgeProbeNear) else false
        val voidFar  = if (enableAntivoid) edgeAheadDynamic(edgeProbeFar)  else false
        val voidFront = voidNear || voidFar
        if (voidFront) {
            Movement.stopForward()
            val goLeft = if (centerReady) preferLeftToward(centerX, centerZ) else (strafeDir < 0)
            if (goLeft) { Movement.stopRight(); Movement.startLeft() } else { Movement.stopLeft(); Movement.startRight() }
        }

        // ======= START-HOP =======
        if (!startHopDone && now - gameStartedAt >= startSafeDelayMs) {
            when (startHopMode) {
                StartHopMode.TIMER -> {
                    if (now - gameStartedAt >= (START_HOP_TIMER_MS - START_HOP_TIMER_FUDGE_MS)) performStartHop(now)
                }
                StartHopMode.GROUND -> {
                    val since = now - gameStartedAt
                    if (p.onGround) groundTicksSinceStart++ else groundTicksSinceStart = 0
                    val timeoutForce = since >= GROUND_MAX_WAIT_MS
                    if (groundTicksSinceStart >= GROUND_TICKS_REQUIRED || timeoutForce) performStartHop(now)
                }
                StartHopMode.HYBRID -> {
                    val since = now - gameStartedAt
                    if (p.onGround) groundTicksSinceStart++ else groundTicksSinceStart = 0
                    val canGroundHop = groundTicksSinceStart >= GROUND_TICKS_REQUIRED
                    val timerSafety = since >= (START_HOP_TIMER_MS - START_HOP_TIMER_FUDGE_MS)
                    val timeoutForce = since >= GROUND_MAX_WAIT_MS
                    if (canGroundHop || timerSafety || timeoutForce) performStartHop(now)
                }
            }
        }

        // ===== Hit-Select assist (s'exécute AVANT le gating AC classique) =====
        tryHitSelect(now, distance, voidFront)

        // ===== AC latch / Prefire (v2) =====
        val aimDot = aimDotToOpponentFlat()
        // (1) fenêtre “courte” classique
        val inAttackLatch = (distance <= attackStartDist)
        // (2) pré-fire approche
        val inPrefireApproach = (approaching && distance <= prefireFastApproachDist && distance > attackStartDist)
        // (3) pré-fire “long” aim-gated (≤ hardAttackDist ET visée alignée)
        val inPrefireLong = (distance <= hardAttackDist && aimDot >= preAimDot)

        if (kira.config?.kiraHit == true && (inAttackLatch || inPrefireApproach || inPrefireLong)) {
            val latch = when {
                inAttackLatch -> attackLatchMs
                inPrefireApproach -> prefireLatchMs
                else -> max(120L, prefireLatchMs - 20L) // pré-fire long: latch un poil plus court
            }
            keepACUntil = now + latch
            Mouse.startLeftAC()
        } else if ((now >= keepACUntil) || kira.config?.kiraHit != true) {
            Mouse.stopLeftAC()
        }

        // ===== Post-hit drive =====
        if (now < postHitDriveUntil && !voidFront) {
            Movement.startForward(); Movement.startSprinting()
        }

        // ===== Saut "zone" : > 7.0 => jump, ≤ 7.0 => jamais =====
        val postStartLockActive = startHopDone && (now - startHopFiredAt) < blockZoneJumpsForMsAfterStart
        val overZone = distance > jumpZoneThreshold
        val jumpAllowed = now >= noJumpUntil && !tapping && !voidFront

        if (zoneArmed && !postStartLockActive && jumpAllowed && overZone) {
            runJumpSmart(12)
            zoneArmed = false
            lastZoneJumpAt = now
        } else if (!zoneArmed) {
            if (distance <= rearmInnerDist && (now - lastZoneJumpAt) >= zoneRearmDelayMs) zoneArmed = true
        }

        // ===== Strafe v2 =====
        val pushing = (kira.config?.kiraHit == true) && (now < keepACUntil || now < postHitDriveUntil)
        when (strafeStyle) {
            StrafeStyle.MINIMAL -> {
                // zéro strafe quand on attaque/pousse : on garde l’axe avant
                if (pushing || distance < 2.0f) {
                    Movement.clearLeftRight()
                } else {
                    // micro-oscillation très lente, fréquence liée à l’intensité
                    val period = (650 - (strafeIntensity * 3)).coerceIn(300, 650)
                    if (now >= sModeUntil) {
                        sModeUntil = now + period
                        strafeDir = -strafeDir
                    }
                    if (strafeDir < 0) { Movement.stopRight(); Movement.startLeft() }
                    else { Movement.stopLeft(); Movement.startRight() }
                }
            }
            StrafeStyle.BURST, StrafeStyle.HYBRID -> {
                // On garde les bursts, mais on les coupe pendant push
                if (pushing) {
                    Movement.clearLeftRight()
                } else {
                    // 1) Phases
                    if (now >= sModeUntil) {
                        sMode = when (sMode) {
                            SMode.HOLD  -> if (RandomUtils.randomIntInRange(0, 99) < 60) SMode.BURST else SMode.COAST
                            SMode.BURST -> if (RandomUtils.randomIntInRange(0, 99) < 55) SMode.COAST else SMode.HOLD
                            SMode.COAST -> if (RandomUtils.randomIntInRange(0, 99) < 55) SMode.HOLD else SMode.BURST
                        }
                        val scale = 1.0 - (strafeIntensity.coerceIn(0,100) / 200.0) // intensité basse = fenêtres plus longues
                        sModeUntil = now + (when (sMode) {
                            SMode.HOLD  -> RandomUtils.randomIntInRange(HOLD_MS.first, HOLD_MS.last)
                            SMode.BURST -> RandomUtils.randomIntInRange(BURST_MS.first, BURST_MS.last)
                            SMode.COAST -> RandomUtils.randomIntInRange(COAST_MS.first, COAST_MS.last)
                        } * scale).toLong().coerceAtLeast(180L)
                        if (sMode == SMode.BURST) {
                            val flipBase = RandomUtils.randomIntInRange(BURST_FLIP_EVERY_MS.first, BURST_FLIP_EVERY_MS.last)
                            nextBurstFlipAt = now + (flipBase * scale).toLong().coerceAtLeast(80L)
                        }
                    }
                    // 2) Flip
                    if (sMode == SMode.BURST && now >= nextBurstFlipAt) {
                        nextBurstFlipAt = now + RandomUtils.randomIntInRange(BURST_FLIP_EVERY_MS.first, BURST_FLIP_EVERY_MS.last)
                        if (now >= longStrafeUntil && RandomUtils.randomIntInRange(1, 100) > BURST_SKIP_PROBA) {
                            if (!attemptLongStrafe(now)) strafeDir = -strafeDir
                            lastStrafeFlip = now
                        }
                    }
                    // 3) Application
                    if (strafeDir < 0) { Movement.stopRight(); Movement.startLeft() }
                    else { Movement.stopLeft(); Movement.startRight() }
                }
            }
        }

        // Biais centre / edge
        if (centerReady) {
            val wantLeft = preferLeftToward(centerX, centerZ)
            val myR = rFromCenter(p.posX, p.posZ)
            val oppR = rFromCenter(o.posX, o.posZ)
            val oppMoreOutside = oppR > (myR + edgeAggroRadiusBonus)
            val enforceCenter = RandomUtils.randomIntInRange(1, 100) <= centerBiasStrength + (if (oppMoreOutside) edgeAggroWeight else 0)
            if (now - lastStrafeFlip > centerBiasIntervalMs && enforceCenter && !pushing) {
                if (wantLeft && strafeDir > 0) { strafeDir = -1; lastStrafeFlip = now }
                if (!wantLeft && strafeDir < 0) { strafeDir = 1; lastStrafeFlip = now }
            }
        }

        // Anti-stagnation
        if (antiStallRef < 0f) { antiStallRef = distance; antiStallAt = now }
        else {
            val d = abs(distance - antiStallRef)
            if (d < ANTI_STALL_EPS) {
                if (now - antiStallAt >= ANTI_STALL_DELAY && now >= longStrafeUntil && !pushing) {
                    if (!attemptLongStrafe(now)) strafeDir = -strafeDir
                    lastStrafeFlip = now
                    antiStallRef = distance
                    antiStallAt = now
                }
            } else {
                antiStallRef = distance
                antiStallAt = now
            }
        }

        // Avant / arrière
        if (distance < stopForwardDist || (enableAntivoid && edgeAheadDynamic(1.0f))) {
            Movement.stopForward()
        } else if (!voidFront) {
            if (distance > reForwardDist) Movement.startForward()
        }

        // Anti-void arrière
        if (WorldUtils.airInBack(p, 2.0f) && p.onGround) {
            Movement.clearLeftRight(); Combat.stopRandomStrafe()
            Movement.startForward()
        }

        prevDistance = distance
    }
}
