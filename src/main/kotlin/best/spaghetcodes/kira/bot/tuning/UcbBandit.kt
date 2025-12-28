package best.spaghetcodes.kira.bot.tuning

import java.util.Random
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Multi-armed bandit implementation with multiple strategy options:
 * - UCB1: Classic Upper Confidence Bound algorithm
 * - UCB_TUNED: UCB with variance estimation for better bounds
 * - THOMPSON: Thompson Sampling using Beta distribution (best for binary rewards)
 * - EPSILON_GREEDY: Simple epsilon-greedy exploration
 *
 * This class is immutable - all update operations return new instances.
 */
class UcbBandit(
    val armCount: Int,
    val totalPlays: Long,
    plays: LongArray,
    rewards: DoubleArray,
    val minReward: Double = 0.0,
    val maxReward: Double = 1.0,
    val strategy: Strategy = Strategy.UCB1,
    val explorationFactor: Double = 2.0,
) {
    private val plays: LongArray = plays.copyOf()
    private val rewards: DoubleArray = rewards.copyOf()

    // For Thompson Sampling - track successes and failures per arm
    private val successes: DoubleArray
    private val failures: DoubleArray

    init {
        require(armCount > 0) { "armCount must be positive" }
        require(this.plays.size == armCount) { "plays size must match armCount" }
        require(this.rewards.size == armCount) { "rewards size must match armCount" }
        require(maxReward > minReward) { "maxReward must be greater than minReward" }
        require(explorationFactor > 0) { "explorationFactor must be positive" }

        // Initialize Thompson Sampling parameters from rewards
        successes = DoubleArray(armCount) { i ->
            if (this.plays[i] > 0) this.rewards[i] else 1.0
        }
        failures = DoubleArray(armCount) { i ->
            if (this.plays[i] > 0) max(0.0, this.plays[i] - this.rewards[i]) else 1.0
        }
    }

    /**
     * Selects the next arm to play using the configured strategy.
     */
    fun selectArm(): Int {
        // First, ensure each arm is tried at least once
        for (i in 0 until armCount) {
            if (plays[i] == 0L) return i
        }

        return when (strategy) {
            Strategy.UCB1 -> selectArmUcb1()
            Strategy.UCB_TUNED -> selectArmUcbTuned()
            Strategy.THOMPSON -> selectArmThompson()
            Strategy.EPSILON_GREEDY -> selectArmEpsilonGreedy()
        }
    }

    private fun selectArmUcb1(): Int {
        val t = max(totalPlays.toDouble(), 1.0)
        val logN = ln(t)
        var bestArm = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (i in 0 until armCount) {
            val averageReward = rewards[i] / plays[i]
            val explorationBonus = sqrt(explorationFactor * logN / plays[i])
            val score = averageReward + explorationBonus

            if (score > bestScore) {
                bestScore = score
                bestArm = i
            }
        }

        return bestArm
    }

    private fun selectArmUcbTuned(): Int {
        val t = max(totalPlays.toDouble(), 1.0)
        val logN = ln(t)
        var bestArm = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (i in 0 until armCount) {
            val averageReward = rewards[i] / plays[i]
            val variance = averageReward * (1 - averageReward)
            val v = variance + sqrt(2.0 * logN / plays[i])
            val bonus = sqrt((logN / plays[i]) * min(0.25, v))
            val score = averageReward + bonus

            if (score > bestScore) {
                bestScore = score
                bestArm = i
            }
        }

        return bestArm
    }

    private fun selectArmThompson(): Int {
        val random = Random()
        var bestArm = 0
        var bestSample = Double.NEGATIVE_INFINITY

        for (i in 0 until armCount) {
            // Sample from Beta distribution using successes and failures as parameters
            val alpha = successes[i] + 1.0
            val beta = failures[i] + 1.0
            val sample = sampleBeta(random, alpha, beta)

            if (sample > bestSample) {
                bestSample = sample
                bestArm = i
            }
        }

        return bestArm
    }

    private fun selectArmEpsilonGreedy(): Int {
        val epsilon = max(0.05, 1.0 / (1 + totalPlays / 100.0))
        val random = Random()

        return if (random.nextDouble() < epsilon) {
            // Explore: random arm
            random.nextInt(armCount)
        } else {
            // Exploit: best known arm
            bestArmIndex()
        }
    }

    /**
     * Sample from Beta distribution using the gamma distribution method.
     */
    private fun sampleBeta(random: Random, alpha: Double, beta: Double): Double {
        val x = sampleGamma(random, alpha)
        val y = sampleGamma(random, beta)
        return if (x + y > 0) x / (x + y) else 0.5
    }

    /**
     * Sample from Gamma distribution using Marsaglia and Tsang's method.
     */
    private fun sampleGamma(random: Random, shape: Double): Double {
        if (shape < 1.0) {
            return sampleGamma(random, shape + 1.0) * Math.pow(random.nextDouble(), 1.0 / shape)
        }

        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)

        while (true) {
            var x: Double
            var v: Double
            do {
                x = random.nextGaussian()
                v = 1.0 + c * x
            } while (v <= 0)

            v = v * v * v
            val u = random.nextDouble()
            val x2 = x * x

            if (u < 1.0 - 0.0331 * x2 * x2) {
                return d * v
            }

            if (ln(u) < 0.5 * x2 + d * (1.0 - v + ln(v))) {
                return d * v
            }
        }
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

        return UcbBandit(
            armCount,
            totalPlays + 1,
            updatedPlays,
            updatedRewards,
            minReward,
            maxReward,
            strategy,
            explorationFactor
        )
    }

    /**
     * Returns a new bandit with a different exploration factor.
     */
    fun withExplorationFactor(factor: Double): UcbBandit {
        return UcbBandit(
            armCount,
            totalPlays,
            plays.copyOf(),
            rewards.copyOf(),
            minReward,
            maxReward,
            strategy,
            factor
        )
    }

    /**
     * Returns a new bandit with a different strategy.
     */
    fun withStrategy(newStrategy: Strategy): UcbBandit {
        return UcbBandit(
            armCount,
            totalPlays,
            plays.copyOf(),
            rewards.copyOf(),
            minReward,
            maxReward,
            newStrategy,
            explorationFactor
        )
    }

    /**
     * Get the confidence interval for an arm's reward estimate.
     * Returns (lower, upper) bounds.
     */
    fun confidenceInterval(arm: Int, confidence: Double = 0.95): Pair<Double, Double> {
        require(arm in 0 until armCount) { "arm index out of bounds" }
        if (plays[arm] == 0L) return Pair(0.0, 1.0)

        val mean = rewards[arm] / plays[arm]
        val n = plays[arm].toDouble()

        // Using normal approximation for large n
        val z = when {
            confidence >= 0.99 -> 2.576
            confidence >= 0.95 -> 1.96
            confidence >= 0.90 -> 1.645
            else -> 1.28
        }

        val margin = z * sqrt(mean * (1 - mean) / n)
        return Pair(
            (mean - margin).coerceIn(0.0, 1.0),
            (mean + margin).coerceIn(0.0, 1.0)
        )
    }

    /**
     * Returns the "regret" - difference between optimal arm's reward and this arm's reward.
     */
    fun regret(arm: Int): Double {
        if (plays[arm] == 0L) return 0.0
        val bestAvg = armStats().firstOrNull()?.averageReward ?: 0.0
        val thisAvg = rewards[arm] / plays[arm]
        return (bestAvg - thisAvg).coerceAtLeast(0.0)
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
            val scaled = (originalPlays * factor).roundToLong()
            val ensured = max(1L, scaled)
            decayedPlays[i] = ensured
            decayedTotal += ensured
        }

        val decayedRewards = DoubleArray(armCount) { rewards[it] * factor }

        return UcbBandit(
            armCount,
            decayedTotal,
            decayedPlays,
            decayedRewards,
            minReward,
            maxReward,
            strategy,
            explorationFactor
        )
    }

    /**
     * Returns a "soft reset" bandit that keeps some memory of past performance.
     * Useful when game mechanics change but we want to retain some learned knowledge.
     */
    fun softReset(retentionFactor: Double = 0.3): UcbBandit {
        require(retentionFactor in 0.0..1.0) { "retentionFactor must be in [0, 1]" }

        if (retentionFactor == 0.0) {
            return UcbBandit.withArms(armCount, minReward, maxReward)
                .withStrategy(strategy)
                .withExplorationFactor(explorationFactor)
        }

        return decay(retentionFactor)
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
        UcbBanditState(totalPlays, plays.copyOf(), rewards.copyOf(), minReward, maxReward, strategy, explorationFactor)

    fun toDto(): UcbBanditDto =
        UcbBanditDto(armCount, totalPlays, plays.copyOf(), rewards.copyOf(), minReward, maxReward, strategy, explorationFactor)

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
                state.explorationFactor,
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
                dto.explorationFactor,
            )
    }

    /**
     * Available bandit strategies.
     */
    enum class Strategy {
        /** Classic Upper Confidence Bound - balanced exploration/exploitation */
        UCB1,
        /** UCB with variance estimation - better bounds for uncertain arms */
        UCB_TUNED,
        /** Thompson Sampling - probabilistic, excellent for binary rewards */
        THOMPSON,
        /** Epsilon-greedy with decaying epsilon - simple but effective */
        EPSILON_GREEDY,
    }
}

/**
 * Statistics for a single arm.
 */
data class ArmStats(
    val index: Int,
    val plays: Long,
    val averageReward: Double,
) {
    val explored: Boolean get() = plays > 0
}

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
    val explorationFactor: Double = 2.0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UcbBanditState) return false
        return totalPlays == other.totalPlays &&
            plays.contentEquals(other.plays) &&
            rewards.contentEquals(other.rewards) &&
            minReward == other.minReward &&
            maxReward == other.maxReward &&
            strategy == other.strategy &&
            explorationFactor == other.explorationFactor
    }

    override fun hashCode(): Int {
        var result = totalPlays.hashCode()
        result = 31 * result + plays.contentHashCode()
        result = 31 * result + rewards.contentHashCode()
        result = 31 * result + minReward.hashCode()
        result = 31 * result + maxReward.hashCode()
        result = 31 * result + strategy.hashCode()
        result = 31 * result + explorationFactor.hashCode()
        return result
    }
}

/**
 * Data transfer object for bandit state.
 */
data class UcbBanditDto(
    val armCount: Int,
    val totalPlays: Long,
    val plays: LongArray,
    val rewards: DoubleArray,
    val minReward: Double,
    val maxReward: Double,
    val strategy: UcbBandit.Strategy = UcbBandit.Strategy.UCB1,
    val explorationFactor: Double = 2.0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UcbBanditDto) return false
        return armCount == other.armCount &&
            totalPlays == other.totalPlays &&
            plays.contentEquals(other.plays) &&
            rewards.contentEquals(other.rewards) &&
            minReward == other.minReward &&
            maxReward == other.maxReward &&
            strategy == other.strategy &&
            explorationFactor == other.explorationFactor
    }

    override fun hashCode(): Int {
        var result = armCount
        result = 31 * result + totalPlays.hashCode()
        result = 31 * result + plays.contentHashCode()
        result = 31 * result + rewards.contentHashCode()
        result = 31 * result + minReward.hashCode()
        result = 31 * result + maxReward.hashCode()
        result = 31 * result + strategy.hashCode()
        result = 31 * result + explorationFactor.hashCode()
        return result
    }
}
