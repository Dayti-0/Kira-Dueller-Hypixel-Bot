package best.spaghetcodes.kira.bot.tuning

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Generic UCB1 multi-armed bandit implementation usable across tuners.
 */
class UcbBandit(
    val armCount: Int,
    val totalPlays: Long,
    plays: LongArray,
    rewards: DoubleArray,
    val minReward: Double = 0.0,
    val maxReward: Double = 1.0,
    val strategy: Strategy = Strategy.UCB1,
) {
    private val plays: LongArray = plays.copyOf()
    private val rewards: DoubleArray = rewards.copyOf()

    init {
        require(armCount > 0) { "armCount must be positive" }
        require(this.plays.size == armCount) { "plays size must match armCount" }
        require(this.rewards.size == armCount) { "rewards size must match armCount" }
        require(maxReward > minReward) { "maxReward must be greater than minReward" }
    }

    /**
     * Selects the next arm to play using the configured strategy.
     */
    fun selectArm(): Int {
        for (i in 0 until armCount) {
            if (plays[i] == 0L) return i
        }

        val t = max(totalPlays.toDouble(), 1.0)
        val logN = ln(t)
        var bestArm = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (i in 0 until armCount) {
            val averageReward = rewards[i] / plays[i]
            val score = when (strategy) {
                Strategy.UCB1 -> {
                    val explorationBonus = sqrt(2.0 * logN / plays[i])
                    averageReward + explorationBonus
                }

                Strategy.UCB_TUNED -> {
                    val variance = averageReward * (1 - averageReward)
                    val v = variance + sqrt(2.0 * logN / plays[i])
                    val bonus = sqrt((logN / plays[i]) * min(0.25, v))
                    averageReward + bonus
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestArm = i
            }
        }

        return bestArm
    }

    /**
     * Returns a new bandit instance with updated statistics for the provided arm.
     */
    fun update(arm: Int, reward: Double): UcbBandit {
        require(arm in 0 until armCount) { "arm index out of bounds" }

        val updatedPlays = plays.copyOf()
        val updatedRewards = rewards.copyOf()

        updatedPlays[arm] += 1L
        updatedRewards[arm] += reward

        return UcbBandit(armCount, totalPlays + 1, updatedPlays, updatedRewards, minReward, maxReward, strategy)
    }

    /**
     * Applies exponential decay to past statistics so that recent samples weigh more without fully resetting history.
     */
    fun decay(factor: Double): UcbBandit {
        require(factor in 0.0..1.0 && factor > 0.0) { "factor must be in (0, 1]" }

        val decayedPlays = LongArray(armCount)
        var decayedTotal = 0L
        for (i in 0 until armCount) {
            val originalPlays = plays[i]
            if (originalPlays == 0L) {
                decayedPlays[i] = 0
                continue
            }
            val scaled = floor(originalPlays * factor).toLong()
            val ensured = max(1L, scaled)
            decayedPlays[i] = ensured
            decayedTotal += ensured
        }

        val decayedRewards = DoubleArray(armCount) { rewards[it] * factor }

        return UcbBandit(armCount, decayedTotal, decayedPlays, decayedRewards, minReward, maxReward, strategy)
    }

    fun normalizeReward(reward: Double): Double {
        val normalized = (reward - minReward) / (maxReward - minReward)
        return normalized.coerceIn(0.0, 1.0)
    }

    fun armStats(): List<ArmStats> {
        return (0 until armCount)
            .map { index ->
                val playsCount = plays[index]
                val averageReward = if (playsCount > 0) rewards[index] / playsCount else 0.0
                ArmStats(index, playsCount, averageReward)
            }
            .sortedWith(compareByDescending<ArmStats> { it.averageReward }.thenBy { it.index })
    }

    fun bestArmIndex(): Int {
        var bestIndex = 0
        var bestAverage = if (plays[0] > 0) rewards[0] / plays[0] else 0.0

        for (i in 1 until armCount) {
            val playsCount = plays[i]
            val averageReward = if (playsCount > 0) rewards[i] / playsCount else 0.0

            if (averageReward > bestAverage || (averageReward == bestAverage && i < bestIndex)) {
                bestAverage = averageReward
                bestIndex = i
            }
        }

        return bestIndex
    }

    fun toState(): UcbBanditState =
        UcbBanditState(totalPlays, plays.copyOf(), rewards.copyOf(), minReward, maxReward, strategy)

    fun toDto(): UcbBanditDto =
        UcbBanditDto(armCount, totalPlays, plays.copyOf(), rewards.copyOf(), minReward, maxReward, strategy)

    companion object {
        fun withArms(armCount: Int, minReward: Double = 0.0, maxReward: Double = 1.0): UcbBandit {
            require(armCount > 0) { "armCount must be positive" }
            require(maxReward > minReward) { "maxReward must be greater than minReward" }

            return UcbBandit(armCount, 0, LongArray(armCount), DoubleArray(armCount), minReward, maxReward)
        }

        fun fromState(state: UcbBanditState): UcbBandit =
            UcbBandit(
                state.plays.size,
                state.totalPlays,
                state.plays,
                state.rewards,
                state.minReward,
                state.maxReward,
                state.strategy,
            )

        fun fromDto(dto: UcbBanditDto): UcbBandit =
            UcbBandit(
                dto.armCount,
                dto.totalPlays,
                dto.plays,
                dto.rewards,
                dto.minReward,
                dto.maxReward,
                dto.strategy,
            )
    }

    enum class Strategy {
        UCB1,
        UCB_TUNED,
    }
}

data class ArmStats(
    val index: Int,
    val plays: Long,
    val averageReward: Double,
)

/**
 * Snapshot of a bandit's state for JSON serialization.
 */
data class UcbBanditState(
    val totalPlays: Long,
    val plays: LongArray,
    val rewards: DoubleArray,
    val minReward: Double = 0.0,
    val maxReward: Double = 1.0,
    val strategy: UcbBandit.Strategy = UcbBandit.Strategy.UCB1,
)

data class UcbBanditDto(
    val armCount: Int,
    val totalPlays: Long,
    val plays: LongArray,
    val rewards: DoubleArray,
    val minReward: Double,
    val maxReward: Double,
    val strategy: UcbBandit.Strategy = UcbBandit.Strategy.UCB1,
)
