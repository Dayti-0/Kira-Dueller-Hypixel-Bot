package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.tuning.BoxingTuner
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.*
import best.spaghetcodes.kira.kira
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Boxing : BotBase("/play duels_boxing_duel"), MovePriority {

    override fun getName(): String = "Boxing"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.boxing_duel_wins",
                "losses" to "player.stats.Duels.boxing_duel_losses",
                "ws" to "player.stats.Duels.current_boxing_winstreak",
            )
        )
    }

    // ======== Aggressive Adaptive Boxing ========

    // --- Etat général
    private var tapping = false
    private var fishTimer: Timer? = null

    // --- Strafe machine
    private var strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
    private var lastStrafeFlip = 0L
    private var prevDistance = -1f
    private var neutralMode = Mode.HOLD
    private var modeUntil = 0L
    private var burstToggleAt = 0L
    private var longStrafeUntil = 0L
    private var antiStallStamp = 0L
    private var antiStallDistRef = -1f
    private enum class Mode { HOLD, BURST }

    // --- Combo/Stick/Jump/KB
    private var comboLockUntil = 0L
    private var forwardStickUntil = 0L
    private var meleeFocusUntil = 0L
    private var lastJumpAt = 0L
    private var kbRecoveryUntil = 0L
    private var lastHurtStamp = 0L

    // --- Warm-up (début de partie: sauts continus tant que > 7 blocs)
    private var warmupActive = true
    private var lastWarmupJumpAt = 0L

    // --- Profilage adverse & arène
    private var oppPrevYaw = 0f
    private var lastAimSpikeAt = 0L
    private var oppLastMovingLeft: Boolean? = null
    private var lastOppFlipAt = 0L
    private var oppFlipIntervalEma = 300f
    private var oppLeftCount = 0
    private var oppRightCount = 0
    private var aimDeltaEma = 0f

    private var arenaCenter: Vec3? = null
    private var arenaRadiusEst = 7.0f
    private var wallEscapeUntil = 0L

    // --- Ping-aware
    private var pingAvg = 80 // ms approx si non dispo

    // --- PID de distance
    private val distPid = DistPID(kp = 1.05f, ki = 0.06f, kd = 0.42f) // agressif mais stable

    // =================== Réglages (profil agressif) ===================

    // Jumps
    private var jumpCooldownMs = 900L
    private var noJumpCloseDist = 3.6f

    // Warm-up (début seulement, jusqu'à <= 7 blocs)
    private var warmupDistanceStop = 7.0f
    private var warmupJumpEveryMin = 240
    private var warmupJumpEveryMax = 380
    private var warmupPressMin = 130
    private var warmupPressMax = 190

    // Combo-lock agressif
    private var comboLockMin = 560
    private var comboLockMax = 760
    private var forwardStickMinMs = 260
    private var forwardStickMaxMs = 340
    private var meleeFocusMinMs = 420
    private var meleeFocusMaxMs = 520
    private var microJitterMin = 120
    private var microJitterMax = 180

    // Avance/stop (hystérésis fallback)
    private var stopForwardCloseDistCombo = 1.00f
    private var resumeForwardDistCombo = 1.45f
    private var stopForwardCloseDistDefault = 1.10f
    private var resumeForwardDistDefault = 1.60f

    // KB recovery
    private var kbRecoveryMin = 520
    private var kbRecoveryMax = 760
    private var heavyKbRecoveryMin = 650
    private var heavyKbRecoveryMax = 900
    private var heavyKbDelta = 0.45f

    // Sweet-zones
    private var targetDistComboMin = 1.08f
    private var targetDistComboMax = 1.50f
    private var targetDistNeutralMin = 1.75f
    private var targetDistNeutralMax = 2.35f

    // Strafe timing (agressif)
    private var burstFlipMin = 55
    private var burstFlipMax = 95
    private var burstWindowMin = 240
    private var burstWindowMax = 380
    private var holdWindowMin = 220
    private var holdWindowMax = 340
    private var longStrafeMin = 900
    private var longStrafeMax = 1600
    private var longStrafeDistanceCap = 3.4f
    private var longStrafeBaseChance = 30 // %
    private var antiStallEps = 0.015f
    private var antiStallDelay = 260

    // Aim-spike
    private var aimSpikeDeg = 14f
    private var aimSpikeCooldown = 180L

    // Mur/centre d'arène
    private var wallNearMargin = 0.9f
    private var wallEscapeTimeMsMin = 600
    private var wallEscapeTimeMsMax = 900

    // Anti-trade (soft) — moins fort en agressif
    private var enemyIframeSoft = 3

    // =================== Lifecycle ===================

    override fun onGameStart() {
        val params = try { BoxingTuner.pickParams() } catch (_: Throwable) { BoxingTuner.defaults() }
        applyParams(params)

        Movement.startSprinting()
        Movement.startForward()
        if (kira.config?.boxingFish == true) {
            TimeUtils.setTimeout(this::fishFunc, RandomUtils.randomIntInRange(10000, 20000))
        }
        Mouse.startTracking()
        if (kira.config?.kiraHit == true) Mouse.startLeftAC() else Mouse.stopLeftAC()

        val p = mc.thePlayer
        val o = opponent()
        if (p != null && o != null) {
            arenaCenter = Vec3((p.posX + o.posX) / 2.0, p.posY, (p.posZ + o.posZ) / 2.0)
            val spawnDist = EntityUtils.getDistanceNoY(p, o)
            arenaRadiusEst = max(6.0f, spawnDist / 2.2f + 1.5f)
        }

        // Init strafe
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        lastStrafeFlip = 0L
        neutralMode = Mode.HOLD
        modeUntil = 0L
        burstToggleAt = 0L
        longStrafeUntil = 0L
        antiStallStamp = 0L
        antiStallDistRef = -1f

        // Init combats
        comboLockUntil = 0L
        forwardStickUntil = 0L
        meleeFocusUntil = 0L
        lastJumpAt = 0L
        kbRecoveryUntil = 0L
        lastHurtStamp = 0L

        // Warm-up
        warmupActive = true
        lastWarmupJumpAt = 0L

        // Profilage/adapt
        oppPrevYaw = 0f
        lastAimSpikeAt = 0L
        oppLastMovingLeft = null
        lastOppFlipAt = 0L
        oppFlipIntervalEma = 300f
        oppLeftCount = 0
        oppRightCount = 0
        aimDeltaEma = 0f

        wallEscapeUntil = 0L
        prevDistance = -1f

        // Ping sampling (si dispo)
        pingAvg = getPingSafely() ?: 80
    }

    private fun fishFunc(fish: Boolean = true) {
        if (StateManager.state == StateManager.States.PLAYING) {
            if (fish) Inventory.setInvItem("fish") else Inventory.setInvItem("sword")
            fishTimer = TimeUtils.setTimeout({ fishFunc(!fish) }, RandomUtils.randomIntInRange(10000, 20000))
        }
    }

    override fun onGameEnd() {
        val win = when {
            Session.wins > Session.losses -> true
            Session.losses > Session.wins -> false
            else -> false
        }
        BoxingTuner.report(win, 0)
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            fishTimer?.cancel()
            Mouse.stopTracking()
        }, RandomUtils.randomIntInRange(100, 300))
    }

    override fun onAttack() {
        // W-tap + stick avant fort + focus melee
        tapping = true
        Combat.wTap(100)
        TimeUtils.setTimeout({ tapping = false }, 100)

        val now = System.currentTimeMillis()
        val forwardStick = RandomUtils.randomIntInRange(forwardStickMinMs, forwardStickMaxMs)
        val meleeFocus = RandomUtils.randomIntInRange(meleeFocusMinMs, meleeFocusMaxMs)
        val comboLock = RandomUtils.randomIntInRange(comboLockMin, comboLockMax)
        forwardStickUntil = now + latAdj(forwardStick)
        meleeFocusUntil = now + latAdj(meleeFocus)
        comboLockUntil = max(comboLockUntil, now + latAdj(comboLock))
        if (combo >= 2) Movement.clearLeftRight() // “colle” l’ennemi
        // Fin du warm-up dès le premier hit (même si distance > 7)
        warmupActive = false
    }

    // =================== Tick ===================

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return
        val now = System.currentTimeMillis()

        // KB reçu -> récupération
        if (p.hurtTime > 0 && now - lastHurtStamp > 120) {
            kbRecoveryUntil = now + latAdj(RandomUtils.randomIntInRange(kbRecoveryMin, kbRecoveryMax))
            lastHurtStamp = now
        }

        val distance = EntityUtils.getDistanceNoY(p, opp)
        val deltaDist = if (prevDistance > 0f) distance - prevDistance else 0f
        if (deltaDist > heavyKbDelta) {
            val heavyRecovery = RandomUtils.randomIntInRange(heavyKbRecoveryMin, heavyKbRecoveryMax)
            kbRecoveryUntil = max(kbRecoveryUntil, now + latAdj(heavyRecovery))
        }
        val kbRecovering = now < kbRecoveryUntil

        // Anti-obstacle (saut autorisé même en recovery pour ne pas rester bloqué)
        if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
            if (canJump(now, distance, comboLockActive = false, kbRecovering = false, onGround = p.onGround)) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 250))
                lastJumpAt = now
            }
        }

        // Warm-up sauts continus (début seulement) jusqu’à <= 7 blocs
        if (warmupActive && !kbRecovering && p.onGround) {
            if (distance > warmupDistanceStop) {
                val cadence = latAdj(RandomUtils.randomIntInRange(warmupJumpEveryMin, warmupJumpEveryMax))
                if (now - lastWarmupJumpAt >= cadence) {
                    Movement.singleJump(RandomUtils.randomIntInRange(warmupPressMin, warmupPressMax))
                    lastWarmupJumpAt = now
                    lastJumpAt = now
                }
            } else {
                warmupActive = false
            }
        }

        // Tracking & AC
        Mouse.startTracking()
        if (kira.config?.kiraHit == true) Mouse.startLeftAC() else Mouse.stopLeftAC()

        // --- Profilage adversaire ---
        // Aim delta & spike
        val aimDelta = abs(opp.rotationYaw - oppPrevYaw)
        aimDeltaEma = 0.15f * aimDelta + 0.85f * aimDeltaEma
        val aimSpike = aimDelta >= aimSpikeDeg && (now - lastAimSpikeAt) > aimSpikeCooldown
        if (aimSpike) {
            strafeDir = -strafeDir
            lastAimSpikeAt = now
            lastStrafeFlip = now
        }
        oppPrevYaw = opp.rotationYaw

        // Biais gauche/droite + cadence de flips adverse
        val movingLeft = EntityUtils.entityMovingLeft(p, opp)
        if (oppLastMovingLeft != null && movingLeft != oppLastMovingLeft) {
            // flip détecté
            if (lastOppFlipAt != 0L) {
                val dt = (now - lastOppFlipAt).toFloat()
                oppFlipIntervalEma = 0.25f * dt + 0.75f * oppFlipIntervalEma
            }
            lastOppFlipAt = now
        }
        oppLastMovingLeft = movingLeft
        if (movingLeft) oppLeftCount++ else oppRightCount++

        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)
        val inCombo = comboLockActive()

        // --- Contrôle d'avance: PID agressif en combo, fallback hystérésis sinon ---
        if (inCombo) {
            val target = 1.28f
            val thrust = distPid.step(target, distance)
            // seuils anti-pompage + filet à courte distance
            if (distance <= stopForwardCloseDistCombo) {
                Movement.stopForward()
            } else if (thrust > 0.10f && !tapping) {
                Movement.startForward()
            } else if (thrust < -0.08f) {
                Movement.stopForward()
            }
        } else {
            val (stopDist, resumeDist) = stopForwardCloseDistDefault to resumeForwardDistDefault
            if (distance <= stopDist) {
                Movement.stopForward()
            } else if (distance >= resumeDist && !tapping) {
                Movement.startForward()
            } else if (!kbRecovering) {
                if (distance < 2.1f && combo >= 1 && approaching) Movement.stopForward()
            }
        }

        // --- Biais centre / anti-mur ---
        val center = arenaCenter
        val nearWall = center?.let {
            val dx = p.posX - it.xCoord
            val dz = p.posZ - it.zCoord
            val r = sqrt(dx*dx + dz*dz).toFloat()
            r > (arenaRadiusEst - wallNearMargin)
        } ?: false
        if (nearWall && wallEscapeUntil < now) {
            wallEscapeUntil = now + RandomUtils.randomIntInRange(wallEscapeTimeMsMin, wallEscapeTimeMsMax)
        }
        val escapingWall = now < wallEscapeUntil

        // --- Strafe state machine + adapt ---
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        // Long strafe opportuniste (neutre, très courte distance)
        val oppFlipFast = oppFlipIntervalEma < 260f // flips rapides -> on préfère BURST vs long
        if (!inCombo && !kbRecovering && distance <= longStrafeDistanceCap && longStrafeUntil < now && !oppFlipFast) {
            val extra = if (aimDeltaEma < 8f) 8 else 0 // s'ils visent "smooth", on tente plus souvent
            if (RandomUtils.randomIntInRange(1, 100) <= (longStrafeBaseChance + extra)) {
                longStrafeUntil = now + latAdj(RandomUtils.randomIntInRange(longStrafeMin, longStrafeMax))
                strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                lastStrafeFlip = now
            }
        }
        val inLongStrafe = now < longStrafeUntil && !inCombo && !kbRecovering

        // Choix/renouvellement du mode neutre HOLD/BURST
        if (!inCombo && !kbRecovering && !inLongStrafe) {
            if (now >= modeUntil) {
                // plus de BURST si l’adversaire flippe souvent
                val burstBias = if (oppFlipFast) 68 else 55
                neutralMode = if (RandomUtils.randomIntInRange(0, 99) < burstBias) Mode.BURST else Mode.HOLD
                modeUntil = now + (if (neutralMode == Mode.HOLD)
                    latAdj(RandomUtils.randomIntInRange(holdWindowMin, holdWindowMax))
                else
                    latAdj(RandomUtils.randomIntInRange(burstWindowMin, burstWindowMax))
                )
                if (neutralMode == Mode.BURST) {
                    burstToggleAt = now + latAdj(RandomUtils.randomIntInRange(burstFlipMin, burstFlipMax))
                }
            } else if (neutralMode == Mode.BURST && now >= burstToggleAt) {
                strafeDir = -strafeDir
                lastStrafeFlip = now
                burstToggleAt = now + latAdj(RandomUtils.randomIntInRange(burstFlipMin, burstFlipMax))
            }
        }

        // Anti-stagnation (distance quasi constante)
        if (!inCombo) {
            if (antiStallDistRef < 0f) {
                antiStallDistRef = distance; antiStallStamp = now
            } else {
                val d = abs(distance - antiStallDistRef)
                if (d < antiStallEps) {
                    if (now - antiStallStamp >= latAdj(antiStallDelay)) {
                        strafeDir = -strafeDir
                        lastStrafeFlip = now
                        antiStallStamp = now
                        antiStallDistRef = distance
                    }
                } else {
                    antiStallDistRef = distance; antiStallStamp = now
                }
            }
        } else {
            antiStallDistRef = -1f
        }

        // Poids de strafe (agressif)
        if (EntityUtils.entityFacingAway(p, opp)) {
            val target = center ?: Vec3(0.0, 0.0, 0.0)
            if (WorldUtils.leftOrRightToPoint(p, target)) movePriority[0] += 3 else movePriority[1] += 3
        } else {
            when {
                escapingWall -> {
                    val target = center ?: Vec3(0.0, 0.0, 0.0)
                    if (WorldUtils.leftOrRightToPoint(p, target)) movePriority[0] += 5 else movePriority[1] += 5
                }
                inCombo -> {
                    // Micro-jitter minimal (agressif = très collé)
                    if (now - lastStrafeFlip >= latAdj(RandomUtils.randomIntInRange(microJitterMin, microJitterMax))) {
                        strafeDir = -strafeDir
                        lastStrafeFlip = now
                    }
                    if (strafeDir < 0) movePriority[0] += 1 else movePriority[1] += 1
                }
                kbRecovering -> {
                    if (strafeDir < 0) movePriority[0] += 1 else movePriority[1] += 1
                }
                inLongStrafe -> {
                    if (strafeDir < 0) movePriority[0] += 7 else movePriority[1] += 7
                }
                else -> {
                    // Poids base par distance (plus fort en mid), biais léger contre leur biais
                    val base = when {
                        distance < 2.0f -> 5
                        distance < 3.4f -> 6
                        distance < 6.0f -> 7
                        else            -> 5
                    }
                    var wL = base; var wR = base
                    // anti-biais (si opp est majoritairement à gauche, on renforce notre droite, et inversement)
                    val total = (oppLeftCount + oppRightCount).coerceAtLeast(1)
                    val bias = (oppLeftCount - oppRightCount).toFloat() / total
                    if (bias > 0.15f) { // ils “tirent” plus à gauche
                        wR += 1
                    } else if (bias < -0.15f) {
                        wL += 1
                    }
                    if (strafeDir < 0) movePriority[0] += wL else movePriority[1] += wR
                    // RandomStrafe généreux en long range
                    if (distance in 7.5f..16.0f) randomStrafe = true
                }
            }
        }

        // Anti-trade doux hors combo (agressif = peu d’atténuation)
        if (opp.hurtTime > enemyIframeSoft && !inCombo) {
            if (movePriority[0] > 1) movePriority[0] -= 1
            if (movePriority[1] > 1) movePriority[1] -= 1
            // on pourrait couper randomStrafe, mais en agressif on le garde souvent
        }

        // Appliquer les mouvements
        handle(clear, randomStrafe, movePriority)

        // Sauts lointains (jamais en close/lock/recovery)
        if (!inCombo && !kbRecovering) {
            if (canJump(now, distance, inCombo, kbRecovering, p.onGround)) {
                if (distance > 8.0f && now - lastJumpAt >= 520) {
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                    lastJumpAt = now
                } else if (distance in 4.8f..8.0f && now - lastJumpAt >= 700) {
                    val facingAway = EntityUtils.entityFacingAway(p, opp)
                    val oppStill = (abs(opp.posX - opp.lastTickPosX) + abs(opp.posZ - opp.lastTickPosZ) < 0.06)
                    if (facingAway || oppStill) {
                        Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                        lastJumpAt = now
                    }
                }
            }
        }

        // Sweet-zones (douce incitation, en plus du PID/hystérésis)
        if (inCombo) {
            if (distance < targetDistComboMin) Movement.stopForward()
            else if (distance > targetDistComboMax && !tapping) Movement.startForward()
        } else if (!kbRecovering) {
            if (distance < targetDistNeutralMin) Movement.stopForward()
            else if (distance > targetDistNeutralMax && !tapping) Movement.startForward()
        }

        prevDistance = distance
    }

    // =================== Helpers ===================

    private fun comboLockActive(): Boolean {
        val now = System.currentTimeMillis()
        return (combo >= 2) || (now < comboLockUntil) || (now < meleeFocusUntil)
    }

    private fun applyParams(p: BoxingTuner.Params) {
        jumpCooldownMs = p.jumpCooldownMs
        noJumpCloseDist = p.noJumpCloseDist

        warmupDistanceStop = p.warmupDistanceStop
        warmupJumpEveryMin = min(p.warmupJumpEveryMin, p.warmupJumpEveryMax)
        warmupJumpEveryMax = max(p.warmupJumpEveryMin, p.warmupJumpEveryMax)
        warmupPressMin = min(p.warmupPressMin, p.warmupPressMax)
        warmupPressMax = max(p.warmupPressMin, p.warmupPressMax)

        comboLockMin = min(p.comboLockMin, p.comboLockMax)
        comboLockMax = max(p.comboLockMin, p.comboLockMax)
        forwardStickMinMs = min(p.forwardStickMinMs, p.forwardStickMaxMs)
        forwardStickMaxMs = max(p.forwardStickMinMs, p.forwardStickMaxMs)
        meleeFocusMinMs = min(p.meleeFocusMinMs, p.meleeFocusMaxMs)
        meleeFocusMaxMs = max(p.meleeFocusMinMs, p.meleeFocusMaxMs)
        microJitterMin = min(p.microJitterMin, p.microJitterMax)
        microJitterMax = max(p.microJitterMin, p.microJitterMax)

        stopForwardCloseDistCombo = p.stopForwardCloseDistCombo
        resumeForwardDistCombo = p.resumeForwardDistCombo
        stopForwardCloseDistDefault = p.stopForwardCloseDistDefault
        resumeForwardDistDefault = p.resumeForwardDistDefault

        kbRecoveryMin = min(p.kbRecoveryMin, p.kbRecoveryMax)
        kbRecoveryMax = max(p.kbRecoveryMin, p.kbRecoveryMax)
        heavyKbRecoveryMin = min(p.heavyKbRecoveryMin, p.heavyKbRecoveryMax)
        heavyKbRecoveryMax = max(p.heavyKbRecoveryMin, p.heavyKbRecoveryMax)
        heavyKbDelta = p.heavyKbDelta

        targetDistComboMin = min(p.targetDistComboMin, p.targetDistComboMax)
        targetDistComboMax = max(p.targetDistComboMin, p.targetDistComboMax)
        targetDistNeutralMin = min(p.targetDistNeutralMin, p.targetDistNeutralMax)
        targetDistNeutralMax = max(p.targetDistNeutralMin, p.targetDistNeutralMax)

        burstFlipMin = min(p.burstFlipMin, p.burstFlipMax)
        burstFlipMax = max(p.burstFlipMin, p.burstFlipMax)
        burstWindowMin = min(p.burstWindowMin, p.burstWindowMax)
        burstWindowMax = max(p.burstWindowMin, p.burstWindowMax)
        holdWindowMin = min(p.holdWindowMin, p.holdWindowMax)
        holdWindowMax = max(p.holdWindowMin, p.holdWindowMax)
        longStrafeMin = min(p.longStrafeMin, p.longStrafeMax)
        longStrafeMax = max(p.longStrafeMin, p.longStrafeMax)
        longStrafeDistanceCap = p.longStrafeDistanceCap
        longStrafeBaseChance = p.longStrafeBaseChance

        antiStallEps = p.antiStallEps
        antiStallDelay = p.antiStallDelay

        aimSpikeDeg = p.aimSpikeDeg
        aimSpikeCooldown = p.aimSpikeCooldown

        wallNearMargin = p.wallNearMargin
        wallEscapeTimeMsMin = min(p.wallEscapeTimeMsMin, p.wallEscapeTimeMsMax)
        wallEscapeTimeMsMax = max(p.wallEscapeTimeMsMin, p.wallEscapeTimeMsMax)

        enemyIframeSoft = p.enemyIframeSoft
    }

    private fun canJump(
        now: Long,
        distance: Float,
        comboLockActive: Boolean,
        kbRecovering: Boolean,
        onGround: Boolean
    ): Boolean {
        if (!onGround) return false
        if (now - lastJumpAt < jumpCooldownMs) return false
        if (comboLockActive) return false
        if (kbRecovering) return false
        if (distance <= noJumpCloseDist) return false
        return true
    }

    // Latency-aware adjustment: ajoute ~ping/90 aux timings pour coller au réseau
    private fun latAdj(ms: Int): Int {
        val adj = (pingAvg / 90f)
        return (ms * (1f + 0.08f * adj)).toInt()
    }

    private fun getPingSafely(): Int? {
        return try {
            val pl = mc.thePlayer ?: return null
            val nh = mc.netHandler ?: return null
            val info = nh.getPlayerInfo(pl.gameProfile.id) ?: return null
            val ping = info.responseTime
            if (ping > 0) ping else null
        } catch (_: Throwable) {
            null
        }
    }

    // PID très simple pour la distance
    private class DistPID(val kp: Float = 0.9f, val ki: Float = 0.05f, val kd: Float = 0.35f) {
        private var integ = 0f
        private var prevErr = 0f
        fun step(target: Float, d: Float): Float {
            val err = target - d
            integ = (integ + err).coerceIn(-2f, 2f)
            val deriv = err - prevErr
            prevErr = err
            return kp * err + ki * integ + kd * deriv
        }
    }
}
