package best.spaghetcodes.kira.utils

import kotlin.test.Test
import kotlin.test.assertTrue

class RandomUtilsTest {

    @Test
    fun `supports swapped bounds`() {
        repeat(25) {
            val value = RandomUtils.randomIntInRange(10, 4)
            assertTrue(value in 4..10, "Expected value within 4..10 but was $value")
        }
    }

    @Test
    fun `handles max int upper bound`() {
        repeat(25) {
            val value = RandomUtils.randomIntInRange(Int.MAX_VALUE - 1, Int.MAX_VALUE)
            assertTrue(value in (Int.MAX_VALUE - 1)..Int.MAX_VALUE, "Expected value near Int.MAX_VALUE but was $value")
        }
    }
}
