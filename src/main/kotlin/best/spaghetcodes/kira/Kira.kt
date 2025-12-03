package best.spaghetcodes.kira

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.bot.bots.Sumo
import best.spaghetcodes.kira.bot.player.LobbyMovement
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.commands.ConfigCommand
import best.spaghetcodes.kira.core.CameraController
import best.spaghetcodes.kira.core.Config
import best.spaghetcodes.kira.core.KeyBindings
import best.spaghetcodes.kira.gui.StatsOverlay
import best.spaghetcodes.kira.events.packet.PacketListener
import best.spaghetcodes.kira.monitor.RemoteControlScheduler
import best.spaghetcodes.kira.monitor.RemoteMonitor
import com.google.gson.Gson
import net.minecraft.client.Minecraft
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.event.FMLInitializationEvent
import java.io.File

@Mod(
    modid = kira.MOD_ID,
    name = kira.MOD_NAME,
    version = kira.VERSION
)
class kira {

    companion object {
        const val MOD_ID = "kira"
        const val MOD_NAME = "kira"
        const val VERSION = "0.1.0"

        val mc: Minecraft = Minecraft.getMinecraft()
        private val configDir = File(mc.mcDataDir, "config")
        val kiraConfigDir: File = File(configDir, "Kira")
        val tunerDir: File = File(kiraConfigDir, "Tuner")
        val configLocation: String = File(kiraConfigDir, "kira.toml").absolutePath
        val gson = Gson()
        var config: Config? = null
        var bot: BotBase? = null

        val isTunerEnabled: Boolean
            get() = config?.enableTuner != false

        fun swapBot(b: BotBase) {
            if (bot != null) MinecraftForge.EVENT_BUS.unregister(bot)
            bot = b
            MinecraftForge.EVENT_BUS.register(bot)
            RemoteMonitor.markDirty()
        }
    }

    @Mod.EventHandler
    @Suppress("UNUSED_PARAMETER")
    fun init(event: FMLInitializationEvent) {
        configDir.mkdirs()
        kiraConfigDir.mkdirs()
        tunerDir.mkdirs()
        config = Config()
        config?.preload()

        ConfigCommand().register()
        KeyBindings.register()

        MinecraftForge.EVENT_BUS.register(PacketListener())
        MinecraftForge.EVENT_BUS.register(StateManager)
        MinecraftForge.EVENT_BUS.register(Mouse)
        MinecraftForge.EVENT_BUS.register(LobbyMovement)
        MinecraftForge.EVENT_BUS.register(KeyBindings)
        MinecraftForge.EVENT_BUS.register(CameraController)
        MinecraftForge.EVENT_BUS.register(StatsOverlay())
        RemoteControlScheduler.init()
        RemoteMonitor.init()

        // Utilise l’accès typé -> aucun Any ici.
        val idx = config?.currentBot ?: 0
        val chosen = config?.getBot(idx) ?: Sumo()
        swapBot(chosen)

        Runtime.getRuntime().addShutdownHook(Thread { config?.writeData() })
    }
}
