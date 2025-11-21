package best.spaghetcodes.kira.bot.tuning

import kotlin.math.ln
import kotlin.math.max
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
    val maxReward: Double = 1.0
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
     * Selects the next arm to play using the standard UCB1 policy.
     */
    fun selectArm(): Int {
        for (i in 0 until armCount) {
            if (plays[i] == 0L) return i
        }

        val t = max(totalPlays.toDouble(), 1.0)
        var bestArm = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (i in 0 until armCount) {
            val averageReward = rewards[i] / plays[i]
            val explorationBonus = sqrt(2.0 * ln(t) / plays[i])
            val score = averageReward + explorationBonus

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

        return UcbBandit(armCount, totalPlays + 1, updatedPlays, updatedRewards, minReward, maxReward)
    }

    fun normalizeReward(reward: Double): Double {
        val normalized = (reward - minReward) / (maxReward - minReward)
        return normalized.coerceIn(0.0, 1.0)
    }

    fun toState(): UcbBanditState = UcbBanditState(totalPlays, plays.copyOf(), rewards.copyOf(), minReward, maxReward)

    fun toDto(): UcbBanditDto =
        UcbBanditDto(armCount, totalPlays, plays.copyOf(), rewards.copyOf(), minReward, maxReward)

    companion object {
        fun withArms(armCount: Int, minReward: Double = 0.0, maxReward: Double = 1.0): UcbBandit {
            require(armCount > 0) { "armCount must be positive" }
            require(maxReward > minReward) { "maxReward must be greater than minReward" }

            return UcbBandit(armCount, 0, LongArray(armCount), DoubleArray(armCount), minReward, maxReward)
        }

        fun fromState(state: UcbBanditState): UcbBandit =
            UcbBandit(state.plays.size, state.totalPlays, state.plays, state.rewards, state.minReward, state.maxReward)

        fun fromDto(dto: UcbBanditDto): UcbBandit =
            UcbBandit(dto.armCount, dto.totalPlays, dto.plays, dto.rewards, dto.minReward, dto.maxReward)
    }
}

/**
 * Snapshot of a bandit's state for JSON serialization.
 */
data class UcbBanditState(
    val totalPlays: Long,
    val plays: LongArray,
    val rewards: DoubleArray,
    val minReward: Double = 0.0,
    val maxReward: Double = 1.0
)

data class UcbBanditDto(
    val armCount: Int,
    val totalPlays: Long,
    val plays: LongArray,
    val rewards: DoubleArray,
    val minReward: Double,
    val maxReward: Double
)
