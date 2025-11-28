package best.spaghetcodes.kira.bot

import best.spaghetcodes.kira.core.Config
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.ChatUtils
import kotlin.math.max

object ModeRotationManager {

    private const val QUEUE_TIMEOUT_SECONDS = 120

    private var gamesInCurrentMode = 0
    private var lastKnownBotIndex = -1
    private var queueWaitTicks = 0
    private var rotationSwitchPending = false

    enum class Trigger {
        GAME_LIMIT,
        QUEUE_TIMEOUT
    }

    data class RotationDecision(val botToQueue: BotBase, val trigger: Trigger, val forceQueueCommand: Boolean)

    fun onGameCompleted(currentBot: BotBase): RotationDecision? {
        val cfg = kira.config ?: return null
        if (!isRotationActive(cfg)) {
            syncState(cfg)
            return null
        }

        syncState(cfg)

        gamesInCurrentMode++
        val gamesPerMode = max(1, cfg.modeRotationGames)
        return if (gamesInCurrentMode >= gamesPerMode) {
            rotate(cfg, Trigger.GAME_LIMIT, currentBot)
        } else {
            null
        }
    }

    fun onQueueWaitingTick(currentBot: BotBase): RotationDecision? {
        val cfg = kira.config ?: return null
        if (!isRotationActive(cfg)) {
            syncState(cfg)
            return null
        }

        syncState(cfg)

        queueWaitTicks++
        val timeoutTicks = QUEUE_TIMEOUT_SECONDS * 20
        return if (queueWaitTicks >= timeoutTicks) {
            queueWaitTicks = 0
            gamesInCurrentMode = 0
            rotate(cfg, Trigger.QUEUE_TIMEOUT, currentBot)
        } else {
            null
        }
    }

    fun onOpponentFound() {
        queueWaitTicks = 0
        rotationSwitchPending = false
    }

    fun onBotToggle(enabled: Boolean) {
        if (!enabled) {
            reset()
            return
        }

        val cfg = kira.config ?: return
        if (!isRotationActive(cfg)) {
            syncState(cfg)
            return
        }

        if (lastKnownBotIndex != -1) {
            syncState(cfg)
            return
        }

        val sequence = getRotationSequence(cfg)
        if (sequence.isEmpty()) {
            reset()
            lastKnownBotIndex = cfg.currentBot
            return
        }

        val firstIdx = sequence.first()
        val wasToggled = kira.bot?.toggled() == true

        if (cfg.currentBot != firstIdx) {
            cfg.currentBot = firstIdx
            cfg.markDirty()
        }

        val previousBot = kira.bot
        val newBot = cfg.getBot(firstIdx) ?: previousBot
        if (newBot != null && newBot !== previousBot) {
            kira.swapBot(newBot)
        }

        gamesInCurrentMode = 0
        queueWaitTicks = 0
        lastKnownBotIndex = firstIdx

        if (newBot != null && newBot.toggled() != wasToggled) {
            newBot.toggle()
        }
    }

    fun onConfigUpdated() {
        val cfg = kira.config
        if (cfg == null) {
            reset()
            return
        }

        if (!isRotationActive(cfg)) {
            reset()
            lastKnownBotIndex = cfg.currentBot
            return
        }

        val sequence = getRotationSequence(cfg)
        if (sequence.isEmpty()) {
            reset()
            lastKnownBotIndex = cfg.currentBot
            return
        }

        if (!sequence.contains(cfg.currentBot)) {
            val nextIdx = sequence.first()
            val wasToggled = kira.bot?.toggled() == true
            if (cfg.currentBot != nextIdx) {
                cfg.currentBot = nextIdx
                cfg.markDirty()
            }
            val previousBot = kira.bot
            val newBot = cfg.getBot(nextIdx) ?: previousBot
            if (newBot != null && newBot !== previousBot) {
                kira.swapBot(newBot)
            }
            if (newBot != null && newBot.toggled() != wasToggled) {
                newBot.toggle()
            }
            reset()
            lastKnownBotIndex = nextIdx
            return
        }

        syncState(cfg)
    }

    private fun rotate(cfg: Config, trigger: Trigger, fallbackBot: BotBase): RotationDecision? {
        val sequence = getRotationSequence(cfg)
        if (sequence.isEmpty()) {
            return null
        }

        val wasToggled = kira.bot?.toggled() == true
        val currentIdxInSeq = sequence.indexOf(cfg.currentBot).takeIf { it >= 0 } ?: 0
        val nextIdx = (currentIdxInSeq + 1) % sequence.size
        val nextBotIndex = sequence[nextIdx]

        if (cfg.currentBot != nextBotIndex) {
            cfg.currentBot = nextBotIndex
            cfg.markDirty()
        }

        val previousBot = kira.bot
        val newBot = cfg.getBot(nextBotIndex) ?: previousBot ?: fallbackBot
        if (newBot !== previousBot) {
            kira.swapBot(newBot)
        }
        if (newBot.toggled() != wasToggled) {
            newBot.toggle()
        }

        gamesInCurrentMode = 0
        queueWaitTicks = 0
        lastKnownBotIndex = nextBotIndex
        rotationSwitchPending = true

        val message = when (trigger) {
            Trigger.GAME_LIMIT -> "Rotation: changement vers ${newBot.getName()} après ${max(1, cfg.modeRotationGames)} parties."
            Trigger.QUEUE_TIMEOUT -> "Rotation: changement vers ${newBot.getName()} après 2 minutes sans adversaire."
        }
        ChatUtils.info(message)

        return RotationDecision(newBot, trigger, true)
    }

    fun consumeRotationSwitchPending(): Boolean {
        val wasPending = rotationSwitchPending
        rotationSwitchPending = false
        return wasPending
    }

    private fun syncState(cfg: Config) {
        if (lastKnownBotIndex != cfg.currentBot) {
            lastKnownBotIndex = cfg.currentBot
            gamesInCurrentMode = 0
            queueWaitTicks = 0
        }
    }

    private fun isRotationActive(cfg: Config): Boolean {
        return cfg.enableModeRotation && getRotationSequence(cfg).isNotEmpty()
    }

    private fun getRotationSequence(cfg: Config): List<Int> {
        val validKeys = cfg.bots.keys
        return listOf(cfg.rotationMode1, cfg.rotationMode2, cfg.rotationMode3)
            .filter { validKeys.contains(it) }
            .distinct()
    }

    private fun reset() {
        gamesInCurrentMode = 0
        queueWaitTicks = 0
        lastKnownBotIndex = -1
        rotationSwitchPending = false
    }
}
