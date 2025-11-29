package best.spaghetcodes.kira.bot.tuning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UcbBanditTest {

    @Test
    fun `exploration selects each arm at least once`() {
        var bandit = UcbBandit.withArms(armCount = 3)

        repeat(9) {
            val arm = bandit.selectArm()
            bandit = bandit.update(arm, reward = 1.0)
        }

        val dto = bandit.toDto()
        assertEquals(UcbBandit.Strategy.UCB1, dto.strategy)
        dto.plays.forEachIndexed { index, plays ->
            assertTrue(plays > 0, "Arm $index should have been explored at least once")
        }
    }

    @Test
    fun `bandit prefers higher reward arm`() {
        var bandit = UcbBandit.withArms(armCount = 2)

        repeat(100) {
            val arm = bandit.selectArm()
            val reward = if (arm == 0) 0.2 else 0.8
            bandit = bandit.update(arm, reward)
        }

        val dto = bandit.toDto()
        assertTrue(dto.plays[1] > dto.plays[0], "Arm with higher reward should be selected more often")
    }

    @Test
    fun `reward normalization clamps within bounds`() {
        val bandit = UcbBandit.withArms(armCount = 2, minReward = -1.0, maxReward = 1.0)

        assertEquals(0.0, bandit.normalizeReward(-1.0), 1e-9)
        assertEquals(1.0, bandit.normalizeReward(1.0), 1e-9)
        assertEquals(0.5, bandit.normalizeReward(0.0), 1e-9)
        assertEquals(0.0, bandit.normalizeReward(-2.0), 1e-9)
        assertEquals(1.0, bandit.normalizeReward(2.0), 1e-9)
    }

    @Test
    fun `dto round trip preserves state`() {
        var bandit = UcbBandit.withArms(armCount = 2, minReward = -0.5, maxReward = 2.0)

        bandit = bandit.update(0, reward = 0.3)
        bandit = bandit.update(1, reward = 1.2)
        bandit = bandit.update(1, reward = 0.8)

        val dto = bandit.toDto()
        val restored = UcbBandit.fromDto(dto)
        val restoredDto = restored.toDto()

        assertEquals(dto.armCount, restoredDto.armCount)
        assertEquals(dto.totalPlays, restoredDto.totalPlays)
        assertTrue(dto.plays.contentEquals(restoredDto.plays))
        assertTrue(dto.rewards.contentEquals(restoredDto.rewards))
        assertEquals(dto.minReward, restoredDto.minReward)
        assertEquals(dto.maxReward, restoredDto.maxReward)
        assertEquals(dto.strategy, restoredDto.strategy)
    }

    @Test
    fun `ucb tuned favors better arm`() {
        var bandit = UcbBandit(
            armCount = 2,
            totalPlays = 0,
            plays = LongArray(2),
            rewards = DoubleArray(2),
            strategy = UcbBandit.Strategy.UCB_TUNED,
        )

        repeat(200) {
            val arm = bandit.selectArm()
            val reward = if (arm == 0) 0.2 else 0.8
            bandit = bandit.update(arm, reward)
        }

        val dto = bandit.toDto()
        assertTrue(dto.plays[1] > dto.plays[0], "UCB_TUNED should favor the higher reward arm")
    }

    @Test
    fun `armStats exposes averages for each arm`() {
        val bandit = UcbBandit(
            armCount = 3,
            totalPlays = 6,
            plays = longArrayOf(2, 1, 3),
            rewards = doubleArrayOf(1.0, 0.5, 2.4),
        )

        val stats = bandit.armStats()

        assertEquals(3, stats.size)
        assertEquals(listOf(2, 0, 1), stats.map { it.index })

        val statsByIndex = stats.associateBy { it.index }
        val arm0 = statsByIndex.getValue(0)
        val arm1 = statsByIndex.getValue(1)
        val arm2 = statsByIndex.getValue(2)

        assertEquals(2L, arm0.plays)
        assertEquals(1L, arm1.plays)
        assertEquals(3L, arm2.plays)
        assertEquals(0.5, arm0.averageReward, 1e-9)
        assertEquals(0.5, arm1.averageReward, 1e-9)
        assertEquals(0.8, arm2.averageReward, 1e-9)
    }

    @Test
    fun `bestArmIndex returns highest average arm`() {
        var bandit = UcbBandit.withArms(armCount = 3)

        bandit = bandit.update(0, reward = 0.4)
        bandit = bandit.update(1, reward = 0.5)
        bandit = bandit.update(2, reward = 0.9)
        bandit = bandit.update(2, reward = 0.8)

        assertEquals(2, bandit.bestArmIndex())
    }
}
