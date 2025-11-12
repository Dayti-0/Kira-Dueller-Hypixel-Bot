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

    // ---------- Tuning ----------
    private val engageJumpMin = 6.0f          // saut d'engagement utile (garanti à ~6–7)
    private val engageJumpMax = 7.2f
    private val strafeFlipBase = 420..680     // délai de flip du strafe par défaut
    private val edgeProbeNear = 1.6f          // détection du vide proche
    private val edgeProbeFar = 2.6f           // détection du vide un peu plus loin
    private val stopForwardDist = 1.2f        // arrêt d'avance si on est trop collé / vide devant
    private val reForwardDist = 2.0f          // reprise d'avance au-delà

    // Latch AC : démarrer l’attaque légèrement plus tôt et la maintenir brièvement
    private val attackStartDist = 4.05f       // ↑ avant 4 blocs pour battre la latence adverse
    private val attackLatchMs = 220L

    // Pré-fire si approche rapide (avant la vraie portée)
    private val prefireFastApproachDist = 4.6f
    private val prefireLatchMs = 160L

    // Distance Jump contrôlé (cooldown interne)
    private var canDistanceJump = true
    private val djumpCdMin = 500
    private val djumpCdMax = 1000

    // Hitselecting / bait (sans S-tap arrière désormais)
    private val enableHitselecting = true
    private val hitselectChance = 0.28
    private val hitselectMinDist = 3.6f
    private val hitselectMaxDist = 6.2f
    private val hitselectCooldown = 1200..1800
    private val baitDurationMin = 240
    private val baitDurationMax = 420
    private val stopSprintDuringBait = true

    // ---------- États ----------
    private var prevDistance = -1f
    private var lastStrafeSwitch = 0L
    private var strafeDir = 1
    private var stagnantSince = 0L

    private var centerX = 0.0
    private var centerZ = 0.0

    private var tapping = false
    private var keepACUntil = 0L
    private var tap50 = false

    private var isHitselecting = false
    private var hitselectCooldownUntil = 0L
    private var stoppedSprintForBait = false

    override fun onGameStart() {
        Mouse.startTracking()
        Mouse.stopLeftAC()
        Movement.clearAll()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping()

        // point "centre" ≈ spawn du joueur pour orienter vers l’intérieur
        val p = mc.thePlayer
        if (p != null) {
            centerX = p.posX
            centerZ = p.posZ
        }

        // Saut immédiat de départ pour prendre le milieu (le 1er saut est safe)
        Movement.singleJump(RandomUtils.randomIntInRange(120, 160))

        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        stagnantSince = 0L
        tapping = false
        keepACUntil = 0L
        tap50 = false

        isHitselecting = false
        hitselectCooldownUntil = 0L
        stoppedSprintForBait = false

        canDistanceJump = true
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

    override fun onAttack() {
        // W-Tap alterné (50ms / 100ms), très efficace en sumo
        val dur = if (tap50) 50 else 100
        tap50 = !tap50

        Combat.wTap(dur)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, dur + 15)
    }

    private fun edgeAhead(dist: Float): Boolean {
        val p = mc.thePlayer ?: return false
        // s'il n'y a PAS de bloc devant nous à la hauteur du pied -> vide
        return WorldUtils.blockInFront(p, dist, 0.0f) == Blocks.air
    }

    private fun preferLeftTowardCenter(): Boolean {
        val p = mc.thePlayer ?: return false
        // vrai = le point (centre) est à gauche du yaw actuel -> strafe gauche rapproche du centre
        return WorldUtils.leftOrRightToPoint(p, Vec3(centerX, 0.0, centerZ))
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        // Sprint permanent si safe
        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.10f)

        // ---- Latch d’attaque & pré-fire (plus tôt) ----
        val inAttackLatch = (!Mouse.isUsingPotion() && !Mouse.isUsingProjectile()
                && !isHitselecting && distance <= attackStartDist)

        val inPrefire = (!Mouse.isUsingPotion() && !Mouse.isUsingProjectile()
                && !isHitselecting && approaching
                && distance <= prefireFastApproachDist && distance > attackStartDist)

        if (kira.config?.kiraHit == true && (inAttackLatch || inPrefire)) {
            val latch = if (inPrefire) prefireLatchMs else attackLatchMs
            keepACUntil = now + latch
            Mouse.startLeftAC()
        } else {
            if ((now >= keepACUntil && !isHitselecting) || kira.config?.kiraHit != true) Mouse.stopLeftAC()
        }

        // ---- Éviter le vide : ne JAMAIS avancer quand c'est "air" devant ----
        val voidNear = edgeAhead(edgeProbeNear)
        val voidFar = edgeAhead(edgeProbeFar)

        if (voidNear || voidFar) {
            Movement.stopForward()
        } else if (!tapping && distance >= reForwardDist && !isHitselecting) {
            Movement.startForward()
        }

        // ---- Distance-jump contrôlé (engage garanti à 6–7) ----
        if (!isHitselecting &&
            !voidNear && !voidFar &&
            p.onGround &&
            distance in engageJumpMin..engageJumpMax &&
            canDistanceJump
        ) {
            // saut même si on vient de w-tap (on ne bloque plus par 'tapping')
            Movement.singleJump(RandomUtils.randomIntInRange(120, 160))
            canDistanceJump = false
            TimeUtils.setTimeout({ canDistanceJump = true }, RandomUtils.randomIntInRange(djumpCdMin, djumpCdMax))
        }

        // =================== HITSELECTING / BAIT (sans recul) ===================
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

            // Pas de S-tap arrière : on se contente d’un “freeze” léger et d’un éventuel cut sprint
            if (stopSprintDuringBait && p.isSprinting) {
                Movement.stopSprinting()
                stoppedSprintForBait = true
            } else {
                stoppedSprintForBait = false
            }

            // Fin du bait après une fenêtre aléatoire + cooldown global
            val baitDur = RandomUtils.randomIntInRange(baitDurationMin, baitDurationMax)
            TimeUtils.setTimeout({
                if (!isHitselecting) return@setTimeout
                isHitselecting = false
                hitselectCooldownUntil = System.currentTimeMillis() +
                        RandomUtils.randomIntInRange(hitselectCooldown.first, hitselectCooldown.last)

                if (stoppedSprintForBait && !p.isSprinting) {
                    Movement.startSprinting()
                }
            }, baitDur)
        }

        // If the opponent closes in while baiting, stop hitselecting and attack
        if (isHitselecting && distance <= attackStartDist) {
            isHitselecting = false
            hitselectCooldownUntil = now + RandomUtils.randomIntInRange(hitselectCooldown.first, hitselectCooldown.last)
            if (stoppedSprintForBait && !p.isSprinting) {
                Movement.startSprinting()
            }
            if (kira.config?.kiraHit == true) {
                keepACUntil = now + attackLatchMs
                Mouse.startLeftAC()
            }
        }

        // =================== STRAFE & DIRECTION ===================
        val movePriority = arrayListOf(0, 0)
        var clear = false
        // valeur de base : pas de random strafe près d’un bord, sinon selon la distance
        var randomStrafe = (distance >= 3.2f && distance <= 7.5f && !isHitselecting && !(voidNear || voidFar))

        // Si on est proche d'un bord (air devant), forcer le strafe vers le centre
        if (voidNear || voidFar) {
            val toLeft = preferLeftTowardCenter()
            val w = 10
            if (toLeft) movePriority[0] += w else movePriority[1] += w
            // randomStrafe reste tel quel (déjà false si voidNear/voidFar)
        } else {
            // Strafe assisté par l’angle sur l’ennemi : recale doucement le côté
            val rotations = EntityUtils.getRotations(opp, p, false)
            if (rotations != null && now - lastStrafeSwitch > 320) {
                val preferSide = if (rotations[0] < 0) +1 else -1
                if (preferSide != strafeDir) {
                    strafeDir = preferSide
                    lastStrafeSwitch = now
                }
            }

            // Alterner régulièrement pour rester imprévisible
            if (now - lastStrafeSwitch > RandomUtils.randomIntInRange(strafeFlipBase.first, strafeFlipBase.last)) {
                strafeDir = -strafeDir
                lastStrafeSwitch = now
            }

            // Anti-stagnation à mi-distance
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

            val weight = if (distance < 3.2f) 8 else 6
            if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
            // randomStrafe déjà positionné par défaut
        }

        // ---- Gestion avant/arrière simple pour coller sans s'emmêler ----
        if (distance < stopForwardDist || edgeAhead(1.0f)) {
            Movement.stopForward()
        } else if (!tapping && distance > reForwardDist && !voidNear && !voidFar && !isHitselecting) {
            Movement.startForward()
        }

        // ---- Pas de saut en mêlée (Sumo) ----
        Movement.stopJumping()

        // Si bait actif : on désactive l’AC, sinon l’AC est géré par le latch plus haut
        if (isHitselecting) Mouse.stopLeftAC()

        handle(clear, randomStrafe, movePriority)
        prevDistance = distance
    }
}
