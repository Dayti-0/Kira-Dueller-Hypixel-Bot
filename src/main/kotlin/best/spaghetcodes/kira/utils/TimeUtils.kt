package best.spaghetcodes.kira.utils

import net.minecraft.client.Minecraft
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object TimeUtils {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    class Task internal constructor(private val future: ScheduledFuture<*>) {
        fun cancel() {
            future.cancel(false)
        }
    }

    /**
     * Call a function after delay ms
     */
    fun setTimeout(function: () -> Unit, delay: Int): Task? {
        return try {
            val future = scheduler.schedule({
                Minecraft.getMinecraft().addScheduledTask(function)
            }, delay.toLong(), TimeUnit.MILLISECONDS)
            Task(future)
        } catch (e: Exception) {
            println("Error scheduling timeout with ${delay}ms: " + e.message)
            null
        }
    }

    /**
     * Call a function every interval ms after delay ms
     */
    fun setInterval(function: () -> Unit, delay: Int, interval: Int): Task? {
        return try {
            val future = scheduler.scheduleAtFixedRate({
                Minecraft.getMinecraft().addScheduledTask(function)
            }, delay.toLong(), interval.toLong(), TimeUnit.MILLISECONDS)
            Task(future)
        } catch (e: Exception) {
            println(
                "Error scheduling interval with ${delay}ms delay and ${interval}ms interval: " + e.message
            )
            null
        }
    }
}
