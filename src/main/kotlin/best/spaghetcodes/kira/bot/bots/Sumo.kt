package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import java.util.Timer
import kotlin.math.abs
import kotlin.math.hypot

class Sumo : BotBase("/play duels_sumo_duel"), MovePriority {

    override fun getName(): String = "Sumo"

    // ---------- Tuning ----------
    private val arenaCenterX = 0.0            // centre fixe de l’arène (Hypixel Sumo)
    private val arenaCenterZ = 0.0

    private val firstJumpDelayRange = 60..120 // délai humain avant le saut initial
    private val firstJumpHoldRange = 120..160 // durée d’appui sur la touche jump
    private val jumpStopBufferMs = 80L

    private val engageJumpMin = 5.5f          // saut d'engagement utile (fenêtre 5.5–7.2)
    private val engageJumpMax = 7.2f
    private val engageJumpHoldRange = 120..160
    private val djumpCdMin = 500              // cooldown variable entre deux sauts d'engagement
    private val djumpCdMax = 1000

    private val strafeFlipWindow = 420..680   // délai pseudo-aléatoire de flip du strafe
    private val stagnationDistanceMin = 1.8f
    private val stagnationDistanceMax = 3.6f
    private val stagnationDeltaThreshold = 0.03f
    private val stagnationTimeMs = 520L
    private val strafeCloseWeight = 8
    private val strafeFarWeight = 6
    private val edgeStrafeWeight = 12
    private val centerBiasWeightNear = 4
    private val centerBiasWeightFar = 8
    private val centerSoftRadius = 3.0f

    private val edgeProbeNear = 1.6f          // détection du vide proche
    private val edgeProbeFar = 2.6f           // détection du vide un peu plus loin
    private val immediateEdgeProbe = 1.0f
    private val stopForwardDist = 1.2f        // arrêt d'avance si on est trop collé / vide devant
    private val reForwardDist = 2.0f          // reprise d'avance au-delà

    // Latch AC : démarrer l’attaque légèrement plus tôt et la maintenir brièvement
    private val attackStartDist = 4.05f       // ↑ avant 4 blocs pour battre la latence adverse
    private val attackLatchMs = 220L

    // Pré-fire si approche rapide (avant la vraie portée)
    private val prefireFastApproachDist = 4.6f
    private val prefireLatchMs = 160L

    private val sTapSprintCutRange = 50..100  // coupure de sprint pour le S-tap

    private val reducingEnabled = true
    private val reducingTriggerDist = 2.6f
    private val reducingWindowRange = 420..700
    private val reducingToggleRange = 65..110
    private val reducingHitExtend = 300

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

    private var tapping = false
    private var keepACUntil = 0L
    private var tap50 = false

    private var isHitselecting = false
    private var hitselectCooldownUntil = 0L
    private var stoppedSprintForBait = false

    private var needFirstGroundJump = false
    private var firstJumpTimer: Timer? = null
    private var jumpSuppressUntil = 0L
    private var canDistanceJump = true
    private var distanceJumpCooldownTimer: Timer? = null

    private var sprintLockUntil = 0L
    private var sTapTimer: Timer? = null

    private var reducingActiveUntil = 0L
    private var reducingToggleTimer: Timer? = null

    override fun onGameStart() {
        Mouse.startTracking()
        Mouse.stopLeftAC()
        Movement.clearAll()
        Movement.startSprinting()
        Movement.startForward()
        Movement.stopJumping()

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

        needFirstGroundJump = true
        firstJumpTimer?.cancel()
        firstJumpTimer = null
        jumpSuppressUntil = 0L
        canDistanceJump = true
        distanceJumpCooldownTimer?.cancel()
        distanceJumpCooldownTimer = null

        sprintLockUntil = 0L
        sTapTimer?.cancel()
        sTapTimer = null

        reducingActiveUntil = 0L
        reducingToggleTimer?.cancel()
        reducingToggleTimer = null
    }

    override fun onGameEnd() {
        firstJumpTimer?.cancel()
        firstJumpTimer = null
        sTapTimer?.cancel()
        sTapTimer = null
        distanceJumpCooldownTimer?.cancel()
        distanceJumpCooldownTimer = null
        stopReducing(true)

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
        val now = System.currentTimeMillis()
        // W-Tap alterné (50ms / 100ms), très efficace en sumo
        val dur = if (tap50) 50 else 100
        tap50 = !tap50

        Combat.wTap(dur)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, dur + 15)

        val player = mc.thePlayer
        if (player != null && !edgeAhead(immediateEdgeProbe)) {
            val sprintCut = RandomUtils.randomIntInRange(sTapSprintCutRange.first, sTapSprintCutRange.last)
            Movement.stopSprinting()
            sprintLockUntil = now + sprintCut + 20
            sTapTimer?.cancel()
            sTapTimer = TimeUtils.setTimeout({
                sTapTimer = null
                sprintLockUntil = 0L
                if (!isReducingActive() && (!isHitselecting || !stoppedSprintForBait)) {
                    Movement.startSprinting()
                }
            }, sprintCut)
        }
    }

    override fun onAttacked() {
        if (!reducingEnabled) return
        val player = mc.thePlayer ?: return
        if (edgeAhead(edgeProbeNear) || edgeAhead(edgeProbeFar)) return
        activateReducing(System.currentTimeMillis(), reducingHitExtend)
    }

    private fun edgeAhead(dist: Float): Boolean {
        val p = mc.thePlayer ?: return false
        // s'il n'y a PAS de bloc devant nous à la hauteur du pied -> vide
        return WorldUtils.blockInFront(p, dist, 0.0f) == Blocks.air
    }

    private fun preferLeftTowardCenter(player: EntityPlayer): Boolean {
        // vrai = le point (centre) est à gauche du yaw actuel -> strafe gauche rapproche du centre
        return WorldUtils.leftOrRightToPoint(player, Vec3(arenaCenterX, player.posY, arenaCenterZ))
    }

    private fun handleFirstGroundJump(player: EntityPlayer, safeForward: Boolean) {
        if (!needFirstGroundJump || !player.onGround || !safeForward) {
            return
        }

        needFirstGroundJump = false
        val delay = RandomUtils.randomIntInRange(firstJumpDelayRange.first, firstJumpDelayRange.last)
        val hold = RandomUtils.randomIntInRange(firstJumpHoldRange.first, firstJumpHoldRange.last)
        firstJumpTimer?.cancel()
        firstJumpTimer = TimeUtils.setTimeout({
            firstJumpTimer = null
            val currentPlayer = mc.thePlayer
            if (currentPlayer != null && currentPlayer.onGround && !edgeAhead(edgeProbeNear) && !edgeAhead(edgeProbeFar)) {
                performJump(hold)
            }
        }, delay)
    }

    private fun performJump(hold: Int) {
        Movement.singleJump(hold)
        jumpSuppressUntil = System.currentTimeMillis() + hold + jumpStopBufferMs
    }

    private fun isReducingActive(now: Long = System.currentTimeMillis()): Boolean {
        return reducingActiveUntil > now
    }

    private fun activateReducing(now: Long, extra: Int = 0) {
        if (!reducingEnabled) return
        val extension = RandomUtils.randomIntInRange(reducingWindowRange.first, reducingWindowRange.last) + extra
        val targetUntil = now + extension.toLong()
        if (targetUntil > reducingActiveUntil) {
            reducingActiveUntil = targetUntil
        }
        if (reducingToggleTimer == null) {
            scheduleNextReducingToggle()
        }
    }

    private fun scheduleNextReducingToggle() {
        if (!reducingEnabled) return
        val now = System.currentTimeMillis()
        if (reducingActiveUntil <= now) {
            stopReducing()
            return
        }
        val delay = RandomUtils.randomIntInRange(reducingToggleRange.first, reducingToggleRange.last)
        reducingToggleTimer = TimeUtils.setTimeout({
            reducingToggleTimer = null
            val current = System.currentTimeMillis()
            if (reducingActiveUntil <= current) {
                stopReducing()
            } else {
                if (Movement.sprinting()) {
                    Movement.stopSprinting()
                } else if (!isHitselecting || !stoppedSprintForBait) {
                    Movement.startSprinting()
                }
                scheduleNextReducingToggle()
            }
        }, delay)
    }

    private fun stopReducing(force: Boolean = false) {
        reducingActiveUntil = 0L
        reducingToggleTimer?.cancel()
        reducingToggleTimer = null
        if (force) {
            return
        }
        val now = System.currentTimeMillis()
        if (!Movement.sprinting() && now >= sprintLockUntil && (!isHitselecting || !stoppedSprintForBait)) {
            Movement.startSprinting()
        }
    }

    override fun onTick() {
        val player = mc.thePlayer ?: return
        val opp = opponent() ?: return

        Mouse.startTracking()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(player, opp)
        val distToCenter = hypot(player.posX - arenaCenterX, player.posZ - arenaCenterZ).toFloat()
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.10f)

        val centerVecX = arenaCenterX - player.posX
        val centerVecZ = arenaCenterZ - player.posZ
        val forwardAlignment = if (distToCenter > 0f) {
            ((player.lookVec.xCoord * centerVecX) + (player.lookVec.zCoord * centerVecZ)) / distToCenter
        } else {
            1.0
        }

        val voidNear = edgeAhead(edgeProbeNear)
        val voidFar = edgeAhead(edgeProbeFar)
        val voidImmediate = edgeAhead(immediateEdgeProbe)
        val safeForward = !(voidNear || voidFar)
        val preferCenterLeft = preferLeftTowardCenter(player)

        handleFirstGroundJump(player, safeForward)

        if (!player.isSprinting
            && now >= sprintLockUntil
            && !isReducingActive(now)
            && (!isHitselecting || !stoppedSprintForBait)
        ) {
            Movement.startSprinting()
        }

        val inAttackLatch = (!Mouse.isUsingPotion() && !Mouse.isUsingProjectile()
                && !isHitselecting && distance <= attackStartDist)

        val inPrefire = (!Mouse.isUsingPotion() && !Mouse.isUsingProjectile()
                && !isHitselecting && approaching
                && distance <= prefireFastApproachDist && distance > attackStartDist)

        if (kira.config?.kiraHit == true && (inAttackLatch || inPrefire)) {
            val latch = if (inPrefire) prefireLatchMs else attackLatchMs
            keepACUntil = now + latch
            Mouse.startLeftAC()
        } else if ((now >= keepACUntil && !isHitselecting) || kira.config?.kiraHit != true) {
            Mouse.stopLeftAC()
        }

        if (!isHitselecting &&
            safeForward &&
            player.onGround &&
            distance in engageJumpMin..engageJumpMax &&
            canDistanceJump
        ) {
            val hold = RandomUtils.randomIntInRange(engageJumpHoldRange.first, engageJumpHoldRange.last)
            performJump(hold)
            canDistanceJump = false
            distanceJumpCooldownTimer?.cancel()
            distanceJumpCooldownTimer = TimeUtils.setTimeout({
                canDistanceJump = true
                distanceJumpCooldownTimer = null
            }, RandomUtils.randomIntInRange(djumpCdMin, djumpCdMax))
        }

        if (enableHitselecting && !isHitselecting && !tapping && player.onGround &&
            now >= hitselectCooldownUntil &&
            distance in hitselectMinDist..hitselectMaxDist &&
            RandomUtils.randomDoubleInRange(0.0, 1.0) < hitselectChance &&
            safeForward && !edgeAhead(2.0f)
        ) {
            isHitselecting = true
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            Movement.clearLeftRight()

            if (stopSprintDuringBait && player.isSprinting) {
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

                val currentPlayer = mc.thePlayer
                if (stoppedSprintForBait && currentPlayer != null && !currentPlayer.isSprinting) {
                    Movement.startSprinting()
                }
                stoppedSprintForBait = false
            }, baitDur)
        }

        if (isHitselecting && distance <= attackStartDist) {
            isHitselecting = false
            hitselectCooldownUntil = now + RandomUtils.randomIntInRange(hitselectCooldown.first, hitselectCooldown.last)
            if (stoppedSprintForBait && !player.isSprinting) {
                Movement.startSprinting()
            }
            stoppedSprintForBait = false
            if (kira.config?.kiraHit == true) {
                keepACUntil = now + attackLatchMs
                Mouse.startLeftAC()
            }
        }

        val movePriority = arrayListOf(0, 0)
        val clear = false
        var randomStrafe = false

        val centerWeight = if (distToCenter > centerSoftRadius) centerBiasWeightFar else centerBiasWeightNear
        if (distToCenter > 0.2f) {
            if (preferCenterLeft) movePriority[0] += centerWeight else movePriority[1] += centerWeight
        }

        if (!safeForward) {
            if (preferCenterLeft) movePriority[0] += edgeStrafeWeight else movePriority[1] += edgeStrafeWeight
            randomStrafe = false
        } else {
            val rotations = EntityUtils.getRotations(opp, player, false)
            if (rotations != null && now - lastStrafeSwitch > 320) {
                val preferSide = if (rotations[0] < 0) +1 else -1
                if (preferSide != strafeDir) {
                    strafeDir = preferSide
                    lastStrafeSwitch = now
                }
            }

            val flipDelay = RandomUtils.randomIntInRange(strafeFlipWindow.first, strafeFlipWindow.last)
            if (now - lastStrafeSwitch > flipDelay) {
                strafeDir = -strafeDir
                lastStrafeSwitch = now
            }

            val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
            if (distance in stagnationDistanceMin..stagnationDistanceMax) {
                if (deltaDist < stagnationDeltaThreshold) {
                    if (stagnantSince == 0L) {
                        stagnantSince = now
                    } else if (now - stagnantSince > stagnationTimeMs && now - lastStrafeSwitch > 280) {
                        strafeDir = -strafeDir
                        lastStrafeSwitch = now
                        stagnantSince = 0L
                    }
                } else {
                    stagnantSince = 0L
                }
            } else {
                stagnantSince = 0L
            }

            val strafeWeight = if (distance < 3.2f) strafeCloseWeight else strafeFarWeight
            if (strafeDir < 0) movePriority[0] += strafeWeight else movePriority[1] += strafeWeight

            randomStrafe = (distance >= 3.2f && distance <= 7.5f && !isHitselecting && safeForward)
        }

        if (reducingEnabled) {
            if (safeForward && distance <= reducingTriggerDist && !isReducingActive(now)) {
                activateReducing(now)
            }
            if (isReducingActive(now)) {
                if (reducingToggleTimer == null) {
                    scheduleNextReducingToggle()
                }
            } else {
                stopReducing()
            }
        }

        val shouldAdvanceTowardCenter = distToCenter <= centerSoftRadius || forwardAlignment >= -0.1
        if (distance < stopForwardDist || !safeForward || voidImmediate || !shouldAdvanceTowardCenter) {
            Movement.stopForward()
        } else if (!tapping && distance > reForwardDist && safeForward && !isHitselecting && shouldAdvanceTowardCenter) {
            Movement.startForward()
        }

        if (isHitselecting) {
            Mouse.stopLeftAC()
        }

        handle(clear, randomStrafe, movePriority)

        if (now >= jumpSuppressUntil) {
            Movement.stopJumping()
        }

        prevDistance = distance
    }
}
