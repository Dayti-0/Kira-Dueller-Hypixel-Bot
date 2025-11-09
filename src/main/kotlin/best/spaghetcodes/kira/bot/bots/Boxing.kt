package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
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
    private var fishTimer: TimeUtils.TaskHandle? = null

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
    private val JUMP_COOLDOWN_MS = 900L
    private val NO_JUMP_CLOSE_DIST = 3.6f

    // Warm-up (début seulement, jusqu'à <= 7 blocs)
    private val WARMUP_DISTANCE_STOP = 7.0f
    private val WARMUP_JUMP_EVERY_MIN = 240
    private val WARMUP_JUMP_EVERY_MAX = 380
    private val WARMUP_PRESS_MIN = 130
    private val WARMUP_PRESS_MAX = 190

    // Combo-lock agressif
    private val COMBO_LOCK_MIN = 560L
    private val COMBO_LOCK_MAX = 760L
    private val MICRO_JITTER_MIN = 120L
    private val MICRO_JITTER_MAX = 180L

    // Avance/stop (hystérésis fallback)
    private val STOP_FORWARD_CLOSE_DIST_COMBO = 1.00f
    private val RESUME_FORWARD_DIST_COMBO = 1.45f
    private val STOP_FORWARD_CLOSE_DIST_DEFAULT = 1.10f
    private val RESUME_FORWARD_DIST_DEFAULT = 1.60f

    // KB recovery
    private val KB_RECOVERY_MIN = 520L
    private val KB_RECOVERY_MAX = 760L
    private val HEAVY_KB_DELTA = 0.45f

    // Sweet-zones
    private val TARGET_DIST_COMBO_MIN = 1.08f
    private val TARGET_DIST_COMBO_MAX = 1.50f
    private val TARGET_DIST_NEUTRAL_MIN = 1.75f
    private val TARGET_DIST_NEUTRAL_MAX = 2.35f

    // Strafe timing (agressif)
    private val BURST_FLIP_MIN = 55
    private val BURST_FLIP_MAX = 95
    private val BURST_WINDOW_MIN = 240L
    private val BURST_WINDOW_MAX = 380L
    private val HOLD_WINDOW_MIN = 220L
    private val HOLD_WINDOW_MAX = 340L
    private val LONG_STRAFE_MIN = 900L
    private val LONG_STRAFE_MAX = 1600L
    private val LONG_STRAFE_DISTANCE_CAP = 3.4f
    private val LONG_STRAFE_BASE_CHANCE = 30 // %
    private val ANTI_STALL_EPS = 0.015f
    private val ANTI_STALL_DELAY = 260L

    // Aim-spike
    private val AIM_SPIKE_DEG = 14f
    private val AIM_SPIKE_COOLDOWN = 180L

    // Mur/centre d'arène
    private val WALL_NEAR_MARGIN = 0.9f
    private val WALL_ESCAPE_TIME_MS_MIN = 600L
    private val WALL_ESCAPE_TIME_MS_MAX = 900L

    // Anti-trade (soft) — moins fort en agressif
    private val ENEMY_IFRAME_SOFT = 3

    // =================== Lifecycle ===================

    override fun onGameStart() {
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
        forwardStickUntil = now + latAdj(RandomUtils.randomIntInRange(260, 340))
        meleeFocusUntil = now + latAdj(RandomUtils.randomIntInRange(420, 520))
        comboLockUntil = max(comboLockUntil, now + latAdj(RandomUtils.randomIntInRange(COMBO_LOCK_MIN.toInt(), COMBO_LOCK_MAX.toInt())))
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
            kbRecoveryUntil = now + latAdj(RandomUtils.randomIntInRange(KB_RECOVERY_MIN.toInt(), KB_RECOVERY_MAX.toInt()))
            lastHurtStamp = now
        }

        val distance = EntityUtils.getDistanceNoY(p, opp)
        val deltaDist = if (prevDistance > 0f) distance - prevDistance else 0f
        if (deltaDist > HEAVY_KB_DELTA) {
            kbRecoveryUntil = max(kbRecoveryUntil, now + latAdj(RandomUtils.randomIntInRange(650, 900)))
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
            if (distance > WARMUP_DISTANCE_STOP) {
                val cadence = latAdj(RandomUtils.randomIntInRange(WARMUP_JUMP_EVERY_MIN, WARMUP_JUMP_EVERY_MAX))
                if (now - lastWarmupJumpAt >= cadence) {
                    Movement.singleJump(RandomUtils.randomIntInRange(WARMUP_PRESS_MIN, WARMUP_PRESS_MAX))
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
        val aimSpike = aimDelta >= AIM_SPIKE_DEG && (now - lastAimSpikeAt) > AIM_SPIKE_COOLDOWN
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
            if (distance <= STOP_FORWARD_CLOSE_DIST_COMBO) {
                Movement.stopForward()
            } else if (thrust > 0.10f && !tapping) {
                Movement.startForward()
            } else if (thrust < -0.08f) {
                Movement.stopForward()
            }
        } else {
            val (stopDist, resumeDist) = STOP_FORWARD_CLOSE_DIST_DEFAULT to RESUME_FORWARD_DIST_DEFAULT
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
            r > (arenaRadiusEst - WALL_NEAR_MARGIN)
        } ?: false
        if (nearWall && wallEscapeUntil < now) {
            wallEscapeUntil = now + RandomUtils.randomIntInRange(WALL_ESCAPE_TIME_MS_MIN.toInt(), WALL_ESCAPE_TIME_MS_MAX.toInt())
        }
        val escapingWall = now < wallEscapeUntil

        // --- Strafe state machine + adapt ---
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        // Long strafe opportuniste (neutre, très courte distance)
        val oppFlipFast = oppFlipIntervalEma < 260f // flips rapides -> on préfère BURST vs long
        if (!inCombo && !kbRecovering && distance <= LONG_STRAFE_DISTANCE_CAP && longStrafeUntil < now && !oppFlipFast) {
            val extra = if (aimDeltaEma < 8f) 8 else 0 // s'ils visent "smooth", on tente plus souvent
            if (RandomUtils.randomIntInRange(1, 100) <= (LONG_STRAFE_BASE_CHANCE + extra)) {
                longStrafeUntil = now + latAdj(RandomUtils.randomIntInRange(LONG_STRAFE_MIN.toInt(), LONG_STRAFE_MAX.toInt()))
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
                    latAdj(RandomUtils.randomIntInRange(HOLD_WINDOW_MIN.toInt(), HOLD_WINDOW_MAX.toInt()))
                else
                    latAdj(RandomUtils.randomIntInRange(BURST_WINDOW_MIN.toInt(), BURST_WINDOW_MAX.toInt()))
                )
                if (neutralMode == Mode.BURST) {
                    burstToggleAt = now + latAdj(RandomUtils.randomIntInRange(BURST_FLIP_MIN, BURST_FLIP_MAX))
                }
            } else if (neutralMode == Mode.BURST && now >= burstToggleAt) {
                strafeDir = -strafeDir
                lastStrafeFlip = now
                burstToggleAt = now + latAdj(RandomUtils.randomIntInRange(BURST_FLIP_MIN, BURST_FLIP_MAX))
            }
        }

        // Anti-stagnation (distance quasi constante)
        if (!inCombo) {
            if (antiStallDistRef < 0f) {
                antiStallDistRef = distance; antiStallStamp = now
            } else {
                val d = abs(distance - antiStallDistRef)
                if (d < ANTI_STALL_EPS) {
                    if (now - antiStallStamp >= latAdj(ANTI_STALL_DELAY.toInt())) {
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
                    if (now - lastStrafeFlip >= latAdj(RandomUtils.randomIntInRange(MICRO_JITTER_MIN.toInt(), MICRO_JITTER_MAX.toInt()))) {
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
        if (opp.hurtTime > ENEMY_IFRAME_SOFT && !inCombo) {
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
            if (distance < TARGET_DIST_COMBO_MIN) Movement.stopForward()
            else if (distance > TARGET_DIST_COMBO_MAX && !tapping) Movement.startForward()
        } else if (!kbRecovering) {
            if (distance < TARGET_DIST_NEUTRAL_MIN) Movement.stopForward()
            else if (distance > TARGET_DIST_NEUTRAL_MAX && !tapping) Movement.startForward()
        }

        prevDistance = distance
    }

    // =================== Helpers ===================

    private fun comboLockActive(): Boolean {
        val now = System.currentTimeMillis()
        return (combo >= 2) || (now < comboLockUntil) || (now < meleeFocusUntil)
    }

    private fun canJump(
        now: Long,
        distance: Float,
        comboLockActive: Boolean,
        kbRecovering: Boolean,
        onGround: Boolean
    ): Boolean {
        if (!onGround) return false
        if (now - lastJumpAt < JUMP_COOLDOWN_MS) return false
        if (comboLockActive) return false
        if (kbRecovering) return false
        if (distance <= NO_JUMP_CLOSE_DIST) return false
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
