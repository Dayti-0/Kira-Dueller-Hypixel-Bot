package best.spaghetcodes.kira.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomUtilsTest {

    @Test
    fun `randomIntInRange tolerates inverted bounds`() {
        repeat(50) {
            val value = RandomUtils.randomIntInRange(10, 5)
            assertTrue(value in 5..10, "value $value should fall in swapped range")
        }
    }

    @Test
    fun `randomIntInRange supports single-value ranges at max int`() {
        repeat(10) {
            val value = RandomUtils.randomIntInRange(Int.MAX_VALUE, Int.MAX_VALUE)
            assertEquals(Int.MAX_VALUE, value)
        }
    }

    @Test
    fun `randomIntInRange covers high-end bounds safely`() {
        var sawMax = false
        var sawMaxMinusOne = false

        repeat(200) {
            val value = RandomUtils.randomIntInRange(Int.MAX_VALUE - 1, Int.MAX_VALUE)
            if (value == Int.MAX_VALUE) sawMax = true
            if (value == Int.MAX_VALUE - 1) sawMaxMinusOne = true
            assertTrue(value in (Int.MAX_VALUE - 1)..Int.MAX_VALUE)
        }

        assertTrue(sawMax && sawMaxMinusOne, "should be able to hit both endpoints")
    }
}
