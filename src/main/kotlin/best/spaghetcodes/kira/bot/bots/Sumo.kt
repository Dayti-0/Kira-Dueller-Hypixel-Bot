package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
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

class Sumo : BotBase("/play duels_sumo_duel"), MovePriority {

    override fun getName(): String = "Sumo"

    // ================== Réglages généraux ==================
    private val startSafeDelayMs = 40L

    // ---- Saut #1 exactement à 0.3 s (sans check onGround) ----
    private val startHopAtMs = 300L
    private val blockZoneJumpsForMsAfterStart = 600L  // verrou "anti double-saut" après le saut à 0,3 s

    // ---- Saut "zone" ----
    // Règle: SAUTER si distance > 7.0f ; ne jamais sauter si distance ≤ 7.0f
    private val jumpZoneThreshold = 7.0f
    // réarmement anti re-spam: revenir bien à l'intérieur + attendre un délai
    private val rearmInnerDist = 6.2f
    private val zoneRearmDelayMs = 1400L

    // AC / Prefire
    private val attackStartDist = 4.05f
    private val attackLatchMs = 220L
    private val prefireFastApproachDist = 4.6f
    private val prefireLatchMs = 160L

    // Avance / stop court
    private val stopForwardDist = 1.18f
    private val reForwardDist = 2.0f

    // Détection vide
    private val edgeProbeNear = 1.6f
    private val edgeProbeFar = 2.6f

    // ================== Strafe "Burst Sumo" ==================
    private enum class SMode { HOLD, BURST, COAST }

    // Phases : HOLD (ligne stable) → BURST (micro-basculements irréguliers) → COAST (repos)
    private val HOLD_MS = 900..1500
    private val COAST_MS = 500..900
    private val BURST_MS = 300..520
    private val BURST_FLIP_EVERY_MS = 120..200  // cadence des flips pendant BURST (mais on skip parfois)
    private val BURST_SKIP_PROBA = 30           // % de chance de sauter un flip (rend le pattern imprévisible)

    // Close-strafe (tenir davantage la direction quand on est collés)
    private fun closeStrafeDelay(distance: Float): Long = when {
        distance < 1.8f -> 360L..520L
        distance < 2.6f -> 420L..600L
        else            -> 520L..700L
    }.let { RandomUtils.randomIntInRange(it.start.toInt(), it.endInclusive.toInt()).toLong() }

    // Anti-stagnation
    private val ANTI_STALL_EPS = 0.010f
    private val ANTI_STALL_DELAY = 380L

    // ================== États ==================
    private var gameStartedAt = 0L

    // Start hop
    private var startHopDone = false

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

        // Start-hop
        startHopDone = false

        // Zone jump
        zoneArmed = true
        lastZoneJumpAt = 0L

        // Strafe
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        sMode = SMode.HOLD
        sModeUntil = 0L
        nextBurstFlipAt = 0L
        nextCloseStrafeAt = 0L
        lastStrafeFlip = 0L
        antiStallRef = -1f
        antiStallAt = 0L

        prevDistance = -1f
        keepACUntil = 0L
        opponentOffEdge = false
        tapping = false
        tap50 = false
    }

    override fun onGameEnd() {
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
        // W-tap propre (hitselect désactivé)
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

        // Sprint permanent
        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()

        // Évite chase suicidaire si l’ennemi tombe
        val isOppActuallyOffEdge = WorldUtils.entityOffEdge(o)
        opponentOffEdge = isOppActuallyOffEdge ||
                (opponentOffEdge && EntityUtils.getDistanceNoY(p, o) > 17)
        if (opponentOffEdge) {
            Mouse.stopLeftAC(); Movement.clearAll(); Combat.stopRandomStrafe(); Mouse.stopTracking()
            return
        }

        val distance = EntityUtils.getDistanceNoY(p, o)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.10f)

        // Anti-void devant
        val voidNear = edgeAhead(edgeProbeNear)
        val voidFar = edgeAhead(edgeProbeFar)
        val voidFront = voidNear || voidFar
        if (voidFront) { Movement.stopForward(); Movement.startSneaking() } else { Movement.stopSneaking() }

        // ===== Saut #1 exact à 0.3 s (pas de check onGround) =====
        if (!startHopDone && (now - gameStartedAt) >= (startHopAtMs + startSafeDelayMs)) {
            Movement.clearLeftRight()
            Movement.startForward(); Movement.startSprinting()
            Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            startHopDone = true
            lastZoneJumpAt = now  // protège contre un double saut immédiat
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

        // ===== Saut "zone" (règle stricte > 7.0f, jamais ≤ 7.0f) =====
        val postStartLockActive = startHopDone && (now - gameStartedAt) < (startHopAtMs + blockZoneJumpsForMsAfterStart)
        val overZone = distance > jumpZoneThreshold

        if (zoneArmed && !postStartLockActive && overZone && !voidFront) {
            // on saute dès qu'on est > 7.0 blocs
            Movement.clearLeftRight(); Movement.startForward()
            Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            zoneArmed = false
            lastZoneJumpAt = now
        } else if (!zoneArmed) {
            // réarmer seulement après être bien rentré ≤ rearmInnerDist ET avoir attendu un petit délai
            if (distance <= rearmInnerDist && (now - lastZoneJumpAt) >= zoneRearmDelayMs) {
                zoneArmed = true
            }
        }

        // ===== Strafe "Burst Sumo" imprévisible (pas de spam rapide) =====
        // 1) Gestion des phases
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

        // 2) Micro-bursts irréguliers
        if (sMode == SMode.BURST && now >= nextBurstFlipAt) {
            if (RandomUtils.randomIntInRange(1, 100) > BURST_SKIP_PROBA) {
                strafeDir = -strafeDir
                lastStrafeFlip = now
            }
            nextBurstFlipAt = now + RandomUtils.randomIntInRange(BURST_FLIP_EVERY_MS.first, BURST_FLIP_EVERY_MS.last)
        }

        // 3) Close-strafe tenu plus longtemps quand collés
        val closeCtrl = distance <= 2.6f && p.onGround
        if (closeCtrl && now >= nextCloseStrafeAt) {
            nextCloseStrafeAt = now + closeStrafeDelay(distance)
            if (RandomUtils.randomIntInRange(1, 100) <= 35) {
                strafeDir = -strafeDir
                lastStrafeFlip = now
            }
        }

        // 4) Anti-stagnation
        if (antiStallRef < 0f) { antiStallRef = distance; antiStallAt = now }
        else {
            val d = abs(distance - antiStallRef)
            if (d < ANTI_STALL_EPS) {
                if (now - antiStallAt >= ANTI_STALL_DELAY) {
                    strafeDir = -strafeDir
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
            if (voidFront) {
                if (wantLeft && strafeDir > 0) { strafeDir = -1; lastStrafeFlip = now }
                if (!wantLeft && strafeDir < 0) { strafeDir = 1; lastStrafeFlip = now }
            } else if (now - lastStrafeFlip > 300) {
                if (wantLeft && strafeDir > 0) { strafeDir = -1; lastStrafeFlip = now }
                if (!wantLeft && strafeDir < 0) { strafeDir = 1; lastStrafeFlip = now }
            }
        }

        // Anti-vide latéral
        if (Movement.left() && WorldUtils.airOnLeft(p, 1.5f) && p.onGround) Movement.stopLeft()
        if (Movement.right() && WorldUtils.airOnRight(p, 1.5f) && p.onGround) Movement.stopRight()

        // Application du strafe (pas de random-strafe lib)
        if (strafeDir < 0) { Movement.stopRight(); Movement.startLeft() }
        else { Movement.stopLeft(); Movement.startRight() }

        // ===== Avant / arrière =====
        if (distance < stopForwardDist || edgeAhead(1.0f)) {
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
