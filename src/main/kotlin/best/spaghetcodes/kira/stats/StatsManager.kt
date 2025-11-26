package best.spaghetcodes.kira.stats

import best.spaghetcodes.kira.kira
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

object StatsManager {

    const val GLOBAL_CATEGORY = "Global"
    const val SESSION_CATEGORY = "Session"

    val MODE_CATEGORIES = listOf("Classic", "ClassicV2", "OP", "Combo", "Sumo", "Boxing", "Bow", "Blitz")
    val DISPLAY_CATEGORIES = listOf(GLOBAL_CATEGORY, SESSION_CATEGORY) + MODE_CATEGORIES

    data class StatsSnapshot(val wins: Int = 0, val losses: Int = 0) {
        val games: Int
            get() = wins + losses

        fun withResult(win: Boolean): StatsSnapshot {
            return if (win) copy(wins = wins + 1) else copy(losses = losses + 1)
        }
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val statsFile = File(kira.kiraConfigDir, "stats.json")

    private val globalStats: MutableMap<String, StatsSnapshot> = mutableMapOf()
    private val sessionStats: MutableMap<String, StatsSnapshot> = mutableMapOf()

    init {
        load()
    }

    private fun load() {
        try {
            statsFile.parentFile?.mkdirs()
            if (!statsFile.exists()) return

            val type = object : TypeToken<Map<String, StatsSnapshot>>() {}.type
            val loaded: Map<String, StatsSnapshot>? = statsFile.reader().use { gson.fromJson(it, type) }
            if (loaded != null) {
                globalStats.putAll(loaded)
            }
        } catch (e: Exception) {
            println("Failed to load stats: ${e.message}")
        }
    }

    private fun save() {
        try {
            statsFile.parentFile?.mkdirs()
            statsFile.writeText(gson.toJson(globalStats))
        } catch (e: Exception) {
            println("Failed to save stats: ${e.message}")
        }
    }

    private fun mutate(map: MutableMap<String, StatsSnapshot>, category: String, win: Boolean) {
        val current = map[category] ?: StatsSnapshot()
        map[category] = current.withResult(win)
    }

    fun recordResult(win: Boolean, category: String) {
        synchronized(this) {
            mutate(sessionStats, GLOBAL_CATEGORY, win)
            mutate(sessionStats, category, win)

            mutate(globalStats, GLOBAL_CATEGORY, win)
            mutate(globalStats, category, win)
            save()
        }
    }

    fun getSessionStats(category: String): StatsSnapshot {
        return sessionStats[category] ?: StatsSnapshot()
    }

    fun getGlobalStats(category: String): StatsSnapshot {
        return globalStats[category] ?: StatsSnapshot()
    }

    fun getStatsForDisplay(category: String): StatsSnapshot {
        return when (category) {
            SESSION_CATEGORY -> getSessionStats(GLOBAL_CATEGORY)
            GLOBAL_CATEGORY -> getGlobalStats(GLOBAL_CATEGORY)
            else -> getGlobalStats(category)
        }
    }
}
