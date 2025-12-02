package best.spaghetcodes.kira.monitor

import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.kira
import com.google.gson.annotations.SerializedName
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.common.MinecraftForge
import org.apache.logging.log4j.LogManager
import java.io.File

object RemoteMonitor {

    private const val WRITE_INTERVAL_MS = 500L
    private val logger = LogManager.getLogger("Kira")

    private val statusFile = File(kira.mc.mcDataDir, "kira_status.json")
    private val tempFile = File(kira.mc.mcDataDir, "kira_status.tmp")

    private var lastWriteAt = 0L

    fun init() {
        MinecraftForge.EVENT_BUS.register(this)
    }

    fun markDirty() {
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (kira.config?.remoteMonitoringEnabled != true) return

        val now = System.currentTimeMillis()
        if (now - lastWriteAt < WRITE_INTERVAL_MS) return

        val status = buildStatus(now)
        val json = kira.gson.toJson(status)

        writeStatus(json)
        lastWriteAt = now
    }

    private fun buildStatus(now: Long): RemoteStatus {
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
            losses = Session.losses
        )
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
            statusFile.parentFile?.mkdirs()
            tempFile.parentFile?.mkdirs()
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
        val losses: Int?
    )

    data class OpponentStatus(
        val name: String?,
        @SerializedName("wlr") val wlr: Double?
    )
}
