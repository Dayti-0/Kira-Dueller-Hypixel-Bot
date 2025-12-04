package best.spaghetcodes.kira.monitor

import java.util.ArrayDeque

object GameHistory {

    private const val MAX_ENTRIES = 500

    private val entries = ArrayDeque<GameHistoryEntry>()

    fun recordGame(mode: String, win: Boolean) {
        val entry = GameHistoryEntry(System.currentTimeMillis() / 1000, mode, win)
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
        RemoteMonitor.markDirty()
    }

    fun snapshot(): HistorySnapshot {
        val copy = synchronized(entries) { entries.toList() }
        val perMode = mutableMapOf<String, ModeTotals>()
        copy.forEach { entry ->
            val totals = perMode.getOrPut(entry.mode) { ModeTotals() }
            perMode[entry.mode] = if (entry.win) {
                totals.copy(wins = totals.wins + 1)
            } else {
                totals.copy(losses = totals.losses + 1)
            }
        }

        return HistorySnapshot(copy, perMode)
    }

    fun totalsForMode(mode: String): ModeTotals {
        val copy = synchronized(entries) { entries.toList() }
        val totals = copy.filter { it.mode.equals(mode, ignoreCase = true) }
            .fold(ModeTotals()) { acc, entry ->
                if (entry.win) acc.copy(wins = acc.wins + 1) else acc.copy(losses = acc.losses + 1)
            }
        return totals
    }
}

