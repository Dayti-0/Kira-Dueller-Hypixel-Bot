package best.spaghetcodes.kira.monitor

import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.kira
import com.google.gson.annotations.SerializedName
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.apache.logging.log4j.LogManager
import java.io.File

object RemoteMonitor {

    private const val WRITE_INTERVAL_MS = 500L
    private val logger = LogManager.getLogger("Kira")

    private val monitorDir = File(kira.mc.mcDataDir, "kira")
    private val statusFile = File(monitorDir, "kira_status.json")
    private val tempFile = File(monitorDir, "kira_status.tmp")

    private var lastWriteAt = 0L
    private var dirty = false

    fun init() {
        MinecraftForge.EVENT_BUS.register(this)
    }

    fun markDirty() {
        dirty = true
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (kira.config?.remoteMonitoringEnabled != true) return

        val now = System.currentTimeMillis()
        if (!dirty && now - lastWriteAt < WRITE_INTERVAL_MS) return

        val status = buildStatus(now)
        val json = kira.gson.toJson(status)

        writeStatus(json)
        lastWriteAt = now
        dirty = false
    }

    private fun buildStatus(now: Long): RemoteStatus {
        val existing = readExistingPayload()
        val bot = kira.bot
        val opponent = bot?.opponent()
        val opponentName = opponent?.gameProfile?.name ?: bot?.opponentName()

        return RemoteStatus(
            timestamp = now / 1000,
            botEnabled = bot?.toggled() == true,
            currentMode = bot?.getName(),
            phase = resolvePhase(),
            opponent = OpponentStatus(
                name = opponentName,
                wlr = null
            ),
            wins = Session.wins,
            losses = Session.losses,
            scheduler = RemoteControlScheduler.getSchedulerStatus(now),
            commands = RemoteControlScheduler.currentCommands(existing?.commands) ?: existing?.commands,
            history = GameHistory.snapshot()
        )
    }

    internal fun readExistingPayload(): RemoteStatus? {
        return try {
            if (!statusFile.exists()) return null
            statusFile.reader().use { reader ->
                kira.gson.fromJson(reader, RemoteStatus::class.java)
            }
        } catch (e: Exception) {
            logger.debug("Failed to read existing remote status", e)
            null
        }
    }

    private fun resolvePhase(): String {
        return when (StateManager.state) {
            StateManager.States.LOBBY -> "LOBBY"
            StateManager.States.GAME -> "QUEUING"
            StateManager.States.PLAYING -> "IN_GAME"
        }
    }

    private fun writeStatus(payload: String) {
        try {
            monitorDir.mkdirs()
            tempFile.writeText(payload)
            if (!tempFile.renameTo(statusFile)) {
                statusFile.writeText(payload)
            }
        } catch (e: Exception) {
            logger.error("Failed to write remote monitoring status", e)
        }
    }

    data class RemoteStatus(
        val timestamp: Long,
        val botEnabled: Boolean,
        val currentMode: String?,
        val phase: String,
        val opponent: OpponentStatus,
        val wins: Int?,
        val losses: Int?,
        val scheduler: SchedulerStatus?,
        val commands: RemoteCommands?,
        val history: HistorySnapshot?
    )

    data class OpponentStatus(
        val name: String?,
        @SerializedName("wlr") val wlr: Double?
    )
}
