package best.spaghetcodes.kira.bot

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.player.LobbyMovement
import best.spaghetcodes.kira.bot.bots.Sumo
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.event.entity.EntityJoinWorldEvent
import net.minecraftforge.fml.common.eventhandler.EventPriority
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent

object StateManager {

    enum class States {
        LOBBY,
        GAME,
        PLAYING
    }

    var state = States.LOBBY
    var gameFull = false
    var gameStartedAt = -1L
    var lastGameDuration = 0L

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onChat(ev: ClientChatReceivedEvent) {
        val unformatted = ev.message.unformattedText
        if (unformatted.matches(Regex(".* a rejoint \\(./2\\)!"))) {
            state = States.GAME
            if (kira.bot !is Sumo) {
                LobbyMovement.generic()
            }
            if (unformatted.matches(Regex(".* a rejoint \\(2/2\\)!"))) {
                gameFull = true
            }
        } else if (unformatted.contains("Opponent:")) {
            state = States.PLAYING
            gameStartedAt = System.currentTimeMillis()
            LobbyMovement.stop()
        } else if (unformatted.contains("Melee")) {
            state = States.GAME
            gameFull = false
            lastGameDuration = System.currentTimeMillis() - gameStartedAt
            if (kira.bot !is Sumo) {
                LobbyMovement.generic()
            }
        } else if (unformatted.contains("has quit!")) {
            gameFull = false
        }
    }

    @SubscribeEvent
    fun onJoinWorld(ev: EntityJoinWorldEvent) {
        if (kira.mc.thePlayer != null && ev.entity == kira.mc.thePlayer) {
            state = States.LOBBY
            gameFull = false
            gameStartedAt = -1L
            LobbyMovement.stop()
        }
    }

}
