package best.spaghetcodes.kira.bot.tuning

import kotlin.test.Test
import kotlin.test.assertEquals

class UcbBanditTest {

    @Test
    fun `swapped reward bounds are corrected`() {
        val bandit = UcbBandit.withArms(2, minReward = 1.0, maxReward = -1.0)

        assertEquals(-1.0, bandit.minReward)
        assertEquals(1.0, bandit.maxReward)
        assertEquals(0.5, bandit.normalizeReward(0.0))
    }

    @Test
    fun `identical reward bounds get widened`() {
        val bandit = UcbBandit.withArms(1, minReward = 2.0, maxReward = 2.0)

        assertEquals(2.0, bandit.minReward)
        assertEquals(3.0, bandit.maxReward)
        assertEquals(0.0, bandit.normalizeReward(2.0))
        assertEquals(1.0, bandit.normalizeReward(3.0))
    }
}
