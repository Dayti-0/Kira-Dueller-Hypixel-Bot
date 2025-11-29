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
        val low = kotlin.math.min(min, max)
        val high = kotlin.math.max(min, max)

        val boundExclusive = if (high == Int.MAX_VALUE) {
            high.toLong() + 1
        } else {
            (high + 1).toLong()
        }

        return ThreadLocalRandom.current()
            .nextLong(low.toLong(), boundExclusive)
            .toInt()
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