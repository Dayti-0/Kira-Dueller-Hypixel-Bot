package best.spaghetcodes.kira.bot.tuning

private const val JUMP_MISTAKE_WEIGHT = 1.0
private const val ROD_MISTAKE_WEIGHT = 1.2
private const val BOW_MISTAKE_WEIGHT = 0.8
private const val MISTAKE_NORMALIZER = 8.0
private const val CLEAN_LOSS_BONUS_THRESHOLD = 0.55
private const val CLEAN_LOSS_BONUS_MAX = 0.12
private const val BASE_WIN_REWARD = 0.65
private const val BASE_LOSS_REWARD = 0.35
private const val WIN_EXTRA_BONUS = 0.25

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

/**
 * Compute a reward in [0.0, 1.0] that reflects outcome, mistakes, and simple positive signals.
 */
fun computeReward(win: Boolean, mistakes: MistakeSummary): Double {
    val qualityFactor = mistakes.qualityScore()

    val outcomeBase = if (win) BASE_WIN_REWARD else BASE_LOSS_REWARD
    val winBonus = if (win) WIN_EXTRA_BONUS * qualityFactor else 0.0
    val rodBonus = mistakes.rodAccuracy()?.let {
        val attemptsWeight = (mistakes.rodAttempts / 4.0).coerceIn(0.0, 1.0)
        it * attemptsWeight * 0.2 * qualityFactor
    } ?: 0.0
    val bowDisciplineBonus = if (mistakes.mistakesBow == 0 && mistakes.bowShots > 0) 0.05 * qualityFactor else 0.0
    val cleanPlayBonus = if (mistakes.totalMistakes == 0) 0.08 else 0.0
    val cleanLossBonus = if (!win && qualityFactor >= CLEAN_LOSS_BONUS_THRESHOLD) {
        (qualityFactor - CLEAN_LOSS_BONUS_THRESHOLD) * (CLEAN_LOSS_BONUS_MAX / (1.0 - CLEAN_LOSS_BONUS_THRESHOLD))
    } else 0.0

    return (outcomeBase * qualityFactor + winBonus + rodBonus + bowDisciplineBonus + cleanPlayBonus + cleanLossBonus)
        .coerceIn(0.0, 1.0)
}
