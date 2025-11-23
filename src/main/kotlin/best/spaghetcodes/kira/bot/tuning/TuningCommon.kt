package best.spaghetcodes.kira.bot.tuning

/**
 * Shared tuning utilities between ClassicV2 runtime and its tuner.
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

    fun weightedPenalty(): Double {
        return mistakesJump * JUMP_MISTAKE_WEIGHT + mistakesRod * ROD_MISTAKE_WEIGHT + mistakesBow * BOW_MISTAKE_WEIGHT
    }

    companion object {
        val ZERO = MistakeSummary(0, 0, 0, 0, 0, 0)
    }
}

private const val JUMP_MISTAKE_WEIGHT = 1.0
private const val ROD_MISTAKE_WEIGHT = 1.2
private const val BOW_MISTAKE_WEIGHT = 0.8
private const val MISTAKE_NORMALIZER = 6.0

fun computeReward(win: Boolean, mistakes: MistakeSummary): Double {
    val penaltyNormalized = (mistakes.weightedPenalty() / MISTAKE_NORMALIZER).coerceIn(0.0, 1.0)
    val qualityFactor = 1.0 - penaltyNormalized

    val base = if (win) 0.85 else 0.4
    val rodBonus = mistakes.rodAccuracy()?.let { it * 0.1 * qualityFactor } ?: 0.0
    val bowDisciplineBonus = if (mistakes.mistakesBow == 0 && mistakes.bowShots > 0) 0.05 * qualityFactor else 0.0
    val cleanPlayBonus = if (mistakes.totalMistakes == 0) 0.05 else 0.0
    val outcomeBonus = (if (win) 0.15 else 0.05) * qualityFactor

    return (base * qualityFactor + rodBonus + bowDisciplineBonus + cleanPlayBonus + outcomeBonus)
        .coerceIn(0.0, 1.0)
}
