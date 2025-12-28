package best.spaghetcodes.kira.bot.tuning

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// ========================== REWARD WEIGHTS ==========================

private const val JUMP_MISTAKE_WEIGHT = 1.0
private const val ROD_MISTAKE_WEIGHT = 1.2
private const val BOW_MISTAKE_WEIGHT = 0.8
private const val MISTAKE_NORMALIZER = 8.0

// Base reward constants
private const val BASE_WIN_REWARD = 0.60
private const val BASE_LOSS_REWARD = 0.30
private const val WIN_BONUS_MAX = 0.25

// Quality bonuses
private const val CLEAN_PLAY_BONUS = 0.08
private const val CLEAN_LOSS_BONUS_THRESHOLD = 0.55
private const val CLEAN_LOSS_BONUS_MAX = 0.12

// Performance bonuses
private const val ROD_ACCURACY_BONUS_MAX = 0.15
private const val BOW_DISCIPLINE_BONUS = 0.05
private const val COMBO_BONUS_MAX = 0.10
private const val DAMAGE_EFFICIENCY_BONUS_MAX = 0.08
private const val QUICK_WIN_BONUS_MAX = 0.07

// Opponent context weights
private const val STRONGER_OPPONENT_MULTIPLIER = 1.15
private const val WEAKER_OPPONENT_MULTIPLIER = 0.90

// ========================== MISTAKE SUMMARY ==========================

/**
 * Shared mistake summary used to assess match quality and compute tuning rewards.
 */
data class MistakeSummary(
    val mistakesJump: Int,
    val mistakesRod: Int,
    val mistakesBow: Int,
    val rodHits: Int,
    val rodMisses: Int,
    val bowShots: Int,
) {
    val totalMistakes: Int
        get() = mistakesJump + mistakesRod + mistakesBow

    val rodAttempts: Int
        get() = rodHits + rodMisses

    fun rodAccuracy(): Double? {
        val attempts = rodAttempts
        if (attempts == 0) return null
        return rodHits.toDouble() / attempts
    }

    fun weightedPenalty(): Double =
        mistakesJump * JUMP_MISTAKE_WEIGHT + mistakesRod * ROD_MISTAKE_WEIGHT + mistakesBow * BOW_MISTAKE_WEIGHT

    fun normalizedPenalty(): Double = (weightedPenalty() / MISTAKE_NORMALIZER).coerceIn(0.0, 1.5)

    fun qualityScore(): Double = (1.0 - normalizedPenalty()).coerceIn(0.0, 1.0)

    companion object {
        val ZERO = MistakeSummary(0, 0, 0, 0, 0, 0)
    }
}

// ========================== MATCH CONTEXT ==========================

/**
 * Additional match context for more sophisticated reward calculation.
 */
data class MatchContext(
    /** Match duration in milliseconds */
    val durationMs: Long = 0L,
    /** Damage dealt to opponent */
    val damageDealt: Float = 0f,
    /** Damage received from opponent */
    val damageReceived: Float = 0f,
    /** Maximum combo achieved */
    val maxCombo: Int = 0,
    /** Opponent's estimated skill level (0.0 = unknown, >1.0 = stronger, <1.0 = weaker) */
    val opponentSkillFactor: Double = 1.0,
    /** Number of times we controlled the pace */
    val paceControlCount: Int = 0,
    /** Bow arrows hit */
    val bowHits: Int = 0,
    /** Bow arrows shot */
    val bowAttempts: Int = 0,
    /** Did we have health advantage at end? */
    val hadHealthAdvantage: Boolean = false,
) {
    companion object {
        val EMPTY = MatchContext()

        /** Expected match duration for different modes (ms) */
        const val SHORT_MATCH_THRESHOLD = 30_000L
        const val NORMAL_MATCH_THRESHOLD = 90_000L
        const val LONG_MATCH_THRESHOLD = 180_000L
    }

    fun damageEfficiency(): Double {
        val total = damageDealt + damageReceived
        if (total <= 0) return 0.5
        return (damageDealt / total).coerceIn(0.0, 1.0).toDouble()
    }

    fun bowAccuracy(): Double? {
        if (bowAttempts == 0) return null
        return bowHits.toDouble() / bowAttempts
    }

    fun isQuickMatch(): Boolean = durationMs in 1 until SHORT_MATCH_THRESHOLD

    fun isNormalDuration(): Boolean = durationMs in SHORT_MATCH_THRESHOLD until LONG_MATCH_THRESHOLD
}

// ========================== REWARD COMPUTATION ==========================

/**
 * Compute a reward in [0.0, 1.0] that reflects outcome, mistakes, and simple positive signals.
 * This is the legacy function for backward compatibility.
 */
fun computeReward(win: Boolean, mistakes: MistakeSummary): Double {
    return computeRewardAdvanced(win, mistakes, MatchContext.EMPTY)
}

/**
 * Advanced reward computation with full match context.
 * Returns a value in [0.0, 1.0] that reflects:
 * - Match outcome (win/loss)
 * - Quality of play (mistakes made)
 * - Performance metrics (accuracy, combos, damage)
 * - Match context (duration, opponent skill)
 */
fun computeRewardAdvanced(
    win: Boolean,
    mistakes: MistakeSummary,
    context: MatchContext,
): Double {
    val qualityFactor = mistakes.qualityScore()

    // 1. Base reward from outcome
    val outcomeBase = if (win) BASE_WIN_REWARD else BASE_LOSS_REWARD

    // 2. Win quality bonus (better play = more reward)
    val winBonus = if (win) {
        WIN_BONUS_MAX * qualityFactor
    } else 0.0

    // 3. Rod accuracy bonus
    val rodBonus = mistakes.rodAccuracy()?.let { accuracy ->
        val attemptsWeight = min(1.0, mistakes.rodAttempts / 5.0)
        accuracy * attemptsWeight * ROD_ACCURACY_BONUS_MAX * qualityFactor
    } ?: 0.0

    // 4. Bow discipline bonus
    val bowDisciplineBonus = if (mistakes.mistakesBow == 0 && mistakes.bowShots > 0) {
        BOW_DISCIPLINE_BONUS * qualityFactor
    } else 0.0

    // 5. Clean play bonus (no mistakes at all)
    val cleanPlayBonus = if (mistakes.totalMistakes == 0) CLEAN_PLAY_BONUS else 0.0

    // 6. Clean loss bonus (played well but lost)
    val cleanLossBonus = if (!win && qualityFactor >= CLEAN_LOSS_BONUS_THRESHOLD) {
        val factor = (qualityFactor - CLEAN_LOSS_BONUS_THRESHOLD) /
            (1.0 - CLEAN_LOSS_BONUS_THRESHOLD)
        factor * CLEAN_LOSS_BONUS_MAX
    } else 0.0

    // 7. Combo bonus (good combos indicate effective play)
    val comboBonus = if (context.maxCombo > 0) {
        val comboScore = min(1.0, context.maxCombo / 8.0)
        comboScore * COMBO_BONUS_MAX * qualityFactor
    } else 0.0

    // 8. Damage efficiency bonus
    val damageBonus = if (context.damageDealt > 0 || context.damageReceived > 0) {
        val efficiency = context.damageEfficiency()
        (efficiency - 0.5) * 2.0 * DAMAGE_EFFICIENCY_BONUS_MAX * qualityFactor
    } else 0.0

    // 9. Quick win bonus (efficient victories)
    val quickWinBonus = if (win && context.isQuickMatch()) {
        QUICK_WIN_BONUS_MAX * qualityFactor
    } else 0.0

    // Sum all components
    var reward = outcomeBase * qualityFactor +
        winBonus +
        rodBonus +
        bowDisciplineBonus +
        cleanPlayBonus +
        cleanLossBonus +
        comboBonus +
        damageBonus +
        quickWinBonus

    // Apply opponent skill modifier
    reward *= when {
        context.opponentSkillFactor > 1.2 -> STRONGER_OPPONENT_MULTIPLIER
        context.opponentSkillFactor < 0.8 -> WEAKER_OPPONENT_MULTIPLIER
        else -> 1.0
    }

    return reward.coerceIn(0.0, 1.0)
}

// ========================== REWARD ANALYSIS ==========================

/**
 * Breakdown of reward components for debugging and analysis.
 */
data class RewardBreakdown(
    val total: Double,
    val outcomeBase: Double,
    val qualityFactor: Double,
    val winBonus: Double,
    val rodBonus: Double,
    val bowBonus: Double,
    val cleanPlayBonus: Double,
    val cleanLossBonus: Double,
    val comboBonus: Double,
    val damageBonus: Double,
    val quickWinBonus: Double,
    val opponentModifier: Double,
)

/**
 * Compute reward with detailed breakdown for analysis.
 */
fun computeRewardWithBreakdown(
    win: Boolean,
    mistakes: MistakeSummary,
    context: MatchContext = MatchContext.EMPTY,
): RewardBreakdown {
    val qualityFactor = mistakes.qualityScore()
    val outcomeBase = if (win) BASE_WIN_REWARD else BASE_LOSS_REWARD

    val winBonus = if (win) WIN_BONUS_MAX * qualityFactor else 0.0

    val rodBonus = mistakes.rodAccuracy()?.let { accuracy ->
        val attemptsWeight = min(1.0, mistakes.rodAttempts / 5.0)
        accuracy * attemptsWeight * ROD_ACCURACY_BONUS_MAX * qualityFactor
    } ?: 0.0

    val bowBonus = if (mistakes.mistakesBow == 0 && mistakes.bowShots > 0) {
        BOW_DISCIPLINE_BONUS * qualityFactor
    } else 0.0

    val cleanPlayBonus = if (mistakes.totalMistakes == 0) CLEAN_PLAY_BONUS else 0.0

    val cleanLossBonus = if (!win && qualityFactor >= CLEAN_LOSS_BONUS_THRESHOLD) {
        val factor = (qualityFactor - CLEAN_LOSS_BONUS_THRESHOLD) / (1.0 - CLEAN_LOSS_BONUS_THRESHOLD)
        factor * CLEAN_LOSS_BONUS_MAX
    } else 0.0

    val comboBonus = if (context.maxCombo > 0) {
        min(1.0, context.maxCombo / 8.0) * COMBO_BONUS_MAX * qualityFactor
    } else 0.0

    val damageBonus = if (context.damageDealt > 0 || context.damageReceived > 0) {
        (context.damageEfficiency() - 0.5) * 2.0 * DAMAGE_EFFICIENCY_BONUS_MAX * qualityFactor
    } else 0.0

    val quickWinBonus = if (win && context.isQuickMatch()) {
        QUICK_WIN_BONUS_MAX * qualityFactor
    } else 0.0

    val opponentModifier = when {
        context.opponentSkillFactor > 1.2 -> STRONGER_OPPONENT_MULTIPLIER
        context.opponentSkillFactor < 0.8 -> WEAKER_OPPONENT_MULTIPLIER
        else -> 1.0
    }

    val rawTotal = outcomeBase * qualityFactor +
        winBonus + rodBonus + bowBonus + cleanPlayBonus +
        cleanLossBonus + comboBonus + damageBonus + quickWinBonus

    val total = (rawTotal * opponentModifier).coerceIn(0.0, 1.0)

    return RewardBreakdown(
        total = total,
        outcomeBase = outcomeBase * qualityFactor,
        qualityFactor = qualityFactor,
        winBonus = winBonus,
        rodBonus = rodBonus,
        bowBonus = bowBonus,
        cleanPlayBonus = cleanPlayBonus,
        cleanLossBonus = cleanLossBonus,
        comboBonus = comboBonus,
        damageBonus = damageBonus,
        quickWinBonus = quickWinBonus,
        opponentModifier = opponentModifier,
    )
}

// ========================== UTILITY FUNCTIONS ==========================

/**
 * Estimate opponent skill from match history.
 * Returns a factor where 1.0 = average, >1.0 = stronger, <1.0 = weaker.
 */
fun estimateOpponentSkill(
    opponentWins: Int,
    opponentLosses: Int,
    opponentKnownRating: Double? = null,
): Double {
    // If we have a known rating, use it
    opponentKnownRating?.let { rating ->
        return when {
            rating > 1500 -> 1.3
            rating > 1200 -> 1.1
            rating > 900 -> 1.0
            rating > 600 -> 0.9
            else -> 0.8
        }
    }

    // Otherwise estimate from win/loss
    val total = opponentWins + opponentLosses
    if (total < 5) return 1.0 // Not enough data

    val winRate = opponentWins.toDouble() / total
    return when {
        winRate > 0.7 -> 1.25
        winRate > 0.55 -> 1.1
        winRate > 0.45 -> 1.0
        winRate > 0.3 -> 0.9
        else -> 0.8
    }
}

/**
 * Calculate a smoothed moving average for tracking performance over time.
 */
fun exponentialMovingAverage(
    current: Double,
    previous: Double,
    alpha: Double = 0.1,
): Double {
    return alpha * current + (1 - alpha) * previous
}
