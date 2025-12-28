package best.spaghetcodes.kira.bot.tuning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class UcbBanditTest {

    // ========================== UCB1 TESTS ==========================

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
        assertEquals(dto.explorationFactor, restoredDto.explorationFactor)
    }

    // ========================== UCB_TUNED TESTS ==========================

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

    // ========================== THOMPSON SAMPLING TESTS ==========================

    @Test
    fun `thompson sampling explores all arms initially`() {
        var bandit = UcbBandit(
            armCount = 4,
            totalPlays = 0,
            plays = LongArray(4),
            rewards = DoubleArray(4),
            strategy = UcbBandit.Strategy.THOMPSON,
        )

        repeat(12) {
            val arm = bandit.selectArm()
            bandit = bandit.update(arm, reward = 0.5)
        }

        val dto = bandit.toDto()
        dto.plays.forEachIndexed { index, plays ->
            assertTrue(plays > 0, "Arm $index should have been explored at least once")
        }
    }

    @Test
    fun `thompson sampling favors higher reward arm over time`() {
        var bandit = UcbBandit(
            armCount = 2,
            totalPlays = 0,
            plays = LongArray(2),
            rewards = DoubleArray(2),
            strategy = UcbBandit.Strategy.THOMPSON,
        )

        repeat(200) {
            val arm = bandit.selectArm()
            val reward = if (arm == 0) 0.2 else 0.9
            bandit = bandit.update(arm, reward)
        }

        val dto = bandit.toDto()
        assertTrue(dto.plays[1] > dto.plays[0], "Thompson sampling should favor the higher reward arm")
    }

    // ========================== EPSILON-GREEDY TESTS ==========================

    @Test
    fun `epsilon greedy explores initially then exploits`() {
        var bandit = UcbBandit(
            armCount = 3,
            totalPlays = 0,
            plays = LongArray(3),
            rewards = DoubleArray(3),
            strategy = UcbBandit.Strategy.EPSILON_GREEDY,
        )

        // Initial exploration phase
        repeat(9) {
            val arm = bandit.selectArm()
            bandit = bandit.update(arm, reward = if (arm == 2) 1.0 else 0.2)
        }

        val dto = bandit.toDto()
        dto.plays.forEachIndexed { index, plays ->
            assertTrue(plays > 0, "Arm $index should have been explored")
        }
    }

    @Test
    fun `epsilon greedy converges to best arm`() {
        var bandit = UcbBandit(
            armCount = 2,
            totalPlays = 0,
            plays = LongArray(2),
            rewards = DoubleArray(2),
            strategy = UcbBandit.Strategy.EPSILON_GREEDY,
        )

        repeat(300) {
            val arm = bandit.selectArm()
            val reward = if (arm == 1) 0.9 else 0.1
            bandit = bandit.update(arm, reward)
        }

        val dto = bandit.toDto()
        assertTrue(dto.plays[1] > dto.plays[0] * 2, "Epsilon-greedy should strongly favor the better arm")
    }

    // ========================== STATISTICS TESTS ==========================

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

    @Test
    fun `ArmStats explored property works correctly`() {
        val exploredArm = ArmStats(index = 0, plays = 5, averageReward = 0.6)
        val unexploredArm = ArmStats(index = 1, plays = 0, averageReward = 0.0)

        assertTrue(exploredArm.explored)
        assertTrue(!unexploredArm.explored)
    }

    // ========================== DECAY TESTS ==========================

    @Test
    fun `decay reduces plays and rewards proportionally`() {
        var bandit = UcbBandit(
            armCount = 2,
            totalPlays = 100,
            plays = longArrayOf(60, 40),
            rewards = doubleArrayOf(30.0, 32.0),
        )

        val decayed = bandit.decay(0.5)
        val dto = decayed.toDto()

        assertTrue(dto.plays[0] < 60)
        assertTrue(dto.plays[1] < 40)
        assertTrue(dto.rewards[0] < 30.0)
        assertTrue(dto.rewards[1] < 32.0)
    }

    @Test
    fun `decay preserves relative arm rankings`() {
        val bandit = UcbBandit(
            armCount = 2,
            totalPlays = 100,
            plays = longArrayOf(50, 50),
            rewards = doubleArrayOf(25.0, 40.0),
        )

        val decayed = bandit.decay(0.8)

        assertEquals(bandit.bestArmIndex(), decayed.bestArmIndex())
    }

    @Test
    fun `softReset with 0 retention creates fresh bandit`() {
        val bandit = UcbBandit(
            armCount = 3,
            totalPlays = 100,
            plays = longArrayOf(30, 40, 30),
            rewards = doubleArrayOf(15.0, 30.0, 20.0),
        )

        val reset = bandit.softReset(retentionFactor = 0.0)

        assertEquals(0L, reset.totalPlays)
        assertTrue(reset.toDto().plays.all { it == 0L })
    }

    // ========================== CONFIDENCE INTERVAL TESTS ==========================

    @Test
    fun `confidenceInterval returns full range for unexplored arm`() {
        val bandit = UcbBandit.withArms(armCount = 2)
        val (lower, upper) = bandit.confidenceInterval(0)

        assertEquals(0.0, lower)
        assertEquals(1.0, upper)
    }

    @Test
    fun `confidenceInterval narrows with more plays`() {
        val fewPlays = UcbBandit(
            armCount = 1,
            totalPlays = 10,
            plays = longArrayOf(10),
            rewards = doubleArrayOf(5.0),
        )

        val manyPlays = UcbBandit(
            armCount = 1,
            totalPlays = 100,
            plays = longArrayOf(100),
            rewards = doubleArrayOf(50.0),
        )

        val (lower1, upper1) = fewPlays.confidenceInterval(0)
        val (lower2, upper2) = manyPlays.confidenceInterval(0)

        val width1 = upper1 - lower1
        val width2 = upper2 - lower2

        assertTrue(width2 < width1, "More plays should narrow the confidence interval")
    }

    // ========================== REGRET TESTS ==========================

    @Test
    fun `regret is zero for best arm`() {
        val bandit = UcbBandit(
            armCount = 2,
            totalPlays = 10,
            plays = longArrayOf(5, 5),
            rewards = doubleArrayOf(2.0, 4.0),
        )

        assertEquals(0.0, bandit.regret(1), 0.001)
    }

    @Test
    fun `regret is positive for suboptimal arm`() {
        val bandit = UcbBandit(
            armCount = 2,
            totalPlays = 10,
            plays = longArrayOf(5, 5),
            rewards = doubleArrayOf(2.0, 4.0),
        )

        assertTrue(bandit.regret(0) > 0)
    }

    // ========================== STRATEGY SWITCHING TESTS ==========================

    @Test
    fun `withStrategy creates bandit with new strategy`() {
        val ucb1 = UcbBandit.withArms(armCount = 2)
        val thompson = ucb1.withStrategy(UcbBandit.Strategy.THOMPSON)

        assertEquals(UcbBandit.Strategy.UCB1, ucb1.strategy)
        assertEquals(UcbBandit.Strategy.THOMPSON, thompson.strategy)
    }

    @Test
    fun `withExplorationFactor creates bandit with new factor`() {
        val standard = UcbBandit.withArms(armCount = 2)
        val aggressive = standard.withExplorationFactor(4.0)
        val conservative = standard.withExplorationFactor(0.5)

        assertEquals(2.0, standard.explorationFactor)
        assertEquals(4.0, aggressive.explorationFactor)
        assertEquals(0.5, conservative.explorationFactor)
    }

    // ========================== STATE EQUALITY TESTS ==========================

    @Test
    fun `UcbBanditState equals works correctly`() {
        val state1 = UcbBanditState(
            totalPlays = 10,
            plays = longArrayOf(5, 5),
            rewards = doubleArrayOf(3.0, 4.0),
        )
        val state2 = UcbBanditState(
            totalPlays = 10,
            plays = longArrayOf(5, 5),
            rewards = doubleArrayOf(3.0, 4.0),
        )
        val state3 = UcbBanditState(
            totalPlays = 10,
            plays = longArrayOf(5, 5),
            rewards = doubleArrayOf(3.0, 5.0),
        )

        assertEquals(state1, state2)
        assertNotEquals(state1, state3)
    }

    @Test
    fun `UcbBanditDto equals works correctly`() {
        val dto1 = UcbBanditDto(
            armCount = 2,
            totalPlays = 10,
            plays = longArrayOf(5, 5),
            rewards = doubleArrayOf(3.0, 4.0),
            minReward = 0.0,
            maxReward = 1.0,
        )
        val dto2 = UcbBanditDto(
            armCount = 2,
            totalPlays = 10,
            plays = longArrayOf(5, 5),
            rewards = doubleArrayOf(3.0, 4.0),
            minReward = 0.0,
            maxReward = 1.0,
        )

        assertEquals(dto1, dto2)
        assertEquals(dto1.hashCode(), dto2.hashCode())
    }
}
