package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.ChatUtils
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.WorldUtils
import net.minecraft.init.Blocks
import net.minecraft.util.MathHelper
import net.minecraft.util.Vec3
import java.util.EnumMap
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Refactored Boxing bot implementing a full behaviour model driven by a finite state machine (FSM).
 * The bot exposes a wide range of tuning variables that can be modified to match player preference
 * and server conditions. The public API (class name, lifecycle hooks, handle signature) is kept
 * intact to guarantee compatibility with the broader Kira project.
 */
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

    // ------------------------------------------------------------------------------
    // Configuration (grouped for easy tuning)
    // Each value is deliberately mutable so it can be tweaked in real time if needed.
    // ------------------------------------------------------------------------------
    private object Tunables {
        // Global aggression baseline that every state builds upon.
        var AGGRO_BASE = 0.55f

        // Delay ranges (ms) for click controller per distance band.
        var CLOSE_DELAY_RANGE_MS = 45..70
        var MID_DELAY_RANGE_MS = 65..95
        var LONG_DELAY_RANGE_MS = 90..125

        // Movement related controls.
        var LONG_STRAFE_CHANCE = 0.35f
        var JUMP_MIN_INTERVAL_MS = 650L
        var MICRO_JUMP_COOLDOWN_MS = 850L
        var ANTI_STAGNATION_DELTA = 0.18f

        // Rotational limits (degrees per tick) and FoV clamps.
        var FOV_MIN = 28f
        var FOV_MAX = 82f
        var ROT_SPEED_MIN = 2.5f
        var ROT_SPEED_MAX = 7.4f

        // Trade management and risk handling.
        var TRADE_BAD_RATIO = 0.55f
        var RECOVERY_TIME_MS = 1400L
        var PANIC_BREAK_TIME_MS = 950L
        var COMBO_LEAD_BUFFER = 2

        // Predictive aim horizon (ticks) per distance band.
        var PREDICTIVE_CLOSE_TICKS = 2
        var PREDICTIVE_MID_TICKS = 3
        var PREDICTIVE_LONG_TICKS = 4

        // RNG / humanisation knobs.
        var JITTER_DEGREES = 0.35f
        var IDEA_COOLDOWN_MS = 1100L
        var STATE_DWELL_MIN_MS = 260L

        // Opponent modelling horizon.
        var TRADE_WINDOW = 14
        var DISTANCE_HISTORY_TICKS = 20
    }

    // ------------------------------------------------------------------------------
    // State & instrumentation holders
    // ------------------------------------------------------------------------------
    private enum class BoxingState { OPENING, APPROACH, CLOSE_RANGE, EXTEND_COMBO, RESET_SPACE, PANIC_BREAK, RECOVERY }

    private val rng = Random()
    private var state = BoxingState.OPENING
    private var lastStateChange = 0L
    private var lastIdeaTime = 0L

    private var tapping = false
    private var fishTimer: java.util.Timer? = null
    private var leftACEnabled = false

    private var lastTickTime = System.currentTimeMillis()
    private var lastJumpTime = 0L
    private var lastMicroJumpTime = 0L

    private val perception = OpponentPerception()
    private val telemetry = Telemetry()

    // ------------------------------------------------------------------------------
    // Opponent modelling (distance trend, trade history, aggression scoring)
    // ------------------------------------------------------------------------------
    private data class OpponentPerception(
        var lastOpponentPos: Vec3? = null,
        var lastOpponentVelocity: Vec3 = Vec3(0.0, 0.0, 0.0),
        var lastDistance: Double = 0.0,
        var distanceSamples: ArrayDeque<Double> = ArrayDeque(),
        var hitsFor: Int = 0,
        var hitsAgainst: Int = 0,
        var recentTrades: ArrayDeque<Boolean> = ArrayDeque(),
        var lastOurHitTime: Long = 0L,
        var lastOpponentHitTime: Long = 0L,
        var estimatedPingMs: Float = 65f,
        var strafeBias: Float = 0f,
        var strafeSamples: ArrayDeque<Float> = ArrayDeque(),
        var dangerScore: Float = 0f,
        var inversionProbability: Float = 0f,
        var opponentAggression: Float = 0.5f,
        var lastOpponentYaw: Float = 0f,
        var stagnationTicks: Int = 0,
    ) {
        fun reset() {
            lastOpponentPos = null
            lastOpponentVelocity = Vec3(0.0, 0.0, 0.0)
            lastDistance = 0.0
            distanceSamples.clear()
            hitsFor = 0
            hitsAgainst = 0
            recentTrades.clear()
            estimatedPingMs = 65f
            strafeBias = 0f
            strafeSamples.clear()
            dangerScore = 0f
            inversionProbability = 0f
            opponentAggression = 0.5f
            lastOpponentYaw = 0f
            stagnationTicks = 0
        }
    }

    private data class Telemetry(
        val stateTimeMs: EnumMap<BoxingState, Long> = EnumMap(BoxingState::class.java),
        var forcedFlips: Int = 0,
        var distanceAtHitAccum: Double = 0.0,
        var distanceAtHitCount: Int = 0,
        var tradesPositive: Int = 0,
        var tradesNegative: Int = 0,
        var telemetryEnabled: Boolean = true,
    )

    // ------------------------------------------------------------------------------
    // Lifecycle hooks (public API preserved)
    // ------------------------------------------------------------------------------
    override fun onGameStart() {
        resetState()
        Movement.startSprinting()
        Movement.startForward()
        startFishTimer()
        configureClicks()
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            fishTimer?.cancel()
            Mouse.stopTracking()
            leftACEnabled = false
        }, RandomUtils.randomIntInRange(100, 300))
    }

    override fun onAttack() {
        // Called when we land a hit. We capitalise with a subtle w-tap burst to keep combos alive.
        tapping = true
        perception.lastOurHitTime = System.currentTimeMillis()
        Combat.wTap(100)
        ChatUtils.info("W-Tap")
        TimeUtils.setTimeout({ tapping = false }, 110)
        if (combo >= 3) Movement.clearLeftRight()
    }

    override fun onTick() {
        val player = mc.thePlayer ?: return
        val enemy = opponent() ?: run {
            perception.reset()
            Movement.startForward()
            return
        }

        val now = System.currentTimeMillis()
        val delta = now - lastTickTime
        lastTickTime = now

        updatePerception(player, enemy, now)
        updateState(player, enemy, now)
        applyStateBehaviour(player, enemy, now, delta)
        manageClickController(now)
        attemptMicroJump(player, enemy, now)
        updateTelemetry(now, delta)
    }

    // ------------------------------------------------------------------------------
    // High level helpers
    // ------------------------------------------------------------------------------
    private fun resetState() {
        state = BoxingState.OPENING
        lastStateChange = System.currentTimeMillis()
        perception.reset()
        telemetry.stateTimeMs.clear()
        telemetry.forcedFlips = 0
        telemetry.tradesPositive = 0
        telemetry.tradesNegative = 0
        telemetry.distanceAtHitAccum = 0.0
        telemetry.distanceAtHitCount = 0
        lastJumpTime = 0L
        lastMicroJumpTime = 0L
        tapping = false
    }

    private fun startFishTimer() {
        if (kira.config?.boxingFish == true) {
            fishTimer?.cancel()
            fishTimer = TimeUtils.setTimeout({ fishFunc(true) }, RandomUtils.randomIntInRange(10000, 20000))
        }
    }

    private fun configureClicks() {
        if (kira.config?.kiraHit == true) {
            Mouse.startLeftAC()
            leftACEnabled = true
        } else {
            Mouse.stopLeftAC()
            leftACEnabled = false
        }
    }

    // ------------------------------------------------------------------------------
    // Inventory toggling between fish and sword
    // ------------------------------------------------------------------------------
    private fun fishFunc(fish: Boolean) {
        if (StateManager.state == StateManager.States.PLAYING) {
            if (fish) Inventory.setInvItem("fish") else Inventory.setInvItem("sword")
            fishTimer = TimeUtils.setTimeout({ fishFunc(!fish) }, RandomUtils.randomIntInRange(10000, 20000))
        }
    }

    // ------------------------------------------------------------------------------
    // Perception Update Module
    // ------------------------------------------------------------------------------
    private fun updatePerception(player: net.minecraft.client.entity.EntityPlayerSP, enemy: net.minecraft.entity.EntityLivingBase, now: Long) {
        val distance = EntityUtils.getDistanceNoY(player, enemy)
        perception.lastDistance = distance

        // Maintain a bounded distance history to detect stagnation and trends.
        perception.distanceSamples.addLast(distance)
        if (perception.distanceSamples.size > Tunables.DISTANCE_HISTORY_TICKS) {
            perception.distanceSamples.removeFirst()
        }
        val distTrend = if (perception.distanceSamples.size >= 2) {
            (perception.distanceSamples.last() - perception.distanceSamples.first()).toFloat()
        } else 0f
        perception.stagnationTicks = if (abs(distTrend) < Tunables.ANTI_STAGNATION_DELTA) perception.stagnationTicks + 1 else 0

        // Velocity estimation via positional delta.
        val opponentPos = Vec3(enemy.posX, enemy.posY, enemy.posZ)
        perception.lastOpponentPos?.let {
            val dx = opponentPos.xCoord - it.xCoord
            val dy = opponentPos.yCoord - it.yCoord
            val dz = opponentPos.zCoord - it.zCoord
            perception.lastOpponentVelocity = Vec3(dx, dy, dz)
        }
        perception.lastOpponentPos = opponentPos

        // Strafe bias estimation: negative = left, positive = right.
        val relativeYaw = enemy.rotationYaw - perception.lastOpponentYaw
        perception.lastOpponentYaw = enemy.rotationYaw
        if (abs(relativeYaw) > 0.2f) {
            val bias = sign(relativeYaw)
            perception.strafeSamples.addLast(bias)
            if (perception.strafeSamples.size > 10) perception.strafeSamples.removeFirst()
            perception.strafeBias = perception.strafeSamples.average().toFloat()
        }

        // Update trade window from hurtTime events.
        if (enemy.hurtTime > 0 && now - perception.lastOpponentHitTime > 200) {
            perception.lastOpponentHitTime = now
            perception.hitsFor++
            registerTrade(true)
            telemetry.distanceAtHitAccum += distance
            telemetry.distanceAtHitCount++
        }
        if (player.hurtTime > 0 && now - perception.lastOurHitTime > 200) {
            perception.lastOurHitTime = now
            perception.hitsAgainst++
            registerTrade(false)
        }

        // Ping estimation from hit intervals.
        if (perception.recentTrades.isNotEmpty()) {
            val good = perception.recentTrades.count { it }
            val bad = perception.recentTrades.size - good
            perception.estimatedPingMs = 40f + bad * 8f
        }

        // Basic aggression modelling: if the opponent keeps distance shrinking we tag them aggressive.
        val shrink = perception.distanceSamples.zipWithNext { a, b -> (a - b).toFloat() }
        val aggressionScore = shrink.count { it > 0 } - shrink.count { it < 0 }
        perception.opponentAggression = 0.5f + aggressionScore.coerceIn(-5, 5) * 0.05f

        // Danger score is a composite of velocity towards us and combo deficit.
        val velocityMag = perception.lastOpponentVelocity.lengthVector()
        val comboDeficit = max(0, perception.hitsAgainst - perception.hitsFor)
        perception.dangerScore = (velocityMag * 0.8f + comboDeficit * 0.2f).toFloat()

        // Probability of inversion (opponent counter hit) grows when we whiff and they are close.
        val timeSinceOurHit = now - perception.lastOurHitTime
        perception.inversionProbability = when {
            distance < 2.6 -> min(0.85f, 0.35f + timeSinceOurHit / 800f)
            distance < 4.5 -> min(0.65f, 0.2f + timeSinceOurHit / 1000f)
            else -> 0.15f
        }
    }

    private fun registerTrade(favourable: Boolean) {
        perception.recentTrades.addLast(favourable)
        if (perception.recentTrades.size > Tunables.TRADE_WINDOW) {
            perception.recentTrades.removeFirst()
        }
        if (favourable) telemetry.tradesPositive++ else telemetry.tradesNegative++
    }

    // ------------------------------------------------------------------------------
    // Finite State Machine (FSM)
    // ------------------------------------------------------------------------------
    private fun updateState(player: net.minecraft.client.entity.EntityPlayerSP, enemy: net.minecraft.entity.EntityLivingBase, now: Long) {
        val distance = perception.lastDistance
        val comboLead = perception.hitsFor - perception.hitsAgainst
        val timeSinceStateChange = now - lastStateChange
        val timeSinceOurHit = now - perception.lastOurHitTime
        val timeSinceOpponentHit = now - perception.lastOpponentHitTime

        val tradeBadRatio = if (perception.recentTrades.isEmpty()) 0f else {
            val negatives = perception.recentTrades.count { !it }
            negatives.toFloat() / perception.recentTrades.size
        }

        val shouldExitEarly = timeSinceStateChange > Tunables.STATE_DWELL_MIN_MS

        val desiredState = when (state) {
            BoxingState.OPENING -> when {
                timeSinceStateChange > 600L || distance < 5.5 -> BoxingState.APPROACH
                else -> state
            }

            BoxingState.APPROACH -> when {
                tradeBadRatio > Tunables.TRADE_BAD_RATIO && shouldExitEarly -> BoxingState.RESET_SPACE
                perception.dangerScore > 1.8f && distance < 3.4 -> BoxingState.PANIC_BREAK
                distance < 2.8 -> BoxingState.CLOSE_RANGE
                comboLead >= Tunables.COMBO_LEAD_BUFFER && timeSinceOurHit < 450 -> BoxingState.EXTEND_COMBO
                else -> state
            }

            BoxingState.CLOSE_RANGE -> when {
                tradeBadRatio > Tunables.TRADE_BAD_RATIO && shouldExitEarly -> BoxingState.RESET_SPACE
                comboLead >= Tunables.COMBO_LEAD_BUFFER && timeSinceOurHit < 350 -> BoxingState.EXTEND_COMBO
                distance > 3.5 -> BoxingState.APPROACH
                timeSinceOpponentHit < Tunables.PANIC_BREAK_TIME_MS -> BoxingState.PANIC_BREAK
                else -> state
            }

            BoxingState.EXTEND_COMBO -> when {
                comboLead <= 0 && shouldExitEarly -> BoxingState.CLOSE_RANGE
                timeSinceOurHit > Tunables.RECOVERY_TIME_MS -> BoxingState.RECOVERY
                distance > 3.4 -> BoxingState.APPROACH
                tradeBadRatio > Tunables.TRADE_BAD_RATIO -> BoxingState.RESET_SPACE
                else -> state
            }

            BoxingState.RESET_SPACE -> when {
                distance > 4.5 && timeSinceOpponentHit > 350 -> BoxingState.APPROACH
                timeSinceStateChange > Tunables.RECOVERY_TIME_MS -> BoxingState.RECOVERY
                else -> state
            }

            BoxingState.PANIC_BREAK -> when {
                distance > 4.2 -> BoxingState.RESET_SPACE
                timeSinceStateChange > Tunables.PANIC_BREAK_TIME_MS -> BoxingState.RECOVERY
                else -> state
            }

            BoxingState.RECOVERY -> when {
                timeSinceStateChange > Tunables.RECOVERY_TIME_MS -> BoxingState.APPROACH
                distance < 3.0 && timeSinceOpponentHit > 260 -> BoxingState.CLOSE_RANGE
                else -> state
            }
        }

        if (desiredState != state) {
            state = desiredState
            lastStateChange = now
            if (telemetry.telemetryEnabled) {
                telemetry.forcedFlips++
            }
        }
    }

    // ------------------------------------------------------------------------------
    // State driven behaviour execution
    // ------------------------------------------------------------------------------
    private fun applyStateBehaviour(player: net.minecraft.client.entity.EntityPlayerSP, enemy: net.minecraft.entity.EntityLivingBase, now: Long, delta: Long) {
        val distance = perception.lastDistance
        val comboLead = perception.hitsFor - perception.hitsAgainst

        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        // Reset backward input each tick unless an explicit state requires it.
        Movement.stopBackward()

        val forwardStick = shouldHoldForward(distance, comboLead)
        if (forwardStick && !tapping) Movement.startForward() else Movement.stopForward()

        // Determine strafe direction preference according to opponent strafe bias & distance.
        val strafeDirection = computeStrafeDirection(distance)
        applyStrafe(strafeDirection)

        when (state) {
            BoxingState.OPENING -> {
                // Charge forward with measured aggression while avoiding early trades.
                applyAimControl(distance, Tunables.FOV_MAX, Tunables.ROT_SPEED_MAX)
                randomStrafe = true
                movePriority[0] += 1
                movePriority[1] += 1
                if (distance < 3.2 && now - perception.lastOurHitTime > 400) {
                    Movement.stopForward()
                }
            }

            BoxingState.APPROACH -> {
                // Maintain mid-range pressure with predictive aim.
                applyAimControl(distance, Tunables.FOV_MAX - 6, Tunables.ROT_SPEED_MAX - 0.8f)
                movePriority[0] += if (strafeDirection > 0) 1 else 0
                movePriority[1] += if (strafeDirection < 0) 1 else 0
                if (distance > 5 && rng.nextFloat() < 0.35f) {
                    executeLongStrafe(distance)
                    randomStrafe = true
                }
            }

            BoxingState.CLOSE_RANGE -> {
                // Stick to opponent, slight jitter to avoid trades.
                applyAimControl(distance, Tunables.FOV_MAX - 12, Tunables.ROT_SPEED_MAX - 1.2f)
                movePriority[0] += if (strafeDirection > 0) 3 else 1
                movePriority[1] += if (strafeDirection < 0) 3 else 1
                if (comboLead < 0 && rng.nextFloat() < 0.2f) {
                    clear = true
                }
                maybeTriggerAntiTrade(now)
            }

            BoxingState.EXTEND_COMBO -> {
                // Keep combo lead with micro lateral flips.
                applyAimControl(distance, Tunables.FOV_MIN + 10, Tunables.ROT_SPEED_MAX)
                movePriority[0] += if (strafeDirection > 0) 4 else 2
                movePriority[1] += if (strafeDirection < 0) 4 else 2
                if (now - lastIdeaTime > Tunables.IDEA_COOLDOWN_MS) {
                    flipStrafe()
                    lastIdeaTime = now
                }
                if (rng.nextFloat() < 0.15f) {
                    Movement.singleJump(RandomUtils.randomIntInRange(120, 180))
                }
            }

            BoxingState.RESET_SPACE -> {
                // Create distance and reset rhythm.
                applyAimControl(distance, Tunables.FOV_MAX, Tunables.ROT_SPEED_MIN + 0.4f)
                Movement.stopForward()
                Movement.startBackward()
                movePriority[0] += if (strafeDirection > 0) 2 else 1
                movePriority[1] += if (strafeDirection < 0) 2 else 1
                if (perception.stagnationTicks > 6) {
                    flipStrafe()
                }
                if (distance > 4.6) Movement.stopBackward()
            }

            BoxingState.PANIC_BREAK -> {
                // Emergency disengage after losing a trade or facing heavy pressure.
                applyAimControl(distance, Tunables.FOV_MAX, Tunables.ROT_SPEED_MIN)
                clear = true
                Movement.stopForward()
                Movement.startBackward()
                movePriority[0] += 2
                movePriority[1] += 2
                Movement.singleJump(RandomUtils.randomIntInRange(150, 220))
                Combat.stopRandomStrafe()
            }

            BoxingState.RECOVERY -> {
                // Short breather to break patterns; slow aim and re-sync sprint.
                applyAimControl(distance, Tunables.FOV_MAX - 4, Tunables.ROT_SPEED_MIN)
                randomStrafe = true
                movePriority[0] += 1
                movePriority[1] += 1
                if (now - perception.lastOurHitTime > 350) Movement.startForward()
            }
        }

        handle(clear, randomStrafe, movePriority)
        maintainSprint(player)
    }

    private fun shouldHoldForward(distance: Double, comboLead: Int): Boolean {
        val aggressionBoost = Tunables.AGGRO_BASE + comboLead.coerceIn(-5, 5) * 0.04f -
            (perception.opponentAggression - 0.5f) * 0.3f
        return when {
            distance < 1.6 -> aggressionBoost > 0.7f && perception.inversionProbability < 0.45f
            distance < 2.5 -> aggressionBoost > 0.6f || perception.inversionProbability < 0.5f
            distance < 3.5 -> true
            else -> aggressionBoost > 0.4f
        }
    }

    private fun computeStrafeDirection(distance: Double): Int {
        val bias = perception.strafeBias
        val randomFlip = rng.nextFloat() < 0.12f
        val direction = when {
            randomFlip -> if (rng.nextBoolean()) 1 else -1
            distance < 2.6 -> -bias.sign.toInt().takeIf { it != 0 } ?: 1
            distance < 4.2 -> bias.sign.toInt().takeIf { it != 0 } ?: if (rng.nextBoolean()) 1 else -1
            else -> if (rng.nextBoolean()) 1 else -1
        }
        return direction
    }

    private fun applyStrafe(direction: Int) {
        if (direction > 0) {
            Movement.startRight()
            Movement.stopLeft()
        } else {
            Movement.startLeft()
            Movement.stopRight()
        }
    }

    private fun applyAimControl(distance: Double, fovClamp: Float, rotSpeedClamp: Float) {
        val player = mc.thePlayer ?: return
        val predictiveTicks = when {
            distance < 2.6 -> Tunables.PREDICTIVE_CLOSE_TICKS
            distance < 5.0 -> Tunables.PREDICTIVE_MID_TICKS
            else -> Tunables.PREDICTIVE_LONG_TICKS
        }

        val predicted = predictPositionVector(opponent(), predictiveTicks)
        val diffX = predicted.xCoord - player.posX
        val diffY = predicted.yCoord - (player.posY + player.eyeHeight)
        val diffZ = predicted.zCoord - player.posZ
        val flatDist = Math.sqrt(diffX * diffX + diffZ * diffZ)

        val targetYaw = (Math.atan2(diffZ, diffX) * 180.0 / Math.PI).toFloat() - 90.0f
        val targetPitch = (-(Math.atan2(diffY, flatDist) * 180.0 / Math.PI)).toFloat()

        val yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - player.rotationYaw)
        val pitchDiff = MathHelper.wrapAngleTo180_float(targetPitch - player.rotationPitch)

        val yawSpeed = rotSpeedClamp.coerceIn(Tunables.ROT_SPEED_MIN, Tunables.ROT_SPEED_MAX)
        val pitchSpeed = (rotSpeedClamp / 1.6f).coerceIn(Tunables.ROT_SPEED_MIN / 2f, Tunables.ROT_SPEED_MAX / 1.5f)

        val easedYaw = easeRotationDelta(yawDiff, yawSpeed)
        val easedPitch = easeRotationDelta(pitchDiff, pitchSpeed)

        val jitter = (rng.nextFloat() - 0.5f) * Tunables.JITTER_DEGREES
        player.rotationYaw += easedYaw + jitter
        player.rotationPitch = (player.rotationPitch + easedPitch + jitter / 2f).coerceIn(-90f, 90f)
    }

    private fun predictPositionVector(target: net.minecraft.entity.EntityLivingBase?, ticks: Int): Vec3 {
        if (target == null) return Vec3(0.0, 0.0, 0.0)
        val velocity = perception.lastOpponentVelocity
        val multiplier = (ticks / max(1f, perception.estimatedPingMs / 50f)).coerceAtLeast(1f)
        val px = target.posX + velocity.xCoord * multiplier
        val py = target.posY + velocity.yCoord * multiplier + target.eyeHeight * 0.5
        val pz = target.posZ + velocity.zCoord * multiplier
        return Vec3(px, py, pz)
    }

    private fun easeRotationDelta(diff: Float, clamp: Float): Float {
        val sign = if (diff >= 0) 1 else -1
        val magnitude = abs(diff)
        val eased = when {
            magnitude < clamp * 0.35f -> magnitude * 0.9f
            magnitude < clamp -> magnitude * 0.6f + clamp * 0.15f
            else -> clamp - clamp * 0.25f
        }
        return eased * sign
    }

    private fun executeLongStrafe(distance: Double) {
        if (distance < 2.4 || rng.nextFloat() > Tunables.LONG_STRAFE_CHANCE) return
        if (System.currentTimeMillis() - lastIdeaTime < Tunables.IDEA_COOLDOWN_MS) return
        lastIdeaTime = System.currentTimeMillis()
        Movement.startLeft()
        Movement.startForward()
        TimeUtils.setTimeout({ Movement.stopLeft(); Movement.startRight() }, RandomUtils.randomIntInRange(220, 320))
        TimeUtils.setTimeout({ Movement.stopRight() }, RandomUtils.randomIntInRange(360, 440))
    }

    private fun maybeTriggerAntiTrade(now: Long) {
        val tradeBadRatio = if (perception.recentTrades.isEmpty()) 0f else {
            val negatives = perception.recentTrades.count { !it }
            negatives.toFloat() / perception.recentTrades.size
        }
        if (tradeBadRatio >= Tunables.TRADE_BAD_RATIO && now - lastIdeaTime > Tunables.IDEA_COOLDOWN_MS) {
            flipStrafe()
            lastIdeaTime = now
        }
    }

    private fun flipStrafe() {
        if (Movement.left()) {
            Movement.stopLeft()
            Movement.startRight()
        } else if (Movement.right()) {
            Movement.stopRight()
            Movement.startLeft()
        } else {
            if (rng.nextBoolean()) {
                Movement.startLeft()
                Movement.stopRight()
            } else {
                Movement.startRight()
                Movement.stopLeft()
            }
        }
        telemetry.forcedFlips++
    }

    private fun maintainSprint(player: net.minecraft.client.entity.EntityPlayerSP) {
        if (!player.isSprinting) {
            Movement.startSprinting()
        }
    }

    private fun attemptMicroJump(player: net.minecraft.client.entity.EntityPlayerSP, enemy: net.minecraft.entity.EntityLivingBase, now: Long) {
        if (!player.onGround) return
        if (now - lastJumpTime < Tunables.JUMP_MIN_INTERVAL_MS) return
        if (WorldUtils.blockInFront(player, 2f, 0.5f) != Blocks.air) {
            Movement.singleJump(RandomUtils.randomIntInRange(150, 250))
            lastJumpTime = now
            return
        }
        if (state == BoxingState.RESET_SPACE && now - lastMicroJumpTime > Tunables.MICRO_JUMP_COOLDOWN_MS) {
            Movement.singleJump(RandomUtils.randomIntInRange(120, 170))
            lastMicroJumpTime = now
        }
        if (state == BoxingState.EXTEND_COMBO && perception.lastDistance > 3.2 && now - lastMicroJumpTime > Tunables.MICRO_JUMP_COOLDOWN_MS) {
            Movement.singleJump(RandomUtils.randomIntInRange(140, 190))
            lastMicroJumpTime = now
        }
    }

    private var clickBurstActive = false
    private var clickBurstEnd = 0L
    private var nextClickBurstCheck = 0L

    private fun manageClickController(now: Long) {
        if (kira.config?.kiraHit != true) {
            if (leftACEnabled) {
                Mouse.stopLeftAC()
                leftACEnabled = false
            }
            return
        }

        val distance = perception.lastDistance
        if (now >= nextClickBurstCheck) {
            val checkRange = when {
                distance < 2.6 -> Tunables.CLOSE_DELAY_RANGE_MS
                distance < 5.0 -> Tunables.MID_DELAY_RANGE_MS
                else -> Tunables.LONG_DELAY_RANGE_MS
            }
            nextClickBurstCheck = now + RandomUtils.randomIntInRange(checkRange.first * 2, checkRange.last * 3)
            if (!clickBurstActive && distance < 2.6 && rng.nextFloat() < 0.6f) {
                clickBurstActive = true
                val burstRange = Tunables.CLOSE_DELAY_RANGE_MS
                clickBurstEnd = now + RandomUtils.randomIntInRange(burstRange.first * 4, burstRange.last * 5)
            } else if (clickBurstActive && distance > 4.5) {
                clickBurstActive = false
                val pauseRange = Tunables.LONG_DELAY_RANGE_MS
                nextClickBurstCheck += RandomUtils.randomIntInRange(pauseRange.first, pauseRange.last)
            }
        }

        if (clickBurstActive) {
            if (!leftACEnabled) {
                Mouse.startLeftAC()
                leftACEnabled = true
            }
            if (now > clickBurstEnd) {
                clickBurstActive = false
                val recoveryRange = Tunables.MID_DELAY_RANGE_MS
                nextClickBurstCheck = now + RandomUtils.randomIntInRange(recoveryRange.first * 2, recoveryRange.last * 3)
            }
        } else {
            if (leftACEnabled && distance > 3.5 && rng.nextFloat() < 0.2f) {
                Mouse.stopLeftAC()
                leftACEnabled = false
            }
            if (!leftACEnabled && (distance < 3.0 || rng.nextFloat() < 0.45f)) {
                Mouse.startLeftAC()
                leftACEnabled = true
            }
        }
    }

    private fun updateTelemetry(now: Long, delta: Long) {
        if (!telemetry.telemetryEnabled) return
        val current = telemetry.stateTimeMs[state] ?: 0L
        telemetry.stateTimeMs[state] = current + delta
    }
}

private fun Vec3.lengthVector(): Double {
    return Math.sqrt(xCoord * xCoord + yCoord * yCoord + zCoord * zCoord)
}

private fun Float.sign(): Float = when {
    this > 0f -> 1f
    this < 0f -> -1f
    else -> 0f
}
