package best.spaghetcodes.kira.bot

import best.spaghetcodes.kira.stats.StatsManager
import net.minecraft.util.EnumChatFormatting
import java.math.RoundingMode
import java.text.DecimalFormat

object Session {

    val wins: Int
        get() = StatsManager.getSessionStats(StatsManager.GLOBAL_CATEGORY).wins

    val losses: Int
        get() = StatsManager.getSessionStats(StatsManager.GLOBAL_CATEGORY).losses

    private var activityWindowStartedAt: Long = -1
    private var accumulatedActivityMs: Long = 0

    private var botEnabled = false
    private var botState: StateManager.States = StateManager.States.LOBBY

    fun getSession(): String {
        val df = DecimalFormat("#.##")
        df.roundingMode = RoundingMode.DOWN
        val ratio = df.format(wins.toFloat() / (if (losses == 0) 1F else losses.toFloat()))
        return "Session: ${EnumChatFormatting.GREEN}Wins: $wins${EnumChatFormatting.RESET} - ${EnumChatFormatting.RED}Losses: $losses${EnumChatFormatting.RESET} - W/L: ${EnumChatFormatting.LIGHT_PURPLE}${ratio}${EnumChatFormatting.RESET}"
    }

    fun recordResult(win: Boolean, category: String) {
        StatsManager.recordResult(win, category)
    }

    fun updateBotEnabled(enabled: Boolean, now: Long = System.currentTimeMillis()) {
        if (botEnabled == enabled) {
            if (!enabled) {
                pauseActivity(now)
            }
            return
        }

        botEnabled = enabled
        refreshActivity(now)
    }

    fun updateBotState(state: StateManager.States, now: Long = System.currentTimeMillis()) {
        if (botState == state) {
            if (botEnabled) {
                refreshActivity(now)
            }
            return
        }

        botState = state
        refreshActivity(now)
    }

    private fun refreshActivity(now: Long) {
        val shouldTrack = botEnabled && (botState == StateManager.States.GAME || botState == StateManager.States.PLAYING)
        setActivityActive(shouldTrack, now)
    }

    private fun setActivityActive(active: Boolean, now: Long) {
        if (active) {
            startActivity(now)
        } else {
            pauseActivity(now)
        }
    }

    private fun startActivity(now: Long) {
        if (activityWindowStartedAt <= 0L) {
            activityWindowStartedAt = now
        }
    }

    private fun pauseActivity(now: Long) {
        if (activityWindowStartedAt > 0L) {
            accumulatedActivityMs += now - activityWindowStartedAt
            activityWindowStartedAt = -1
        }
    }

    fun getActiveDurationMs(now: Long = System.currentTimeMillis()): Long {
        var total = accumulatedActivityMs
        if (activityWindowStartedAt > 0L) {
            total += now - activityWindowStartedAt
        }
        return total
    }

    fun hasActiveTime(): Boolean {
        return accumulatedActivityMs > 0L || activityWindowStartedAt > 0L
    }

    fun resetActivity() {
        activityWindowStartedAt = -1
        accumulatedActivityMs = 0
    }

}
