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

class Sumo : BotBase("/play duels_sumo_duel"), MovePriority {

    override fun getName(): String = "Sumo"

    // --------- Réglages principaux ----------
    // Latch sol + micro délai de sécurité pour éviter le “saut dans le vide”
    private val startGroundTicksRequired = 3
    private val startSafeDelayMs = 80L

    // Start-hop (saut de début) = tôt, avant d'être trop proche
    private val startHopEnabled = true
    private val startHopEarliestMs = 120L   // après start
    private val startHopLatestMs = 280L
    private val startHopDistMin = 7.8f
    private val startHopDistMax = 10.5f

    // Fenêtre du saut d'engagement (deuxième saut éventuel, plus tard)
    // On la restreint un peu pour ne pas “bondir dessus” juste après le start-hop.
    private val engageJumpMin = 6.4f
    private val engageJumpMax = 7.2f
    private val djumpCdMin = 600
    private val djumpCdMax = 1000

    // Portées d’AC/prefire (inchangées)
    private val attackStartDist = 4.05f
    private val attackLatchMs = 220L
    private val prefireFastApproachDist = 4.6f
    private val prefireLatchMs = 160L

    // Avance/arrêt à très courte distance
    private val stopForwardDist = 1.18f
    private val reForwardDist = 2.0f

    // Détection vide
    private val edgeProbeNear = 1.6f
    private val edgeProbeFar = 2.6f

    // Hitselecting léger (sans S-tap arrière)
    private val enableHitselecting = true
    private val hitselectChance = 0.28
    private val hitselectMinDist = 3.6f
    private val hitselectMaxDist = 6.2f
    private val hitselectCooldown = 1200..1800
    private val baitDurationMin = 240
    private val baitDurationMax = 420

    // >>> Sprint maintenu dès le début (ne pas le couper pendant bait)
    private val stopSprintDuringBait = false

    // --------- États ----------
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
    private var lastStrafeSwitch = 0L
    private var strafeDir = 1
    private var stagnantSince = 0L

    private var tapping = false
    private var tap50 = false
    private var keepACUntil = 0L

    private var canDistanceJump = true
    private var didStartHop = false

    private var isHitselecting = false
    private var hitselectCooldownUntil = 0L
    private var stoppedSprintForBait = false

    private var opponentOffEdge = false

    // --------- Utils ----------
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

    // --------- Hooks ----------
    override fun onGameStart() {
        Mouse.startTracking()
        Mouse.stopLeftAC()

        // Sprint dès le premier tick
        Movement.clearAll()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping()

        // Mémorise mon spawn (pour calculer le centre)
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
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        stagnantSince = 0L

        tapping = false
        tap50 = false
        keepACUntil = 0L

        canDistanceJump = true
        didStartHop = false

        isHitselecting = false
        hitselectCooldownUntil = 0L
        stoppedSprintForBait = false

        opponentOffEdge = false
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

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val o = opponent()

        if (p.onGround) groundTicks++ else groundTicks = 0
        if (!startLatched && groundTicks >= startGroundTicksRequired &&
            System.currentTimeMillis() - gameStartedAt >= startSafeDelayMs
        ) {
            startLatched = true
        }

        if (o == null) {
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            // Maintenir sprint & légère avance (si safe) pour rester vers le centre
            if (!p.isSprinting) Movement.startSprinting()
            if (centerReady && !edgeAhead(1.2f)) Movement.startForward()
            return
        }

        updateCenterOnce()

        val isOppActuallyOffEdge = WorldUtils.entityOffEdge(o)
        opponentOffEdge = isOppActuallyOffEdge ||
                (opponentOffEdge && EntityUtils.getDistanceNoY(p, o) > 17)

        if (opponentOffEdge) {
            Mouse.stopLeftAC(); Combat.stopRandomStrafe(); Mouse.stopTracking()
            Movement.clearAll()
            return
        }

        // Sprint permanent (ne jamais lâcher au début)
        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, o)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.10f)

        // ------------- Détection vide devant -------------
        val voidNear = edgeAhead(edgeProbeNear)
        val voidFar = edgeAhead(edgeProbeFar)
        val voidFront = voidNear || voidFar

        if (voidFront) {
            Movement.stopForward()
            Movement.startSneaking()
        } else {
            Movement.stopSneaking()
        }

        // ------------- Start-hop EARLY -------------
        if (startHopEnabled && !didStartHop && startLatched && p.onGround && !voidFront) {
            val sinceStart = now - gameStartedAt
            if (sinceStart in startHopEarliestMs..startHopLatestMs &&
                distance in startHopDistMin..startHopDistMax
            ) {
                // saut court, tôt, pendant qu'on sprint déjà
                Movement.clearLeftRight(); Combat.stopRandomStrafe(); Movement.startForward()
                Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
                didStartHop = true

                // Évite un second jump collé derrière (on verrouille temporairement)
                canDistanceJump = false
                TimeUtils.setTimeout({ canDistanceJump = true }, RandomUtils.randomIntInRange(1100, 1400))
            }
        }

        // ------------- AC latch / Prefire -------------
        val inAttackLatch = (!isHitselecting && distance <= attackStartDist)
        val inPrefire = (!isHitselecting && approaching &&
                distance <= prefireFastApproachDist && distance > attackStartDist)

        if (kira.config?.kiraHit == true && (inAttackLatch || inPrefire)) {
            val latch = if (inPrefire) prefireLatchMs else attackLatchMs
            keepACUntil = now + latch
            Mouse.startLeftAC()
        } else {
            if ((now >= keepACUntil && !isHitselecting) || kira.config?.kiraHit != true) {
                Mouse.stopLeftAC()
            }
        }

        // ------------- Hitselecting (léger) -------------
        if (enableHitselecting && !isHitselecting && !tapping && p.onGround &&
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
            } else {
                stoppedSprintForBait = false
            }

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

        // ------------- Distance-jump (engagement) -------------
        var performedJump = false
        val allowSecondJump = (!didStartHop || (now - gameStartedAt) > 1800)
        if (allowSecondJump && !isHitselecting && !voidFront && p.onGround &&
            distance in engageJumpMin..engageJumpMax && canDistanceJump
        ) {
            Movement.clearLeftRight(); Combat.stopRandomStrafe(); Movement.startForward()
            Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            canDistanceJump = false
            TimeUtils.setTimeout({ canDistanceJump = true }, RandomUtils.randomIntInRange(djumpCdMin, djumpCdMax))
            performedJump = true
        }

        // ------------- Prise de centre & strafe dirigé -------------
        val movePriority = arrayListOf(0, 0)
        var clearStrafe = false
        var randomStrafe = false

        if (!performedJump) {
            val biasWeight = if (distance < 3.2f) 10 else 7
            if (centerReady) {
                val goLeft = preferLeftToward(centerX, centerZ)
                if (goLeft) movePriority[0] += biasWeight else movePriority[1] += biasWeight
            } else {
                randomStrafe = distance in 3.0f..7.5f && !isHitselecting && !voidFront
            }

            val deltaDist = if (prevDistance > 0f) kotlin.math.abs(distance - prevDistance) else 999f
            if (distance in 1.8f..3.6f) {
                if (deltaDist < 0.03f) {
                    if (stagnantSince == 0L) stagnantSince = now
                    else if (now - stagnantSince > 520 && now - lastStrafeSwitch > 280) {
                        strafeDir = -strafeDir
                        lastStrafeSwitch = now
                        stagnantSince = 0L
                    }
                } else stagnantSince = 0L
            } else stagnantSince = 0L

            if (now - lastStrafeSwitch > RandomUtils.randomIntInRange(420, 680)) {
                strafeDir = -strafeDir
                lastStrafeSwitch = now
            }

            if (Movement.left() && WorldUtils.airOnLeft(p, 1.5f) && p.onGround) Movement.stopLeft()
            if (Movement.right() && WorldUtils.airOnRight(p, 1.5f) && p.onGround) Movement.stopRight()

            if (voidFront && centerReady) {
                val goLeft = preferLeftToward(centerX, centerZ)
                if (goLeft) { Movement.stopRight(); Movement.startLeft(); Combat.stopRandomStrafe() }
                else { Movement.stopLeft(); Movement.startRight(); Combat.stopRandomStrafe() }
                randomStrafe = false
            }

            if (distance <= 2.6f) clearStrafe = true
        }

        // ------------- Avant / arrière -------------
        if (distance < stopForwardDist || edgeAhead(1.0f)) {
            Movement.stopForward()
        } else if (!tapping && !voidFront && !isHitselecting) {
            if (distance > reForwardDist) Movement.startForward()
        }

        // ------------- Sélection strafe finale -------------
        if (clearStrafe || tapping || isHitselecting) {
            Combat.stopRandomStrafe()
            Movement.clearLeftRight()
        } else {
            if (randomStrafe) {
                Movement.clearLeftRight()
                Combat.startRandomStrafe(900, 1400)
            } else {
                Combat.stopRandomStrafe()
                if (movePriority[0] > movePriority[1]) {
                    Movement.stopRight(); Movement.startLeft()
                } else if (movePriority[1] > movePriority[0]) {
                    Movement.stopLeft(); Movement.startRight()
                } else {
                    if (strafeDir < 0) { Movement.stopRight(); Movement.startLeft() }
                    else { Movement.stopLeft(); Movement.startRight() }
                }
            }
        }

        // ------------- Anti-void arrière -------------
        if (WorldUtils.airInBack(p, 2.0f) && p.onGround) {
            Movement.clearLeftRight(); Combat.stopRandomStrafe()
            if (!tapping) Movement.startForward()
        }

        prevDistance = distance
    }
}
