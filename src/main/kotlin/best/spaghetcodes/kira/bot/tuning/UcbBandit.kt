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
    rewards: DoubleArray
) {
    private val plays: LongArray = plays.copyOf()
    private val rewards: DoubleArray = rewards.copyOf()

    init {
        require(armCount > 0) { "armCount must be positive" }
        require(this.plays.size == armCount) { "plays size must match armCount" }
        require(this.rewards.size == armCount) { "rewards size must match armCount" }
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

        return UcbBandit(armCount, totalPlays + 1, updatedPlays, updatedRewards)
    }

    fun toState(): UcbBanditState = UcbBanditState(totalPlays, plays.copyOf(), rewards.copyOf())

    companion object {
        fun fromState(state: UcbBanditState): UcbBandit =
            UcbBandit(state.plays.size, state.totalPlays, state.plays, state.rewards)
    }
}

/**
 * Snapshot of a bandit's state for JSON serialization.
 */
data class UcbBanditState(
    val totalPlays: Long,
    val plays: LongArray,
    val rewards: DoubleArray
)
