package best.spaghetcodes.kira.utils

import net.minecraft.client.Minecraft
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

object TimeUtils {

    class TaskHandle internal constructor(private val future: ScheduledFuture<*>) {
        fun cancel() {
            future.cancel(false)
        }

        fun isCancelled(): Boolean = future.isCancelled
    }

    private val scheduler = ScheduledThreadPoolExecutor(1, ThreadFactory { runnable ->
        Thread(runnable, "Kira-ClientScheduler").apply {
            isDaemon = true
        }
    }).apply {
        removeOnCancelPolicy = true
    }

    private fun runOnClientThread(function: () -> Unit) {
        try {
            val mc = Minecraft.getMinecraft()
            if (mc.isCallingFromMinecraftThread) {
                try {
                    function()
                } catch (t: Throwable) {
                    println("Error executing scheduled task: ${t.message}")
                }
            } else {
                mc.addScheduledTask {
                    try {
                        function()
                    } catch (t: Throwable) {
                        println("Error executing scheduled task: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            println("Error executing scheduled task: ${t.message}")
        }
    }

    private fun schedule(function: () -> Unit, delayMs: Long, intervalMs: Long?): TaskHandle? {
        val safeDelay = delayMs.coerceAtLeast(0)
        val runnable = Runnable {
            runOnClientThread(function)
        }

        return try {
            val future = if (intervalMs != null) {
                val safeInterval = intervalMs.coerceAtLeast(1)
                scheduler.scheduleAtFixedRate(runnable, safeDelay, safeInterval, TimeUnit.MILLISECONDS)
            } else {
                scheduler.schedule(runnable, safeDelay, TimeUnit.MILLISECONDS)
            }
            TaskHandle(future)
        } catch (t: Throwable) {
            println("Error scheduling task with delay ${safeDelay}ms${intervalMs?.let { " and interval ${it.coerceAtLeast(1)}ms" } ?: ""}: ${t.message}")
            null
        }
    }

    /**
     * Call a function after delay ms
     */
    fun setTimeout(function: () -> Unit, delay: Int): TaskHandle? {
        return schedule(function, delay.toLong(), null)
    }

    /**
     * Call a function every interval ms after delay ms
     */
    fun setInterval(function: () -> Unit, delay: Int, interval: Int): TaskHandle? {
        return schedule(function, delay.toLong(), interval.toLong())
    }
}
