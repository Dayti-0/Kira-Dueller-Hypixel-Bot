package best.spaghetcodes.kira.utils

import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.concurrent.CopyOnWriteArrayList

object TimeUtils {

    private class ScheduledTask(var nextRun: Long, val interval: Long?, val function: () -> Unit)

    private val tasks = CopyOnWriteArrayList<ScheduledTask>()

    class Task internal constructor(private val scheduled: ScheduledTask) {
        fun cancel() {
            tasks.remove(scheduled)
        }
    }

    /**
     * Call a function after delay ms
     */
    fun setTimeout(function: () -> Unit, delay: Int): Task? {
        val task = ScheduledTask(System.currentTimeMillis() + delay, null, function)
        tasks.add(task)
        return Task(task)
    }

    /**
     * Call a function every interval ms after delay ms
     */
    fun setInterval(function: () -> Unit, delay: Int, interval: Int): Task? {
        val task = ScheduledTask(System.currentTimeMillis() + delay, interval.toLong(), function)
        tasks.add(task)
        return Task(task)
    }

    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val now = System.currentTimeMillis()
        for (task in tasks) {
            if (task.nextRun <= now) {
                Minecraft.getMinecraft().addScheduledTask(task.function)
                if (task.interval != null) {
                    task.nextRun = now + task.interval
                } else {
                    tasks.remove(task)
                }
            }
        }
    }
}
