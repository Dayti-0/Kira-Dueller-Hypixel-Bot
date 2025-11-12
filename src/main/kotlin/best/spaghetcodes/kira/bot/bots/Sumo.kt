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
import kotlin.math.max

class Sumo : BotBase("/play duels_sumo_duel"), MovePriority {

    override fun getName(): String = "Sumo"

    // ================= Réglages généraux =================
    private val startGroundTicksRequired = 3
    private val startSafeDelayMs = 60L

    // --- Start Center-Hop (FORCÉ au début pour gratter le centre) ---
    private val startHopEnabled = true
    private val startHopEarliestMs = 70L     // plus tôt
    private val startHopLatestMs = 160L
    private val startHopMinForwardTicks = 1  // nombre de ticks de forward avant saut

    // --- Fenêtre du saut d’engagement (second) – resserrée ---
    private val engageJumpMin = 6.4f
    private val engageJumpMax = 7.2f
    private val djumpCdMin = 650
    private val djumpCdMax = 1000

    // Pas de saut si ennemi proche/menaçant (s’applique au DEUXIÈME saut, pas au start-hop)
    private val noJumpCloseDist = 5.8f

    // AC / Prefire
    private val attackStartDist = 4.05f
    private val attackLatchMs = 220L
    private val prefireFastApproachDist = 4.6f
    private val prefireLatchMs = 160L

    // Avance courte distance
    private val stopForwardDist = 1.18f
    private val reForwardDist = 2.0f

    // Détection vide
    private val edgeProbeNear = 1.6f
    private val edgeProbeFar = 2.6f

    // Hitselecting léger (sans couper le sprint)
    private val enableHitselecting = true
    private val hitselectChance = 0.26
    private val hitselectMinDist = 3.6f
    private val hitselectMaxDist = 6.0f
    private val hitselectCooldown = 1200..1800
    private val baitDurationMin = 240
    private val baitDurationMax = 420
    private val stopSprintDuringBait = false

    // ================= Strafe ralenti (HOLD/BURST + long-strafe rare) =================
    private enum class Mode { HOLD, BURST }

    // Fenêtres HOLD/BURST (encore plus larges => cadence encore plus lente)
    private val BURST_FLIP_MIN = 180        // ms
    private val BURST_FLIP_MAX = 280
    private val BURST_WINDOW_MIN = 700L     // ms
    private val BURST_WINDOW_MAX = 1100L
    private val HOLD_WINDOW_MIN = 900L      // ms
    private val HOLD_WINDOW_MAX = 1400L

    // Long strafe opportuniste (rare et long)
    private val LONG_STRAFE_MIN = 1200L
    private val LONG_STRAFE_MAX = 1800L
    private val LONG_STRAFE_DISTANCE_CAP = 3.2f
    private val LONG_STRAFE_BASE_CHANCE = 16 // %

    // Close-strafe : délai de bascule dépendant de la distance (plus près -> plus tenu)
    private fun computeCloseStrafeDelay(distance: Float): Long = when {
        distance < 1.8f -> RandomUtils.randomIntInRange(360, 520).toLong()
        distance < 2.6f -> RandomUtils.randomIntInRange(420, 600).toLong()
        else            -> RandomUtils.randomIntInRange(520, 700).toLong()
    }

    // Anti-stagnation (si la distance ne varie pas)
    private val ANTI_STALL_EPS = 0.010f
    private val ANTI_STALL_DELAY = 380L

    // ================= États =================
    private var gameStartedAt = 0L
    private var groundTicks = 0
    private var startLatched = false

    private var mySpawnX = 0.0
    private var mySpawnZ = 0.0
    private var oppSpawnX: Double? = null
    private var oppSpawnZ: Double? = null
    private var centerX = 0.0
    private var centerZ = 0.0
    private var centerReady = false

    private var prevDistance = -1f
    private var keepACUntil = 0L

    private var canDistanceJump = true
    private var didStartHop = false
    private var forwardTicksSinceStart = 0

    private var isHitselecting = false
    private var hitselectCooldownUntil = 0L
    private var stoppedSprintForBait = false

    private var opponentOffEdge = false
    private var tapping = false
    private var tap50 = false

    // ---- Strafe state machine ----
    private var strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
    private var lastStrafeFlip = 0L
    private var mode = Mode.HOLD
    private var modeUntil = 0L
    private var burstToggleAt = 0L
    private var longStrafeUntil = 0L
    private var closeStrafeNextAt = 0L
    private var antiStallStamp = 0L
    private var antiStallDistRef = -1f

    // Radial control
    private var lastRadialMode = "neutral"
    private var radialLockUntil = 0L

    // ================= Utils =================
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

    private fun distFromCenter(x: Double, z: Double): Double {
        return hypot(x - centerX, z - centerZ)
    }

    private fun isThreatClose(distance: Float, approaching: Boolean): Boolean {
        return (distance <= noJumpCloseDist) || (approaching && distance <= noJumpCloseDist + 0.8f)
    }

    // ================= Hooks =================
    override fun onGameStart() {
        Mouse.startTracking()
        Mouse.stopLeftAC()

        // Sprint dès le premier tick
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
        groundTicks = 0
        startLatched = false

        prevDistance = -1f
        keepACUntil = 0L

        canDistanceJump = true
        didStartHop = false
        forwardTicksSinceStart = 0

        isHitselecting = false
        hitselectCooldownUntil = 0L
        stoppedSprintForBait = false

        opponentOffEdge = false
        tapping = false
        tap50 = false

        // Init strafe machine (cadence lente)
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        lastStrafeFlip = 0L
        mode = Mode.HOLD
        modeUntil = 0L
        burstToggleAt = 0L
        longStrafeUntil = 0L
        closeStrafeNextAt = 0L
        antiStallStamp = 0L
        antiStallDistRef = -1f

        lastRadialMode = "neutral"
        radialLockUntil = 0L
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
        if (isHitselecting) return
        val dur = if (tap50) 50 else 100
        tap50 = !tap50
        Combat.wTap(dur)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, dur + 15)
    }

    // ================= Tick =================
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val now = System.currentTimeMillis()
        val o = opponent()

        // ==== MATCHMAKING : freeze si Lobby Movement OFF ====
        if (o == null) {
            if (kira.config?.lobbyMovement != true) {
                Combat.stopRandomStrafe()
                Mouse.stopTracking()
                Mouse.stopLeftAC()
                Movement.clearAll()
            }
            return
        }

        // Latch du start (qq ticks au sol + mini délai)
        if (p.onGround) groundTicks++ else groundTicks = 0
        if (!startLatched && groundTicks >= startGroundTicksRequired &&
            now - gameStartedAt >= startSafeDelayMs
        ) startLatched = true

        updateCenterOnce()

        // Stop chase suicidaire si ennemi off-edge
        val isOppActuallyOffEdge = WorldUtils.entityOffEdge(o)
        opponentOffEdge = isOppActuallyOffEdge ||
                (opponentOffEdge && EntityUtils.getDistanceNoY(p, o) > 17)
        if (opponentOffEdge) {
            Mouse.stopLeftAC(); Combat.stopRandomStrafe(); Mouse.stopTracking()
            Movement.clearAll()
            return
        }

        // Sprint permanent
        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()

        val distance = EntityUtils.getDistanceNoY(p, o)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.10f)

        // Vide devant
        val voidNear = edgeAhead(edgeProbeNear)
        val voidFar = edgeAhead(edgeProbeFar)
        val voidFront = voidNear || voidFar
        if (voidFront) { Movement.stopForward(); Movement.startSneaking() } else { Movement.stopSneaking() }

        // ===== Start Center-Hop (FORCÉ, tôt) =====
        if (startHopEnabled && !didStartHop && startLatched && p.onGround && !voidFront) {
            // On attend 1-2 ticks de forward pour avoir la vélocité horizontale avant le saut
            forwardTicksSinceStart++
            val sinceStart = now - gameStartedAt
            if (sinceStart in startHopEarliestMs..startHopLatestMs &&
                forwardTicksSinceStart >= startHopMinForwardTicks
            ) {
                Movement.clearLeftRight(); Combat.stopRandomStrafe(); Movement.startForward(); Movement.startSprinting()
                Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
                didStartHop = true
                // Verrouille le second saut un court moment pour éviter double-bond instantané
                canDistanceJump = false
                TimeUtils.setTimeout({ canDistanceJump = true }, RandomUtils.randomIntInRange(1100, 1400))
                // Petit lock radial "pressure" post-hop
                lastRadialMode = "pressure"
                radialLockUntil = now + 420L
            }
        }

        // ===== AC latch / Prefire =====
        val inAttackLatch = (!isHitselecting && distance <= attackStartDist)
        val inPrefire = (!isHitselecting && approaching &&
                distance <= prefireFastApproachDist && distance > attackStartDist)
        if (kira.config?.kiraHit == true && (inAttackLatch || inPrefire)) {
            keepACUntil = now + if (inPrefire) prefireLatchMs else attackLatchMs
            Mouse.startLeftAC()
        } else if ((now >= keepACUntil && !isHitselecting) || kira.config?.kiraHit != true) {
            Mouse.stopLeftAC()
        }

        // ===== Hitselecting léger =====
        if (enableHitselecting && !isHitselecting && p.onGround &&
            now >= hitselectCooldownUntil &&
            distance in hitselectMinDist..hitselectMaxDist &&
            RandomUtils.randomDoubleInRange(0.0, 1.0) < hitselectChance &&
            !edgeAhead(2.0f)
        ) {
            isHitselecting = true
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            Movement.clearLeftRight()
            if (stopSprintDuringBait && p.isSprinting) {
                Movement.stopSprinting()
                stoppedSprintForBait = true
            } else stoppedSprintForBait = false

            val baitDur = RandomUtils.randomIntInRange(baitDurationMin, baitDurationMax)
            TimeUtils.setTimeout({
                if (!isHitselecting) return@setTimeout
                isHitselecting = false
                hitselectCooldownUntil = System.currentTimeMillis() +
                        RandomUtils.randomIntInRange(hitselectCooldown.first, hitselectCooldown.last)
                if (stoppedSprintForBait && !p.isSprinting) Movement.startSprinting()
            }, baitDur)
        }

        if (isHitselecting && distance <= attackStartDist) {
            isHitselecting = false
            hitselectCooldownUntil = now + RandomUtils.randomIntInRange(hitselectCooldown.first, hitselectCooldown.last)
            if (stoppedSprintForBait && !p.isSprinting) Movement.startSprinting()
            if (kira.config?.kiraHit == true) {
                keepACUntil = now + attackLatchMs
                Mouse.startLeftAC()
            }
        }

        // ===== Deuxième saut d’engagement (bloqué si menace proche) =====
        var performedJump = false
        val allowSecondJump = (!didStartHop || (now - gameStartedAt) > 1800)
        if (allowSecondJump && !isHitselecting && !voidFront && p.onGround &&
            distance in engageJumpMin..engageJumpMax && canDistanceJump &&
            !isThreatClose(distance, approaching)
        ) {
            Movement.clearLeftRight(); Combat.stopRandomStrafe(); Movement.startForward()
            Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            canDistanceJump = false
            TimeUtils.setTimeout({ canDistanceJump = true }, RandomUtils.randomIntInRange(djumpCdMin, djumpCdMax))
            performedJump = true
        }

        // ===== Radial Center Control =====
        var biasLeftScore = 0
        var biasRightScore = 0
        if (centerReady) {
            val myR = distFromCenter(p.posX, p.posZ)
            val opR = distFromCenter(o.posX, o.posZ)
            val deltaR = myR - opR
            // Mode "pressure" si je suis plus au centre, "recover" si je suis plus extérieur
            val newMode = when {
                deltaR <= -0.7 -> "pressure"
                deltaR >=  0.7 -> "recover"
                else -> "neutral"
            }
            // petite hystérésis pour éviter spam de changements
            if (now >= radialLockUntil || newMode == lastRadialMode) {
                lastRadialMode = newMode
                radialLockUntil = now + 260L
            }

            val goLeftToCenter = preferLeftToward(centerX, centerZ)
            when (lastRadialMode) {
                "pressure" -> {
                    // je suis côté centre -> je maintiens le biais centre pour rester “entre lui et le centre”
                    if (goLeftToCenter) biasLeftScore += 10 else biasRightScore += 10
                }
                "recover" -> {
                    // je suis trop extérieur -> je force très fort vers le centre
                    if (goLeftToCenter) biasLeftScore += 16 else biasRightScore += 16
                }
                else -> {
                    // léger biais centre par défaut
                    if (goLeftToCenter) biasLeftScore += 6 else biasRightScore += 6
                }
            }
        } else {
            // centre inconnu tout début : rien de spécial (on garde la machine lente)
        }

        // ===== Strafe ralenti : machine d’états + corrections =====
        val inLongStrafe = now < longStrafeUntil
        if (!inLongStrafe && now >= modeUntil) {
            mode = if (RandomUtils.randomIntInRange(0, 99) < 50) Mode.BURST else Mode.HOLD
            modeUntil = now + if (mode == Mode.HOLD)
                RandomUtils.randomIntInRange(HOLD_WINDOW_MIN.toInt(), HOLD_WINDOW_MAX.toInt())
            else
                RandomUtils.randomIntInRange(BURST_WINDOW_MIN.toInt(), BURST_WINDOW_MAX.toInt())
            if (mode == Mode.BURST) {
                burstToggleAt = now + RandomUtils.randomIntInRange(BURST_FLIP_MIN, BURST_FLIP_MAX)
            }
        } else if (!inLongStrafe && mode == Mode.BURST && now >= burstToggleAt) {
            strafeDir = -strafeDir
            lastStrafeFlip = now
            burstToggleAt = now + RandomUtils.randomIntInRange(BURST_FLIP_MIN, BURST_FLIP_MAX)
        }

        // Long-strafe opportuniste (rare), seulement très proche
        val canLongStrafe = !isHitselecting && !performedJump && distance <= LONG_STRAFE_DISTANCE_CAP && p.onGround
        if (canLongStrafe && now >= longStrafeUntil &&
            RandomUtils.randomIntInRange(1, 100) <= LONG_STRAFE_BASE_CHANCE
        ) {
            longStrafeUntil = now + RandomUtils.randomIntInRange(LONG_STRAFE_MIN.toInt(), LONG_STRAFE_MAX.toInt())
            // fixe une direction et la tient
            strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
            lastStrafeFlip = now
        }

        // Close-strafe tenu plus longtemps
        val closeCtrl = distance <= 2.6f && p.onGround && !inLongStrafe
        if (closeCtrl && now >= closeStrafeNextAt) {
            closeStrafeNextAt = now + computeCloseStrafeDelay(distance)
            // petite proba de flip
            if (RandomUtils.randomIntInRange(1, 100) <= 30) {
                strafeDir = -strafeDir
                lastStrafeFlip = now
            }
        }

        // Anti-stagnation de distance
        if (antiStallDistRef < 0f) {
            antiStallDistRef = distance; antiStallStamp = now
        } else {
            val d = abs(distance - antiStallDistRef)
            if (d < ANTI_STALL_EPS) {
                if (now - antiStallStamp >= ANTI_STALL_DELAY) {
                    strafeDir = -strafeDir
                    lastStrafeFlip = now
                    antiStallDistRef = distance
                    antiStallStamp = now
                }
            } else {
                antiStallDistRef = distance
                antiStallStamp = now
            }
        }

        // Biais centre additionnel + anti-edge
        if (voidFront && centerReady) {
            val wantLeft = preferLeftToward(centerX, centerZ)
            if (wantLeft) { biasLeftScore += 6 } else { biasRightScore += 6 }
        }

        // Anti-vide latéral
        if (Movement.left() && WorldUtils.airOnLeft(p, 1.5f) && p.onGround) Movement.stopLeft()
        if (Movement.right() && WorldUtils.airOnRight(p, 1.5f) && p.onGround) Movement.stopRight()

        // Application du strafe final (aucun random-strafe)
        if (!isHitselecting) {
            // Injecte le biais centre dans la décision (doucement)
            if (biasLeftScore > biasRightScore && strafeDir > 0) { strafeDir = -1; lastStrafeFlip = now }
            if (biasRightScore > biasLeftScore && strafeDir < 0) { strafeDir = 1; lastStrafeFlip = now }

            if (strafeDir < 0) { Movement.stopRight(); Movement.startLeft() }
            else { Movement.stopLeft(); Movement.startRight() }
        } else {
            Combat.stopRandomStrafe()
            Movement.clearLeftRight()
        }

        // ===== Avant / arrière =====
        if (distance < stopForwardDist || edgeAhead(1.0f)) {
            Movement.stopForward()
        } else if (!tapping && !voidFront && !isHitselecting) {
            if (distance > reForwardDist) Movement.startForward()
        }

        // ===== Anti-void arrière =====
        if (WorldUtils.airInBack(p, 2.0f) && p.onGround) {
            Movement.clearLeftRight(); Combat.stopRandomStrafe()
            if (!tapping) Movement.startForward()
        }

        prevDistance = distance
    }
}
