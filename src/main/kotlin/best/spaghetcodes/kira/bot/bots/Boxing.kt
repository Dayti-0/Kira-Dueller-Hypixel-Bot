package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.ChatUtils
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.WorldUtils
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.util.MathHelper
import net.minecraft.util.Vec3
import java.util.Timer
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Boxing duel bot rebuilt around a finite state machine so every decision is explicit,
 * tunable and documented. The public API (class name + lifecycle methods) stays intact
 * for Kira integration, everything else is internal to this file.
 */
class Boxing : BotBase("/play duels_boxing_duel"), MovePriority {

    /**
     * ===== Configuration knobs =====
     * All numbers centralised here so we can quickly iterate from the debugger / hot reload.
     * Values are deliberately floats/ints (no allocations at runtime) and tuned for 1.8.9 reach.
     */
    private object Config {
        // Global aggression baseline applied before state-specific modifiers.
        const val AGGRO_BASE = 0.62f

        // Delay windows for movement idea refresh (ms).
        val CLOSE_DELAY_RANGE_MS = 40..70
        val MID_DELAY_RANGE_MS = 55..95
        val LONG_DELAY_RANGE_MS = 75..120

        // Chance to trigger a long strafe burst while stuck in close range.
        const val LONG_STRAFE_CHANCE = 0.32f

        // Minimal interval between micro jumps to keep them scarce and meaningful.
        const val JUMP_MIN_INTERVAL_MS = 550

        // Field-of-view clamp for the aim controller (degrees).
        const val FOV_MIN = 35f
        const val FOV_MAX = 120f

        // Rotation speed bounds (degrees / tick) for the humanoid controller.
        const val ROT_SPEED_MIN = 2.1f
        const val ROT_SPEED_MAX = 8.4f

        // Distance change threshold before we consider flipping strafe (anti stagnation).
        const val ANTI_STAGNATION_DELTA = 0.18f

        // If trades lost / total exceed this ratio we bail out of the exchange.
        const val TRADE_BAD_RATIO = 0.62f

        // Time we need to stay in recovery mode after a bad exchange.
        const val RECOVERY_TIME_MS = 900

        // Panic guard if the opponent sprints straight into us.
        const val PANIC_WINDOW_MS = 420

        // Cooldown between forced strafe flips.
        const val FLIP_COOLDOWN_MS = 900

        // Predictive aim look-ahead factor (ticks) for mid distance.
        const val PREDICT_TICKS_BASE = 2.4f
        const val MAX_LOOK_AHEAD_TICKS = 4.6f

        // Micro jitter amplitude for the rotation controller.
        const val MICRO_JITTER_DEG = 0.65f

        // Randomness applied to acceleration curves.
        const val HUMAN_VARIANCE = 0.22f

        // Prevent repeating the same movement idea more than twice.
        const val IDEA_COOLDOWN_MS = 1600

        // Behaviour thresholds (blocks).
        const val RESET_DISTANCE = 4.6f
        const val PANIC_DISTANCE = 2.0f
        const val EXTEND_DISTANCE = 3.1f
        const val RECOVERY_DISTANCE = 5.3f
        const val APPROACH_DISTANCE = 6.5f

        // Trade / perception windows.
        const val TRADE_WINDOW = 12
        const val DIST_HISTORY = 16
        const val STRAFE_HISTORY = 18

        // Smoothing factor for ping estimation (0..1, higher = quicker reaction).
        const val LATENCY_SMOOTHING = 0.18f

        // Danger level at which we hard brake.
        const val DANGER_THRESHOLD = 0.58f

        // Minimum sprint re-engage delay after a hit (ms).
        const val SPRINT_RESET_MS = 90
    }

    /** Telemetry toggle (kept lightweight, no IO). */
    private object TelemetryConfig {
        const val ENABLED = true
    }

    /** States describing every macro situation we care about. */
    private enum class BoxingState {
        OPENING,
        APPROACH,
        CLOSE_RANGE,
        EXTEND_COMBO,
        RESET_SPACE,
        PANIC_BREAK,
        RECOVERY
    }

    /** Aggregated perception of the opponent, refreshed each tick. */
    private data class OpponentPerception(
        var aggressionScore: Float = 0f,
        var kiteProbability: Float = 0f,
        var strafeBias: Float = 0f,
        var distanceTrend: Float = 0f,
        var latencyMs: Float = 90f,
        var inversionChance: Float = 0f,
        var dangerScore: Float = 0f
    )

    /** Telemetry counters (only touched when telemetry is enabled). */
    private data class Telemetry(
        var forcedFlips: Int = 0,
        var tradesWon: Int = 0,
        var tradesLost: Int = 0,
        val stateTimeMs: LongArray = LongArray(BoxingState.values().size),
        var impactDistanceAccum: Float = 0f,
        var impactSamples: Int = 0
    )

    /** Clicker state machine to humanise the auto clicker cadence. */
    private enum class ClickState { IDLE, BURST, PAUSE }

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

    private val perception = OpponentPerception()
    private val telemetry = Telemetry()
    private val rng = ThreadLocalRandom.current()
    private val movePriorityBuffer = arrayListOf(0, 0)

    private var state: BoxingState = BoxingState.OPENING
    private var lastStateChange = 0L
    private var stateLockedUntil = 0L

    private var lastTickAt = 0L
    private var lastDistance = 0f
    private var stagnationAnchor = 0f
    private var stagnationCheckAt = 0L

    private var strafeDirection = 1
    private var lastForcedFlipAt = 0L
    private var longStrafeActiveUntil = 0L
    private var longStrafeCooldownUntil = 0L

    private var ideaId = -1
    private var ideaRepeat = 0
    private var ideaCooldownUntil = 0L

    private var lastJumpAt = 0L
    private var lastSprintResetAt = 0L

    private var clickState: ClickState = ClickState.IDLE
    private var clickStateUntil = 0L

    private var lastOwnHitAt = 0L
    private var lastTakenHitAt = 0L
    private var panicArmedUntil = 0L
    private var recoveryUntil = 0L

    private var fishTimer: Timer? = null
    private var usingFish = false

    // Histories (ring buffers without allocations in tick loop).
    private val tradeHistory = IntArray(Config.TRADE_WINDOW)
    private var tradeIndex = 0
    private var tradeBalance = 0
    private var tradeSamples = 0

    private val distanceHistory = FloatArray(Config.DIST_HISTORY)
    private var distanceIndex = 0
    private var distanceSamples = 0

    private val strafeHistory = IntArray(Config.STRAFE_HISTORY)
    private var strafeIndex = 0
    private var strafeAccumulator = 0
    private var strafeSamples = 0

    private val kbSamples = FloatArray(8)
    private var kbIndex = 0
    private var kbFilled = 0

    override fun onGameStart() {
        resetInternalState()

        Movement.startSprinting()
        Movement.startForward()
        Mouse.startTracking()

        if (kira.config?.kiraHit == true) {
            Mouse.startLeftAC()
        } else {
            Mouse.stopLeftAC()
        }

        if (kira.config?.boxingFish == true) {
            usingFish = false
            scheduleFishToggle()
        }
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Combat.stopRandomStrafe()
            Mouse.stopLeftAC()
            Mouse.stopTracking()
            fishTimer?.cancel()
            clickState = ClickState.IDLE
        }, RandomUtils.randomIntInRange(100, 300))
    }

    override fun onAttack() {
        lastOwnHitAt = System.currentTimeMillis()
        registerTrade(1)

        // W-tap + sprint reset to maintain pressure.
        Combat.wTap(120)
        lastSprintResetAt = lastOwnHitAt

        // Keep telemetry of distance at impact.
        kira.mc.thePlayer?.let { player ->
            opponent()?.let { opp ->
                val dist = EntityUtils.getDistanceNoY(player, opp)
                if (TelemetryConfig.ENABLED) {
                    telemetry.impactDistanceAccum += dist
                    telemetry.impactSamples++
                    telemetry.tradesWon++
                }
                pushKbSample(dist)
            }
        }

        // On large combo lead we may lock strafe direction for a burst.
        if (combo >= 3 && rng.nextFloat() < 0.45f) {
            longStrafeActiveUntil = System.currentTimeMillis() + RandomUtils.randomIntInRange(320, 540)
        }
    }

    override fun onAttacked() {
        lastTakenHitAt = System.currentTimeMillis()
        registerTrade(-1)
        panicArmedUntil = lastTakenHitAt + Config.PANIC_WINDOW_MS
        recoveryUntil = lastTakenHitAt + Config.RECOVERY_TIME_MS
        if (TelemetryConfig.ENABLED) {
            telemetry.tradesLost++
        }
    }

    override fun onTick() {
        val player = kira.mc.thePlayer ?: return
        val opp = opponent() ?: return
        Mouse.startTracking() // ensure aim assistance stays active even if other modules changed it.

        val now = System.currentTimeMillis()
        val deltaMs = (now - lastTickAt).coerceAtLeast(1)
        lastTickAt = now

        val distance = EntityUtils.getDistanceNoY(player, opp)

        updateHistories(distance)
        updatePerception(now, player, opp, distance, deltaMs)
        updateClicker(now, distance)
        maintainSprint(now)

        // Micro jump to keep momentum if we are about to collide with a block or need a tempo reset.
        maybePerformContextualJump(now, player, distance)

        val nextState = decideNextState(now, distance)
        if (nextState != state) {
            enterState(nextState, now)
        }

        if (TelemetryConfig.ENABLED) {
            telemetry.stateTimeMs[state.ordinal] += deltaMs
        }

        applyStrafePlan(now, distance)

        applyStateBehaviours(now, player, opp, distance, deltaMs)

        lastDistance = distance
    }

    /** Maintains the fish slot swapper if enabled. */
    private fun scheduleFishToggle() {
        fishTimer?.cancel()
        fishTimer = TimeUtils.setTimeout(
            {
                if (StateManager.state == StateManager.States.PLAYING) {
                    usingFish = !usingFish
                    if (usingFish) Inventory.setInvItem("fish") else Inventory.setInvItem("sword")
                    scheduleFishToggle()
                }
            },
            RandomUtils.randomIntInRange(9_000, 18_000)
        )
    }

    /** Reset everything between games so state carry-over never happens. */
    private fun resetInternalState() {
        state = BoxingState.OPENING
        lastStateChange = System.currentTimeMillis()
        stateLockedUntil = 0L
        lastTickAt = lastStateChange
        lastDistance = 0f
        stagnationAnchor = 0f
        stagnationCheckAt = 0L
        strafeDirection = if (rng.nextBoolean()) 1 else -1
        lastForcedFlipAt = 0L
        longStrafeActiveUntil = 0L
        longStrafeCooldownUntil = 0L
        ideaId = -1
        ideaRepeat = 0
        ideaCooldownUntil = 0L
        lastJumpAt = 0L
        lastSprintResetAt = 0L
        clickState = ClickState.IDLE
        clickStateUntil = 0L
        lastOwnHitAt = 0L
        lastTakenHitAt = 0L
        panicArmedUntil = 0L
        recoveryUntil = 0L
        perception.apply {
            aggressionScore = 0f
            kiteProbability = 0f
            strafeBias = 0f
            distanceTrend = 0f
            latencyMs = 90f
            inversionChance = 0f
            dangerScore = 0f
        }
        tradeHistory.fill(0)
        tradeIndex = 0
        tradeBalance = 0
        tradeSamples = 0
        distanceHistory.fill(0f)
        distanceIndex = 0
        distanceSamples = 0
        strafeHistory.fill(0)
        strafeIndex = 0
        strafeAccumulator = 0
        strafeSamples = 0
        kbSamples.fill(0f)
        kbIndex = 0
        kbFilled = 0
        if (TelemetryConfig.ENABLED) {
            telemetry.forcedFlips = 0
            telemetry.tradesWon = 0
            telemetry.tradesLost = 0
            telemetry.stateTimeMs.fill(0)
            telemetry.impactDistanceAccum = 0f
            telemetry.impactSamples = 0
        }
    }

    /** Update distance / trade histories without allocating. */
    private fun updateHistories(distance: Float) {
        // Distance trend (used to infer kiting).
        val previous = distanceHistory[distanceIndex]
        distanceHistory[distanceIndex] = distance
        distanceIndex = (distanceIndex + 1) % distanceHistory.size
        if (distanceSamples < distanceHistory.size) distanceSamples++
        val trend = if (distanceSamples >= 2) {
            distance - previous
        } else 0f
        perception.distanceTrend = (perception.distanceTrend * 0.6f) + trend * 0.4f

        // Strafe preference.
        val strafeValue = when {
            opponent() == null || kira.mc.thePlayer == null -> 0
            EntityUtils.entityMovingLeft(kira.mc.thePlayer, opponent()!!) -> -1
            EntityUtils.entityMovingRight(kira.mc.thePlayer, opponent()!!) -> 1
            else -> 0
        }
        val removed = strafeHistory[strafeIndex]
        strafeHistory[strafeIndex] = strafeValue
        strafeIndex = (strafeIndex + 1) % strafeHistory.size
        if (strafeSamples < strafeHistory.size) {
            strafeSamples++
            strafeAccumulator += strafeValue
        } else {
            strafeAccumulator += strafeValue - removed
        }
        if (strafeSamples > 0) {
            perception.strafeBias = strafeAccumulator / strafeSamples.toFloat()
        }
    }

    /** Feed opponent perception with fresh information. */
    private fun updatePerception(now: Long, player: EntityPlayer, opp: EntityPlayer, distance: Float, deltaMs: Long) {
        val playerVelocity = player.positionVector.subtract(Vec3(player.prevPosX, player.prevPosY, player.prevPosZ))
        val opponentVelocity = opp.positionVector.subtract(Vec3(opp.prevPosX, opp.prevPosY, opp.prevPosZ))
        val relativeSpeed = sqrt(
            max(0.0, (
                (opponentVelocity.xCoord - playerVelocity.xCoord) * (opponentVelocity.xCoord - playerVelocity.xCoord) +
                    (opponentVelocity.zCoord - playerVelocity.zCoord) * (opponentVelocity.zCoord - playerVelocity.zCoord)
                ))
        ).toFloat()

        val aggressionFactor = when {
            combo - opponentCombo >= 3 -> 1.2f
            opponentCombo - combo >= 2 -> 0.55f
            else -> 1.0f
        }

        val velocityComponent = min(1f, relativeSpeed * 4f)
        val distanceComponent = 1f - min(1f, distance / Config.APPROACH_DISTANCE)
        val tradeComponent = if (tradeSamples == 0) 0.5f else (0.5f + tradeBalance / (tradeSamples * 2f))
        val aggressionScore = (velocityComponent * 0.5f + distanceComponent * 0.3f + tradeComponent * 0.2f) * aggressionFactor
        perception.aggressionScore = (Config.AGGRO_BASE + aggressionScore).coerceIn(0f, 1.4f)

        val kiteScore = when {
            perception.distanceTrend > 0.12f && relativeSpeed < 0.08f -> 0.65f
            perception.distanceTrend > 0.04f -> 0.45f
            else -> 0.18f
        }
        perception.kiteProbability = (perception.kiteProbability * 0.6f) + (kiteScore * 0.4f)

        val tradeRatio = if (tradeSamples == 0) 0.5f else (0.5f + tradeBalance / (tradeSamples * 2f))
        val facing = MathHelper.wrapAngleTo180_float(opp.rotationYaw - player.rotationYaw)
        val facingComponent = 1f - (abs(facing) / 180f)
        val danger = (perception.aggressionScore * 0.5f + velocityComponent * 0.25f + facingComponent * 0.25f)
        perception.dangerScore = danger.coerceIn(0f, 1.5f)

        perception.inversionChance = calculateInversionChance(distance, relativeSpeed)

        val pingSample = if (lastOwnHitAt > 0 && lastTakenHitAt > 0) {
            val gap = abs(lastOwnHitAt - lastTakenHitAt).coerceAtMost(700)
            max(40, gap)
        } else if (lastOwnHitAt > 0) {
            (now - lastOwnHitAt).coerceAtMost(250)
        } else {
            110
        }
        perception.latencyMs = (perception.latencyMs * (1 - Config.LATENCY_SMOOTHING) + pingSample * Config.LATENCY_SMOOTHING)
    }

    private fun calculateInversionChance(distance: Float, relativeSpeed: Float): Float {
        val kbVariance = if (kbFilled == 0) 0f else {
            var sum = 0f
            for (i in 0 until kbFilled) {
                sum += kbSamples[i]
            }
            val mean = sum / kbFilled
            var acc = 0f
            for (i in 0 until kbFilled) {
                val diff = kbSamples[i] - mean
                acc += diff * diff
            }
            sqrt((acc / kbFilled).toDouble()).toFloat()
        }
        val distanceFactor = if (distance < 2.6f) 0.55f else 0.3f
        val varianceFactor = min(0.65f, kbVariance * 0.35f)
        val speedFactor = min(0.45f, relativeSpeed * 0.6f)
        return (distanceFactor + varianceFactor + speedFactor).coerceIn(0f, 1f)
    }

    /** Strafe plan updated each tick based on the active state. */
    private fun applyStrafePlan(now: Long, distance: Float) {
        // Map state + perception outputs to MovePriority weights.
        val shouldClear = state == BoxingState.RECOVERY
        val shouldRandom = state == BoxingState.APPROACH && distance > 4.5f

        val inversionBoost = if (perception.inversionChance > 0.55f) 2 else 0
        var leftWeight: Int
        var rightWeight: Int
        when (state) {
            BoxingState.OPENING -> {
                leftWeight = 1
                rightWeight = 1
            }
            BoxingState.APPROACH -> {
                when {
                    perception.strafeBias > 0.2f -> {
                        leftWeight = 2
                        rightWeight = 4
                    }
                    perception.strafeBias < -0.2f -> {
                        leftWeight = 4
                        rightWeight = 2
                    }
                    else -> {
                        leftWeight = 3
                        rightWeight = 3
                    }
                }
            }
            BoxingState.CLOSE_RANGE -> {
                leftWeight = 6 + strafeDirection + inversionBoost * strafeDirection
                rightWeight = 6 - strafeDirection - inversionBoost * strafeDirection
            }
            BoxingState.EXTEND_COMBO -> {
                leftWeight = 7 + strafeDirection + inversionBoost
                rightWeight = 5 - strafeDirection - inversionBoost
            }
            BoxingState.RESET_SPACE -> {
                leftWeight = 4 - strafeDirection
                rightWeight = 4 + strafeDirection
            }
            BoxingState.PANIC_BREAK -> {
                leftWeight = 5 - strafeDirection
                rightWeight = 5 + strafeDirection
            }
            BoxingState.RECOVERY -> {
                leftWeight = 3
                rightWeight = 3
            }
        }

        maybeFlipStrafe(now, distance)

        movePriorityBuffer[0] = leftWeight
        movePriorityBuffer[1] = rightWeight
        handle(shouldClear, shouldRandom, movePriorityBuffer)
    }

    /** Apply detailed behaviour for the current state. */
    private fun applyStateBehaviours(now: Long, player: EntityPlayer, opp: EntityPlayer, distance: Float, deltaMs: Long) {
        when (state) {
            BoxingState.OPENING -> {
                // Opening: build speed in a straight line to contest the very first hit.
                Movement.startForward()
                Movement.startSprinting()
                Movement.clearLeftRight()
                Movement.stopBackward()
            }

            BoxingState.APPROACH -> {
                // Approach: controlled forward pressure with strafe adjustments based on kite detection.
                Movement.startForward()
                Movement.stopBackward()
                Movement.startSprinting()
                if (perception.kiteProbability > 0.55f && distance < Config.RESET_DISTANCE) {
                    Movement.swapLeftRight()
                }
            }

            BoxingState.CLOSE_RANGE -> {
                // Close range: stay glued to the opponent but avoid clipping through hitboxes.
                Movement.startForward()
                Movement.stopBackward()
                if (distance < 1.8f) {
                    Movement.stopForward()
                }
                Movement.startSprinting()
                manageLongStrafe(now)
            }

            BoxingState.EXTEND_COMBO -> {
                // Extend combo: prioritise keeping them in reach when we are ahead.
                Movement.startForward()
                Movement.stopBackward()
                Movement.startSprinting()
                if (combo - opponentCombo >= 4 && distance < 2.8f) {
                    Movement.stopForward()
                }
            }

            BoxingState.RESET_SPACE -> {
                // Reset: drift backwards a little to reset momentum after bad trades.
                Movement.stopForward()
                Movement.startBackward()
                Movement.startSprinting()
            }

            BoxingState.PANIC_BREAK -> {
                // Panic: hard disengage with a quick S-tap / jump to nullify point-blank rushes.
                Movement.stopForward()
                Movement.startBackward()
                Movement.stopSprinting()
                if (rng.nextBoolean()) {
                    Movement.startJumping()
                    TimeUtils.setTimeout(Movement::stopJumping, 120)
                }
            }

            BoxingState.RECOVERY -> {
                // Recovery: fully drop aggression until the timers expire.
                Movement.stopForward()
                Movement.startBackward()
                Movement.stopSprinting()
            }
        }

        applyAimController(player, opp, distance, deltaMs)
    }

    private fun manageLongStrafe(now: Long) {
        if (now > longStrafeActiveUntil && now > longStrafeCooldownUntil && rng.nextFloat() < Config.LONG_STRAFE_CHANCE) {
            val newDir = if (rng.nextBoolean()) 1 else -1
            if (newDir != strafeDirection) {
                strafeDirection = newDir
                longStrafeActiveUntil = now + RandomUtils.randomIntInRange(260, 420)
                longStrafeCooldownUntil = now + RandomUtils.randomIntInRange(800, 1100)
                if (TelemetryConfig.ENABLED) telemetry.forcedFlips++
            }
        }
    }

    /** Handles strafe flips when distance stagnates. */
    private fun maybeFlipStrafe(now: Long, distance: Float) {
        if (now < lastForcedFlipAt + Config.FLIP_COOLDOWN_MS) return

        if (perception.inversionChance > 0.7f && rng.nextFloat() < 0.5f) {
            strafeDirection = -strafeDirection
            lastForcedFlipAt = now
            if (TelemetryConfig.ENABLED) telemetry.forcedFlips++
            return
        }

        if (stagnationCheckAt == 0L) {
            stagnationCheckAt = now
            stagnationAnchor = distance
            return
        }

        if (now - stagnationCheckAt > RandomUtils.randomIntInRange(150, 260)) {
            val delta = abs(distance - stagnationAnchor)
            if (delta < Config.ANTI_STAGNATION_DELTA) {
                strafeDirection = -strafeDirection
                lastForcedFlipAt = now
                stagnationCheckAt = now
                stagnationAnchor = distance
                if (TelemetryConfig.ENABLED) telemetry.forcedFlips++
            } else {
                stagnationCheckAt = now
                stagnationAnchor = distance
            }
        }
    }

    /** Human style aiming: easing, jitter, FOV clamp and light prediction. */
    private fun applyAimController(player: EntityPlayer, opp: EntityPlayer, distance: Float, deltaMs: Long) {
        val lookAhead = when {
            distance < 2.4f -> 1.2f
            distance < 4.0f -> Config.PREDICT_TICKS_BASE
            else -> min(Config.MAX_LOOK_AHEAD_TICKS, Config.PREDICT_TICKS_BASE + distance * 0.2f)
        } + (perception.latencyMs / 180f)

        val velocity = opp.positionVector.subtract(Vec3(opp.prevPosX, opp.prevPosY, opp.prevPosZ))
        val predicted = opp.positionVector.addVector(
            velocity.xCoord * lookAhead,
            velocity.yCoord * (lookAhead * 0.45f),
            velocity.zCoord * lookAhead
        )

        val diffX = predicted.xCoord - player.posX
        val diffY = predicted.yCoord - (player.posY + player.eyeHeight.toDouble())
        val diffZ = predicted.zCoord - player.posZ
        val flatDist = sqrt(diffX * diffX + diffZ * diffZ)

        val desiredYaw = (Math.atan2(diffZ, diffX) * 180.0 / Math.PI).toFloat() - 90f
        val desiredPitch = (-(Math.atan2(diffY, flatDist) * 180.0 / Math.PI)).toFloat().coerceIn(-89f, 89f)

        val fov = Config.FOV_MIN + (Config.FOV_MAX - Config.FOV_MIN) * when (state) {
            BoxingState.APPROACH -> 0.55f
            BoxingState.CLOSE_RANGE -> 0.85f
            BoxingState.EXTEND_COMBO -> 1.0f
            BoxingState.PANIC_BREAK, BoxingState.RECOVERY -> 0.4f
            else -> 0.6f
        }

        val yawDiff = MathHelper.wrapAngleTo180_float(desiredYaw - player.rotationYaw)
        val pitchDiff = MathHelper.wrapAngleTo180_float(desiredPitch - player.rotationPitch)

        if (abs(yawDiff) > fov) {
            // Slow body turn if opponent is out of our dynamic FOV.
            player.rotationYaw += yawDiff.coerceIn(-3f, 3f)
            return
        }

        val tickScale = (deltaMs / 50f).coerceIn(0.7f, 1.3f)
        val baseSpeed = when {
            distance < 2.2f -> Config.ROT_SPEED_MAX
            distance < 4.0f -> (Config.ROT_SPEED_MIN + Config.ROT_SPEED_MAX) / 2f
            else -> Config.ROT_SPEED_MIN
        }
        val jitter = (rng.nextFloat() - 0.5f) * Config.MICRO_JITTER_DEG
        val speed = (baseSpeed + jitter) * tickScale

        val easing = 0.2f + rng.nextFloat() * Config.HUMAN_VARIANCE
        val yawStep = yawDiff * easing
        val pitchStep = pitchDiff * (easing * 0.8f)

        player.rotationYaw += yawStep.coerceIn(-speed, speed)
        player.rotationPitch = (player.rotationPitch + pitchStep.coerceIn(-speed, speed)).coerceIn(-89f, 89f)
    }

    /** Maintain the auto clicker cadence to look human. */
    private fun updateClicker(now: Long, distance: Float) {
        val cfgHit = kira.config?.kiraHit == true
        if (!cfgHit) {
            Mouse.stopLeftAC()
            clickState = ClickState.IDLE
            return
        }

        when (clickState) {
            ClickState.IDLE -> {
                clickState = ClickState.BURST
                clickStateUntil = now + chooseBurstDuration(distance)
                Mouse.startLeftAC()
            }

            ClickState.BURST -> {
                if (now >= clickStateUntil) {
                    val pauseDur = RandomUtils.randomIntInRange(60, if (distance < 3f) 120 else 200)
                    clickState = ClickState.PAUSE
                    clickStateUntil = now + pauseDur
                    Mouse.stopLeftAC()
                }
            }

            ClickState.PAUSE -> {
                if (now >= clickStateUntil) {
                    clickState = ClickState.BURST
                    clickStateUntil = now + chooseBurstDuration(distance)
                    Mouse.startLeftAC()
                }
            }
        }
    }

    private fun chooseBurstDuration(distance: Float): Int {
        return when {
            distance < 2.6f -> RandomUtils.randomIntInRange(320, 520)
            distance < 4.0f -> RandomUtils.randomIntInRange(240, 420)
            else -> RandomUtils.randomIntInRange(180, 320)
        }
    }

    /** Keep sprint engaged unless we intentionally drop it for recovery. */
    private fun maintainSprint(now: Long) {
        if (state == BoxingState.RECOVERY) return
        if (now - lastSprintResetAt > Config.SPRINT_RESET_MS && !Movement.sprinting()) {
            Movement.startSprinting()
        }
    }

    private fun maybePerformContextualJump(now: Long, player: EntityPlayer, distance: Float) {
        if (now - lastJumpAt < Config.JUMP_MIN_INTERVAL_MS) return
        val blockAhead = WorldUtils.blockInFront(player, 2f, 0.6f)
        val needsSpace = state == BoxingState.RESET_SPACE && distance < 3.4f
        if (blockAhead != Blocks.air || needsSpace) {
            Movement.singleJump(RandomUtils.randomIntInRange(140, 220))
            lastJumpAt = now
        }
    }

    /**
     * Decide the next high level state using the perception scores and recent history.
     */
    private fun decideNextState(now: Long, distance: Float): BoxingState {
        if (now < stateLockedUntil) return state

        val comboLead = combo - opponentCombo
        val tradeDeficit = tradeSamples > 4 && -tradeBalance > tradeSamples * Config.TRADE_BAD_RATIO
        val timeSinceHit = now - max(lastOwnHitAt, lastTakenHitAt)

        return when (state) {
            BoxingState.OPENING -> {
                // Until we are close enough, stay in opening stance.
                if (distance < Config.APPROACH_DISTANCE) BoxingState.APPROACH else BoxingState.OPENING
            }

            BoxingState.APPROACH -> when {
                // A sudden spike in danger inside melee distance -> panic.
                perception.dangerScore > Config.DANGER_THRESHOLD && distance < Config.PANIC_DISTANCE -> BoxingState.PANIC_BREAK
                // Extend combo as soon as we secure lead inside range.
                distance < Config.EXTEND_DISTANCE && comboLead >= 2 -> BoxingState.EXTEND_COMBO
                distance < Config.RESET_DISTANCE -> BoxingState.CLOSE_RANGE
                else -> BoxingState.APPROACH
            }

            BoxingState.CLOSE_RANGE -> when {
                // Danger spikes win over everything else.
                perception.dangerScore > Config.DANGER_THRESHOLD && distance < Config.PANIC_DISTANCE -> BoxingState.PANIC_BREAK
                tradeDeficit -> BoxingState.RESET_SPACE
                comboLead >= 3 -> BoxingState.EXTEND_COMBO
                distance > Config.RESET_DISTANCE -> BoxingState.APPROACH
                else -> BoxingState.CLOSE_RANGE
            }

            BoxingState.EXTEND_COMBO -> when {
                comboLead <= 0 -> BoxingState.CLOSE_RANGE
                distance > Config.EXTEND_DISTANCE + 0.8f -> BoxingState.CLOSE_RANGE
                tradeDeficit -> BoxingState.RESET_SPACE
                else -> BoxingState.EXTEND_COMBO
            }

            BoxingState.RESET_SPACE -> when {
                // Once space is cleared, resume approach.
                distance > Config.RECOVERY_DISTANCE -> BoxingState.APPROACH
                perception.dangerScore > Config.DANGER_THRESHOLD && now < panicArmedUntil -> BoxingState.PANIC_BREAK
                now > recoveryUntil -> BoxingState.CLOSE_RANGE
                else -> BoxingState.RESET_SPACE
            }

            BoxingState.PANIC_BREAK -> when {
                // After successfully breaking away, enter recovery.
                distance > Config.RESET_DISTANCE -> BoxingState.RECOVERY
                timeSinceHit > Config.PANIC_WINDOW_MS -> BoxingState.RESET_SPACE
                else -> BoxingState.PANIC_BREAK
            }

            BoxingState.RECOVERY -> when {
                // If they re-enter melee during recovery, defend immediately.
                distance < Config.RESET_DISTANCE -> BoxingState.CLOSE_RANGE
                now > recoveryUntil -> BoxingState.APPROACH
                else -> BoxingState.RECOVERY
            }
        }
    }

    /** Called every time the state transitions to set timers / randomness. */
    private fun enterState(newState: BoxingState, now: Long) {
        state = newState
        lastStateChange = now
        val lock = when (newState) {
            BoxingState.OPENING -> 200L
            BoxingState.APPROACH -> RandomUtils.randomIntInRange(120, 200).toLong()
            BoxingState.CLOSE_RANGE -> RandomUtils.randomIntInRange(150, 220).toLong()
            BoxingState.EXTEND_COMBO -> RandomUtils.randomIntInRange(160, 240).toLong()
            BoxingState.RESET_SPACE -> RandomUtils.randomIntInRange(140, 220).toLong()
            BoxingState.PANIC_BREAK -> 120L
            BoxingState.RECOVERY -> RandomUtils.randomIntInRange(220, 320).toLong()
        }
        stateLockedUntil = now + lock

        if (now > ideaCooldownUntil || ideaId == newState.ordinal) {
            val available = BoxingState.values().indices.filter { it != ideaId }
            ideaId = available[rng.nextInt(available.size)]
            ideaCooldownUntil = now + Config.IDEA_COOLDOWN_MS + RandomUtils.randomIntInRange(-120, 120)
            ideaRepeat = 0
        } else {
            ideaRepeat++
            if (ideaRepeat >= 2) {
                strafeDirection = -strafeDirection
                ideaRepeat = 0
                if (TelemetryConfig.ENABLED) telemetry.forcedFlips++
            }
        }
    }

    private fun registerTrade(value: Int) {
        val removed = tradeHistory[tradeIndex]
        tradeHistory[tradeIndex] = value
        tradeIndex = (tradeIndex + 1) % tradeHistory.size
        if (tradeSamples < tradeHistory.size) {
            tradeSamples++
            tradeBalance += value
        } else {
            tradeBalance += value - removed
        }
    }

    private fun pushKbSample(distance: Float) {
        kbSamples[kbIndex] = distance
        kbIndex = (kbIndex + 1) % kbSamples.size
        if (kbFilled < kbSamples.size) kbFilled++
    }

    /** Debug helper to print telemetry mid game (invoked manually via chat command). */
    @Suppress("unused")
    private fun dumpTelemetry() {
        if (!TelemetryConfig.ENABLED) return
        val stateNames = BoxingState.values()
        val sb = StringBuilder("[Boxing] Telemetry: ")
        for (i in stateNames.indices) {
            sb.append(stateNames[i].name).append('=')
                .append(telemetry.stateTimeMs[i]).append("ms ")
        }
        sb.append("flips=").append(telemetry.forcedFlips)
        sb.append(" trades=").append(telemetry.tradesWon).append('/').append(telemetry.tradesLost)
        if (telemetry.impactSamples > 0) {
            sb.append(" dist=")
                .append(String.format("%.2f", telemetry.impactDistanceAccum / telemetry.impactSamples))
        }
        ChatUtils.info(sb.toString())
    }

    /** Exposed for compatibility with previous behaviour. */
    private fun opponent(): EntityPlayer? = super.opponent()
}

