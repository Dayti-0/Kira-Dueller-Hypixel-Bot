package best.spaghetcodes.kira.utils

import java.util.*
import java.util.concurrent.ThreadLocalRandom

object RandomUtils {

    /**
     * Get a random integer in a certain range
     * @param min
     * @param max
     * @return int
     */
    fun randomIntInRange(min: Int, max: Int): Int {
        if (min == max) return min

        val low = min.coerceAtMost(max)
        val highExclusive = min.coerceAtLeast(max).toLong() + 1

        return if (highExclusive > Int.MAX_VALUE) {
            ThreadLocalRandom.current().nextLong(low.toLong(), highExclusive).toInt()
        } else {
            ThreadLocalRandom.current().nextInt(low, highExclusive.toInt())
        }
    }

    /**
     * Get a random double in a certain range
     * @param min
     * @param max
     * @return double
     */
    fun randomDoubleInRange(min: Double, max: Double): Double {
        val r = Random()
        return min + (max - min) * r.nextDouble()
    }

    /**
     * Get a random boolean value
     * @return bool
     */
    fun randomBool(): Boolean {
        val r = Random()
        return r.nextBoolean()
    }

}