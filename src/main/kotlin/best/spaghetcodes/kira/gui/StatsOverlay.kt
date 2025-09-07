package best.spaghetcodes.kira.gui

import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.kira
import net.minecraft.client.gui.Gui
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import java.text.DecimalFormat

class StatsOverlay {

    private val df = DecimalFormat("#.##")

    @SubscribeEvent
    fun onRender(@Suppress("UNUSED_PARAMETER") event: RenderGameOverlayEvent.Text) {
        if (kira.config?.showStatsOverlay == false) return

        val mc = kira.mc
        val wins = Session.wins
        val losses = Session.losses
        val total = wins + losses

        val ratio = if (losses == 0) wins.toFloat() else wins.toFloat() / losses
        val winrate = if (total == 0) 0f else (wins.toFloat() / total) * 100f

        val elapsed = if (Session.startTime > 0) System.currentTimeMillis() - Session.startTime else 0L
        val hours = elapsed / 1000.0 / 3600.0
        val winsPerHour = if (hours > 0) wins / hours else 0.0
        val minutes = elapsed / 1000 / 60

        val lines = mutableListOf(
            "Wins: $wins",
            "Losses: $losses",
            "W/L: ${df.format(ratio)}",
            "Winrate: ${df.format(winrate)}%"
        )

        if (elapsed > 0) {
            lines += "Wins/h: ${df.format(winsPerHour)}"
            lines += "Time: ${minutes}m"
        }

        val fr = mc.fontRendererObj
        var maxWidth = 0
        for (s in lines) {
            maxWidth = maxWidth.coerceAtLeast(fr.getStringWidth(s))
        }

        val x = 4
        val y = 4
        val lineHeight = fr.FONT_HEIGHT + 2
        val bgColor = 0x55000000.toInt()
        Gui.drawRect(x - 2, y - 2, x + maxWidth + 2, y + lineHeight * lines.size, bgColor)

        lines.forEachIndexed { index, s ->
            fr.drawString(s, x, y + index * lineHeight, 0xFFFFFF)
        }
    }
}

