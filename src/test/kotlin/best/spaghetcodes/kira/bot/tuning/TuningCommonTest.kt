package best.spaghetcodes.kira.bot.tuning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuningCommonTest {

    @Test
    fun `MistakeSummary calculates total mistakes correctly`() {
        val summary = MistakeSummary(
            mistakesJump = 2,
            mistakesRod = 3,
            mistakesBow = 1,
            rodHits = 5,
            rodMisses = 2,
            bowShots = 4
        )

        assertEquals(6, summary.totalMistakes)
        assertEquals(7, summary.rodAttempts)
    }

    @Test
    fun `MistakeSummary calculates rod accuracy correctly`() {
        val summary = MistakeSummary(
            mistakesJump = 0,
            mistakesRod = 0,
            mistakesBow = 0,
            rodHits = 7,
            rodMisses = 3,
            bowShots = 0
        )

        assertEquals(0.7, summary.rodAccuracy()!!, 0.001)
    }

    @Test
    fun `MistakeSummary returns null for rod accuracy with no attempts`() {
        val summary = MistakeSummary.ZERO
        assertEquals(null, summary.rodAccuracy())
    }

    @Test
    fun `MistakeSummary quality score is 1 for zero mistakes`() {
        val summary = MistakeSummary.ZERO
        assertEquals(1.0, summary.qualityScore(), 0.001)
    }

    @Test
    fun `MistakeSummary quality score decreases with mistakes`() {
        val noMistakes = MistakeSummary.ZERO
        val someMistakes = MistakeSummary(2, 1, 1, 0, 0, 0)
        val manyMistakes = MistakeSummary(5, 5, 5, 0, 0, 0)

        assertTrue(noMistakes.qualityScore() > someMistakes.qualityScore())
        assertTrue(someMistakes.qualityScore() > manyMistakes.qualityScore())
    }

    @Test
    fun `computeReward returns higher reward for wins`() {
        val mistakes = MistakeSummary.ZERO
        val winReward = computeReward(win = true, mistakes = mistakes)
        val lossReward = computeReward(win = false, mistakes = mistakes)

        assertTrue(winReward > lossReward)
    }

    @Test
    fun `computeReward returns lower reward for more mistakes`() {
        val cleanMistakes = MistakeSummary.ZERO
        val dirtyMistakes = MistakeSummary(3, 3, 3, 0, 0, 0)

        val cleanReward = computeReward(win = true, mistakes = cleanMistakes)
        val dirtyReward = computeReward(win = true, mistakes = dirtyMistakes)

        assertTrue(cleanReward > dirtyReward)
    }

    @Test
    fun `computeReward is bounded between 0 and 1`() {
        val extremeMistakes = MistakeSummary(100, 100, 100, 0, 0, 0)

        val winReward = computeReward(win = true, mistakes = extremeMistakes)
        val lossReward = computeReward(win = false, mistakes = extremeMistakes)

        assertTrue(winReward in 0.0..1.0)
        assertTrue(lossReward in 0.0..1.0)
    }

    @Test
    fun `computeReward gives rod accuracy bonus`() {
        val noRod = MistakeSummary(0, 0, 0, 0, 0, 0)
        val goodRod = MistakeSummary(0, 0, 0, 10, 2, 0)
        val badRod = MistakeSummary(0, 0, 0, 2, 10, 0)

        val noRodReward = computeReward(win = true, mistakes = noRod)
        val goodRodReward = computeReward(win = true, mistakes = goodRod)
        val badRodReward = computeReward(win = true, mistakes = badRod)

        assertTrue(goodRodReward > noRodReward)
        assertTrue(goodRodReward > badRodReward)
    }

    @Test
    fun `MatchContext damage efficiency calculation`() {
        val balanced = MatchContext(damageDealt = 10f, damageReceived = 10f)
        val dominant = MatchContext(damageDealt = 15f, damageReceived = 5f)
        val dominated = MatchContext(damageDealt = 5f, damageReceived = 15f)

        assertEquals(0.5, balanced.damageEfficiency(), 0.01)
        assertEquals(0.75, dominant.damageEfficiency(), 0.01)
        assertEquals(0.25, dominated.damageEfficiency(), 0.01)
    }

    @Test
    fun `MatchContext quick match detection`() {
        val quickMatch = MatchContext(durationMs = 20_000L)
        val normalMatch = MatchContext(durationMs = 60_000L)
        val longMatch = MatchContext(durationMs = 200_000L)

        assertTrue(quickMatch.isQuickMatch())
        assertTrue(!normalMatch.isQuickMatch())
        assertTrue(!longMatch.isQuickMatch())
    }

    @Test
    fun `computeRewardAdvanced gives combo bonus`() {
        val noCombo = MatchContext(maxCombo = 0)
        val goodCombo = MatchContext(maxCombo = 6)

        val noComboReward = computeRewardAdvanced(true, MistakeSummary.ZERO, noCombo)
        val goodComboReward = computeRewardAdvanced(true, MistakeSummary.ZERO, goodCombo)

        assertTrue(goodComboReward > noComboReward)
    }

    @Test
    fun `computeRewardAdvanced adjusts for opponent skill`() {
        val weakOpponent = MatchContext(opponentSkillFactor = 0.7)
        val strongOpponent = MatchContext(opponentSkillFactor = 1.3)

        val weakReward = computeRewardAdvanced(true, MistakeSummary.ZERO, weakOpponent)
        val strongReward = computeRewardAdvanced(true, MistakeSummary.ZERO, strongOpponent)

        assertTrue(strongReward > weakReward)
    }

    @Test
    fun `computeRewardAdvanced gives quick win bonus`() {
        val quickWin = MatchContext(durationMs = 15_000L)
        val slowWin = MatchContext(durationMs = 120_000L)

        val quickReward = computeRewardAdvanced(true, MistakeSummary.ZERO, quickWin)
        val slowReward = computeRewardAdvanced(true, MistakeSummary.ZERO, slowWin)

        assertTrue(quickReward > slowReward)
    }

    @Test
    fun `computeRewardWithBreakdown sums correctly`() {
        val breakdown = computeRewardWithBreakdown(
            win = true,
            mistakes = MistakeSummary(1, 1, 0, 5, 2, 3),
            context = MatchContext(maxCombo = 4, durationMs = 60_000L)
        )

        val manualSum = breakdown.outcomeBase + breakdown.winBonus + breakdown.rodBonus +
            breakdown.bowBonus + breakdown.cleanPlayBonus + breakdown.cleanLossBonus +
            breakdown.comboBonus + breakdown.damageBonus + breakdown.quickWinBonus

        assertEquals(breakdown.total, (manualSum * breakdown.opponentModifier).coerceIn(0.0, 1.0), 0.001)
    }

    @Test
    fun `estimateOpponentSkill returns correct values for win rates`() {
        val strongPlayer = estimateOpponentSkill(opponentWins = 80, opponentLosses = 20)
        val averagePlayer = estimateOpponentSkill(opponentWins = 50, opponentLosses = 50)
        val weakPlayer = estimateOpponentSkill(opponentWins = 20, opponentLosses = 80)

        assertTrue(strongPlayer > averagePlayer)
        assertTrue(averagePlayer > weakPlayer)
    }

    @Test
    fun `estimateOpponentSkill uses known rating when provided`() {
        val highRated = estimateOpponentSkill(0, 0, opponentKnownRating = 1600.0)
        val lowRated = estimateOpponentSkill(0, 0, opponentKnownRating = 500.0)

        assertTrue(highRated > 1.0)
        assertTrue(lowRated < 1.0)
    }

    @Test
    fun `exponentialMovingAverage blends values correctly`() {
        val result = exponentialMovingAverage(current = 1.0, previous = 0.0, alpha = 0.5)
        assertEquals(0.5, result, 0.001)

        val result2 = exponentialMovingAverage(current = 1.0, previous = 0.0, alpha = 0.1)
        assertEquals(0.1, result2, 0.001)
    }
}
