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
import kotlin.math.max
import kotlin.math.min

/**
 * Sumo (KIRA) – version corrigée et orientée "prise de centre"
 * - Pas de saut au lancement tant qu'on n'a pas "touché" le sol X ticks
 * - Centre calculé = milieu des deux spawns (capturés au premier tick où les deux entités existent)
 * - Anti-void strict + gestion d'AC (latch de quelques ms) + distance jump uniquement safe
 * - Hitselecting sans recul, sprint stoppé pendant l’appât (optionnel/contrôlé dans la classe)
 * - W-tap alterné (50 / 100 ms)
 */
class Sumo : BotBase("/play duels_sumo_duel"), MovePriority {

    override fun getName(): String = "Sumo"

    // ---------- Tuning général ----------
    private val startGroundTicksRequired = 5        // nb de ticks onGround requis avant d'autoriser saut/engage
    private val startSafeDelayMs = 150L              // marge de sécurité après GAME START

    private val attackStartDist = 4.05f             // portée où on “latch” (AC) légèrement en avance
    private val attackLatchMs = 220L

    private val prefireFastApproachDist = 4.6f
    private val prefireLatchMs = 160L

    private val stopForwardDist = 1.18f             // trop collé -> on stop l'avance
    private val reForwardDist = 2.0f                // on reprend l'avance au-delà

    private val edgeProbeNear = 1.6f                // air très proche devant
    private val edgeProbeFar = 2.6f                 // air un peu plus loin devant

    // Distance-jump contrôlé (pour coller à 6–7 blocs)
    private val engageJumpMin = 6.0f
    private val engageJumpMax = 7.2f
    private val djumpCdMin = 500
    private val djumpCdMax = 1000

    // Hitselecting (appât léger) — sans S-tap arrière
    private val enableHitselecting = true
    private val hitselectChance = 0.28
    private val hitselectMinDist = 3.6f
    private val hitselectMaxDist = 6.2f
    private val hitselectCooldown = 1200..1800
    private val baitDurationMin = 240
    private val baitDurationMax = 420
    private val stopSprintDuringBait = true

    // ---------- États ----------
    private var gameStartedAt = 0L
    private var groundTicks = 0
    private var startLatched = false               // devient true après X ticks onGround + délai

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

    private var isHitselecting = false
    private var hitselectCooldownUntil = 0L
    private var stoppedSprintForBait = false

    private var opponentOffEdge = false

    // ---------- Utils locaux ----------
    private fun edgeAhead(dist: Float): Boolean {
        val p = mc.thePlayer ?: return false
        // Si pas de bloc à hauteur du pied dans l'axe -> c'est le vide
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

    // ---------- Lifecycle ----------
    override fun onGameStart() {
        Mouse.startTracking()
        Mouse.stopLeftAC()
        Movement.clearAll()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping()

        val p = mc.thePlayer
        if (p != null) {
            mySpawnX = p.posX
            mySpawnZ = p.posZ
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
        // Évite de w-tap pendant bait
        if (isHitselecting) return
        val dur = if (tap50) 50 else 100
        tap50 = !tap50
        Combat.wTap(dur)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, dur + 15)
    }

    // ---------- Tick principal ----------
    override fun onTick() {
        val p = mc.thePlayer ?: return
        val o = opponent()

        // Latch du start : attendre quelques ticks "onGround" + un léger délai
        if (p.onGround) groundTicks++ else groundTicks = 0
        if (!startLatched && groundTicks >= startGroundTicksRequired &&
            System.currentTimeMillis() - gameStartedAt >= startSafeDelayMs
        ) {
            startLatched = true
        }

        // Opponent peut disparaître entre deux ticks
        if (o == null) {
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            Movement.startSprinting()
            // On garde un léger forward pour rester vers le centre si on l’a
            if (centerReady && !edgeAhead(1.2f)) Movement.startForward()
            return
        }

        // MAJ centre une seule fois quand on a les deux spawns
        updateCenterOnce()

        // Detec off edge (ne pas se suicider en chase)
        val isOppActuallyOffEdge = WorldUtils.entityOffEdge(o)
        opponentOffEdge = isOppActuallyOffEdge ||
                (opponentOffEdge && EntityUtils.getDistanceNoY(p, o) > 17)

        if (opponentOffEdge) {
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            Mouse.stopTracking()
            Movement.clearAll()
            return
        }

        // Sprint permanent si safe
        if (!p.isSprinting && !edgeAhead(1.0f)) Movement.startSprinting()
        Mouse.startTracking()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, o)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.10f)

        // --------- AC latch / Pré-fire ---------
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

        // --------- Anti-void frontal ---------
        val voidNear = edgeAhead(edgeProbeNear)
        val voidFar = edgeAhead(edgeProbeFar)
        val voidFront = voidNear || voidFar

        if (voidFront) {
            Movement.stopForward()
            Movement.startSneaking()
        } else {
            Movement.stopSneaking()
        }

        // --------- Hitselecting (appât léger) ---------
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

        // Si l’adversaire ferme pendant l’appât, on sort de l’état et on enclenche l’AC
        if (isHitselecting && distance <= attackStartDist) {
            isHitselecting = false
            hitselectCooldownUntil = now + RandomUtils.randomIntInRange(hitselectCooldown.first, hitselectCooldown.last)
            if (stoppedSprintForBait && !p.isSprinting) Movement.startSprinting()
            if (kira.config?.kiraHit == true) {
                keepACUntil = now + attackLatchMs
                Mouse.startLeftAC()
            }
        }

        // --------- Distance-jump contrôlé (uniquement safe et après latch du start) ---------
        var performedJump = false
        if (startLatched && !isHitselecting && !voidFront && p.onGround &&
            distance in engageJumpMin..engageJumpMax && canDistanceJump
        ) {
            Movement.clearLeftRight(); Combat.stopRandomStrafe(); Movement.startForward()
            Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            canDistanceJump = false
            TimeUtils.setTimeout({ canDistanceJump = true }, RandomUtils.randomIntInRange(djumpCdMin, djumpCdMax))
            performedJump = true
        }

        // --------- Prise de centre & strafe dirigé ---------
        val movePriority = arrayListOf(0, 0)
        var clearStrafe = false
        var randomStrafe = false

        if (!performedJump) {
            // Biais vers le centre si connu, sinon léger random
            val biasWeight = if (distance < 3.2f) 10 else 7
            if (centerReady) {
                val goLeft = preferLeftToward(centerX, centerZ)
                if (goLeft) movePriority[0] += biasWeight else movePriority[1] += biasWeight
            } else {
                randomStrafe = distance in 3.0f..7.5f && !isHitselecting && !voidFront
            }

            // Anti-stagnation : si on “piétine” à mi-distance, on inverse parfois le strafe
            val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
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

            // Flip périodique pour rester imprévisible
            if (now - lastStrafeSwitch > RandomUtils.randomIntInRange(420, 680)) {
                strafeDir = -strafeDir
                lastStrafeSwitch = now
            }

            // Éloignement des bords latéraux (si air sur le côté on coupe ce côté)
            if (Movement.left() && WorldUtils.airOnLeft(p, 1.5f) && p.onGround) Movement.stopLeft()
            if (Movement.right() && WorldUtils.airOnRight(p, 1.5f) && p.onGround) Movement.stopRight()

            // Si vraiment proche d’un bord devant : forcer le strafe qui ramène vers le centre
            if (voidFront && centerReady) {
                val goLeft = preferLeftToward(centerX, centerZ)
                if (goLeft) { Movement.stopRight(); Movement.startLeft(); Combat.stopRandomStrafe() }
                else { Movement.stopLeft(); Movement.startRight(); Combat.stopRandomStrafe() }
                clearStrafe = false
                randomStrafe = false
            }

            // Si très près ou en bon combo : on clarifie le strafe pour ne pas s’emmêler
            if (distance <= 2.6f) {
                clearStrafe = true
            }
        }

        // --------- Gestion avant / arrière ---------
        if (distance < stopForwardDist || edgeAhead(1.0f)) {
            Movement.stopForward()
        } else if (!tapping && !voidFront && !isHitselecting) {
            if (distance > reForwardDist) Movement.startForward()
        }

        // --------- Sélection strafe finale ---------
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
                    // appui léger sur la dynamique courante
                    if (strafeDir < 0) { Movement.stopRight(); Movement.startLeft() }
                    else { Movement.stopLeft(); Movement.startRight() }
                }
            }
        }

        // --------- Anti-void arrière ---------
        if (WorldUtils.airInBack(p, 2.0f) && p.onGround) {
            Movement.clearLeftRight(); Combat.stopRandomStrafe()
            if (!tapping) Movement.startForward()
        }

        prevDistance = distance
    }
}
