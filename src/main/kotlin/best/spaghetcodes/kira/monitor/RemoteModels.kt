package best.spaghetcodes.kira.monitor

data class RemoteCommands(
    val botEnabled: Boolean? = null,
    val switchMode: String? = null,
    val pauseAutoQueue: Boolean? = null,
    val startAfterSeconds: Long? = null,
    val stopAfterGames: Int? = null,
    val stopAfterSeconds: Long? = null,
    val plan: RemotePlanCommand? = null
)

data class RemotePlanCommand(
    val id: String? = null,
    val active: Boolean? = null,
    val loop: Boolean? = null,
    val startAfterSeconds: Long? = null,
    val steps: List<RemotePlanStep>? = null
)

data class RemotePlanStep(
    val type: String,
    val mode: String? = null,
    val games: Int? = null,
    val durationSeconds: Long? = null
)

data class SchedulerStatus(
    val planActive: Boolean,
    val planId: String?,
    val looping: Boolean,
    val currentStepIndex: Int?,
    val currentStepType: String?,
    val currentStepMode: String?,
    val currentStepTargetGames: Int?,
    val currentStepCompletedGames: Int?,
    val currentStepTargetDuration: Long?,
    val currentStepRemainingDuration: Long?,
    val totalPlanGames: Int,
    val stopAfterGamesRemaining: Int?,
    val stopAfterSecondsRemaining: Long?,
    val autoQueuePaused: Boolean,
    val pendingStartAt: Long?
)

data class GameHistoryEntry(
    val timestamp: Long,
    val mode: String,
    val win: Boolean
)

data class ModeTotals(val wins: Int = 0, val losses: Int = 0) {
    val games: Int
        get() = wins + losses
}

data class HistorySnapshot(
    val recentGames: List<GameHistoryEntry>,
    val perModeTotals: Map<String, ModeTotals>
)

