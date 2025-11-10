package best.spaghetcodes.kira.bot

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.player.LobbyMovement
import best.spaghetcodes.kira.bot.bots.Sumo
import best.spaghetcodes.kira.core.Config
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
        private set(value) {
            field = value
            Session.updateBotState(value)
        }
    var gameFull = false
    var gameStartedAt = -1L
    var lastGameDuration = 0L

    init {
        Session.updateBotState(state)
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onChat(ev: ClientChatReceivedEvent) {
        val unformatted = ev.message.unformattedText
        if (unformatted.matches(Regex(".* a rejoint \\(./2\\)!"))) {
            state = States.GAME
            val moveType =
                if (kira.bot is Sumo) {
                    Config.LobbyMovementType.SUMO
                } else {
                    Config.LobbyMovementType.FAST_FORWARD
                }
            LobbyMovement.startMovement(moveType)
            if (unformatted.matches(Regex(".* a rejoint \\(2/2\\)!"))) {
                gameFull = true
            }
        } else if (unformatted.contains("Opponent:") || unformatted.contains("Adversaire")) {
            state = States.PLAYING
            gameStartedAt = System.currentTimeMillis()
            LobbyMovement.stop()
        } else if (unformatted.contains("Melee")) {
            state = States.GAME
            gameFull = false
            lastGameDuration = System.currentTimeMillis() - gameStartedAt
            val moveType =
                if (kira.bot is Sumo) {
                    Config.LobbyMovementType.SUMO
                } else {
                    Config.LobbyMovementType.FAST_FORWARD
                }
            LobbyMovement.startMovement(moveType)
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
