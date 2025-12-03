package best.spaghetcodes.kira.monitor

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.StateManager
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import kotlin.math.max

object RemoteControlScheduler {

    private const val READ_INTERVAL_MS = 500L

    private var lastReadAt = 0L

    private var autoQueuePaused = false
    private var delayedStartAt: Long? = null
    private var stopAfterGamesRemaining: Int? = null
    private var stopAfterDeadline: Long? = null
    private var stopTriggered = false
    private var pendingDisable: PendingDisable? = null

    private var activePlan: ActivePlan? = null

    private var lastCommands: RemoteCommands? = null

    fun init() {
        MinecraftForge.EVENT_BUS.register(this)
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (kira.config?.remoteMonitoringEnabled != true) return

        val now = System.currentTimeMillis()

        readCommands(now)
        updateDelayedStart(now)
        updateStopTimers(now)
        tickPlan(now)
        tryExecutePendingDisable()
    }

    private fun readCommands(now: Long) {
        if (now - lastReadAt < READ_INTERVAL_MS) return
        lastReadAt = now

        val payload = RemoteMonitor.readExistingPayload() ?: return
        val commands = payload.commands ?: return

        lastCommands = commands

        commands.pauseAutoQueue?.let { newValue ->
            if (autoQueuePaused != newValue) {
                autoQueuePaused = newValue
                RemoteMonitor.markDirty()
            }
        }

        commands.botEnabled?.let { enforceBotToggle(it, disconnect = true) }

        commands.switchMode?.let { switchMode(it) }

        commands.startAfterSeconds?.let { seconds ->
            delayedStartAt = if (seconds > 0) now + (seconds * 1000) else null
            if (seconds > 0) {
                requestDisable(disconnect = false)
            }
            RemoteMonitor.markDirty()
        }

        commands.stopAfterGames?.let { games ->
            stopAfterGamesRemaining = games.takeIf { it > 0 }
            if (games <= 0) {
                stopTriggered = false
            }
            RemoteMonitor.markDirty()
        }

        commands.stopAfterSeconds?.let { seconds ->
            stopAfterDeadline = seconds.takeIf { it > 0 }?.let { now + (it * 1000) }
            if (seconds <= 0) {
                stopTriggered = false
            }
            RemoteMonitor.markDirty()
        }

        commands.plan?.let { handlePlanCommand(it, now) }
    }

    private fun handlePlanCommand(plan: RemotePlanCommand, now: Long) {
        if (plan.active == false) {
            if (activePlan != null) {
                activePlan = null
                RemoteMonitor.markDirty()
            }
            return
        }

        val steps = plan.steps?.mapNotNull { buildStep(it) } ?: emptyList()
        if (steps.isEmpty()) return

        val newPlan = ActivePlan(
            id = plan.id ?: "remote-plan",
            loop = plan.loop == true,
            steps = steps.toList()
        )

        val startDelay = plan.startAfterSeconds ?: 0
        if (startDelay > 0) {
            newPlan.blockedUntil = now + (startDelay * 1000)
        }

        if (newPlan.blockedUntil == null) {
            (newPlan.currentStep() as? ExecutablePlanStep.Pause)?.let { pause ->
                newPlan.blockedUntil = now + pause.durationMs
            }
        }

        activePlan = newPlan
        RemoteMonitor.markDirty()
    }

    private fun buildStep(step: RemotePlanStep): ExecutablePlanStep? {
        val type = step.type.uppercase()
        return when (type) {
            StepType.PLAY.name -> {
                val mode = step.mode ?: return null
                val games = step.games ?: return null
                if (games <= 0) return null
                ExecutablePlanStep.Play(mode, games)
            }

            StepType.PAUSE.name -> {
                val duration = step.durationSeconds ?: return null
                if (duration <= 0) return null
                ExecutablePlanStep.Pause(duration * 1000)
            }

            else -> null
        }
    }

    private fun tickPlan(now: Long) {
        val plan = activePlan ?: return
        val step = plan.currentStep() ?: return

        plan.blockedUntil?.let { blockedUntil ->
            if (now < blockedUntil) {
                enforceBotToggle(false, disconnect = false)
                return
            }
            plan.blockedUntil = null
            RemoteMonitor.markDirty()
        }

        when (step) {
            is ExecutablePlanStep.Play -> {
                enforceBotToggle(true, disconnect = false)
                switchMode(step.mode)
            }

            is ExecutablePlanStep.Pause -> {
                enforceBotToggle(false, disconnect = false)
                if (plan.blockedUntil == null) {
                    advancePlan(now)
                }
                return
            }
        }
    }

    private fun updateDelayedStart(now: Long) {
        val startAt = delayedStartAt ?: return
        if (now < startAt) {
            enforceBotToggle(false, disconnect = false)
            return
        }

        delayedStartAt = null
        enforceBotToggle(true, disconnect = false)
        if (autoQueuePaused) {
            autoQueuePaused = false
        }
        RemoteMonitor.markDirty()
    }

    private fun updateStopTimers(now: Long) {
        stopAfterDeadline?.let { deadline ->
            if (now >= deadline) {
                triggerStop()
            }
        }

        stopAfterGamesRemaining?.let { remaining ->
            if (remaining <= 0) {
                triggerStop()
            }
        }
    }

    private fun triggerStop() {
        if (stopTriggered) return
        stopTriggered = true
        autoQueuePaused = true
        delayedStartAt = null
        requestDisable(disconnect = true)
        activePlan = null
        RemoteMonitor.markDirty()
    }

    fun onGameFinished(mode: String, win: Boolean) {
        if (kira.config?.remoteMonitoringEnabled != true) return

        stopAfterGamesRemaining = stopAfterGamesRemaining?.let { max(0, it - 1) }

        activePlan?.let { plan ->
            val step = plan.currentStep()
            if (step is ExecutablePlanStep.Play) {
                plan.gamesInCurrentStep++
                plan.totalGamesPlayed++
                if (plan.gamesInCurrentStep >= step.games) {
                    advancePlan(System.currentTimeMillis())
                }
            }
        }

        tryExecutePendingDisable()
        updateStopTimers(System.currentTimeMillis())
        RemoteMonitor.markDirty()
    }

    private fun advancePlan(now: Long) {
        val plan = activePlan ?: return
        plan.gamesInCurrentStep = 0
        plan.currentIndex++
        if (plan.currentIndex >= plan.steps.size) {
            if (plan.loop) {
                plan.currentIndex = 0
            } else {
                activePlan = null
                RemoteMonitor.markDirty()
                return
            }
        }

        val nextStep = plan.currentStep()
        if (nextStep is ExecutablePlanStep.Pause) {
            plan.blockedUntil = now + nextStep.durationMs
        } else {
            plan.blockedUntil = null
        }
        RemoteMonitor.markDirty()
    }

    fun canAutoQueue(forceCommand: Boolean): Boolean {
        if (kira.config?.remoteMonitoringEnabled != true) return true
        val now = System.currentTimeMillis()

        if (stopTriggered || pendingDisable != null) return false
        delayedStartAt?.let { if (now < it) return false }

        val plan = activePlan
        plan?.blockedUntil?.let { blockedUntil ->
            if (now < blockedUntil) return false
        }

        if (plan?.currentStep() is ExecutablePlanStep.Pause) return false

        if (autoQueuePaused && !forceCommand) return false
        return true
    }

    fun getSchedulerStatus(now: Long = System.currentTimeMillis()): SchedulerStatus {
        val plan = activePlan
        val step = plan?.currentStep()

        val remainingStopSeconds = stopAfterDeadline?.let { deadline ->
            max(0, (deadline - now) / 1000)
        }

        val remainingPlanPause = if (plan?.blockedUntil != null && step is ExecutablePlanStep.Pause) {
            max(0, (plan.blockedUntil!! - now) / 1000)
        } else {
            null
        }

        val delayedStartPending = delayedStartAt?.let { it > now } == true
        val planPauseActive = plan?.blockedUntil?.let { it > now } == true || (step is ExecutablePlanStep.Pause)
        val effectiveQueuePause = autoQueuePaused || stopTriggered || delayedStartPending || planPauseActive || pendingDisable != null

        return SchedulerStatus(
            planActive = plan != null,
            planId = plan?.id,
            looping = plan?.loop == true,
            currentStepIndex = plan?.currentIndex,
            currentStepType = step?.type?.name,
            currentStepMode = (step as? ExecutablePlanStep.Play)?.mode,
            currentStepTargetGames = (step as? ExecutablePlanStep.Play)?.games,
            currentStepCompletedGames = plan?.gamesInCurrentStep,
            currentStepTargetDuration = (step as? ExecutablePlanStep.Pause)?.durationMs?.div(1000),
            currentStepRemainingDuration = remainingPlanPause,
            totalPlanGames = plan?.totalGamesPlayed ?: 0,
            stopAfterGamesRemaining = stopAfterGamesRemaining?.takeIf { it > 0 },
            stopAfterSecondsRemaining = remainingStopSeconds,
            autoQueuePaused = effectiveQueuePause,
            pendingStartAt = delayedStartAt?.div(1000)
        )
    }

    fun currentCommands(fallback: RemoteCommands?): RemoteCommands? {
        return lastCommands ?: fallback
    }

    fun onManualToggle(enabled: Boolean) {
        if (kira.config?.remoteMonitoringEnabled != true) return
        val updated = (lastCommands ?: RemoteCommands()).copy(botEnabled = enabled)
        lastCommands = updated
        lastReadAt = System.currentTimeMillis()
        RemoteMonitor.markDirty()
    }

    private fun enforceBotToggle(enabled: Boolean, disconnect: Boolean) {
        val bot = kira.bot ?: return
        if (enabled) {
            pendingDisable = null
            stopTriggered = false
            if (autoQueuePaused && lastCommands?.pauseAutoQueue != true) {
                autoQueuePaused = false
            }
            if (!bot.toggled()) {
                bot.toggle()
            }
            bot.ensureConnectedToHypixel()
            return
        }

        requestDisable(disconnect)
    }

    private fun requestDisable(disconnect: Boolean) {
        val bot = kira.bot
        autoQueuePaused = true
        pendingDisable = PendingDisable(disconnect)
        tryExecutePendingDisable(bot)
        RemoteMonitor.markDirty()
    }

    private fun tryExecutePendingDisable(bot: BotBase? = kira.bot) {
        val pending = pendingDisable ?: return
        val state = StateManager.state
        if (state == StateManager.States.PLAYING) {
            return
        }

        pendingDisable = null
        stopTriggered = stopTriggered || pending.disconnect
        val activeBot = bot ?: return
        if (pending.disconnect) {
            activeBot.remoteShutdownToLobbyAndDisconnect()
        } else {
            if (activeBot.toggled()) {
                activeBot.toggle()
            }
        }
    }

    private fun switchMode(mode: String) {
        val cfg = kira.config ?: return
        val target = cfg.bots.entries.firstOrNull { it.value.getName().equals(mode, ignoreCase = true) } ?: return
        val wasToggled = kira.bot?.toggled() == true

        if (cfg.currentBot != target.key) {
            cfg.currentBot = target.key
            cfg.markDirty()
        }

        val newBot = cfg.getBot(target.key)
        val previousBot = kira.bot
        if (newBot != null && newBot !== previousBot) {
            kira.swapBot(newBot)
        }

        if (newBot != null && newBot.toggled() != wasToggled) {
            newBot.toggle()
        }

        RemoteMonitor.markDirty()
    }

    private fun ActivePlan.currentStep(): ExecutablePlanStep? {
        return steps.getOrNull(currentIndex)
    }

    private data class ActivePlan(
        val id: String,
        val loop: Boolean,
        val steps: List<ExecutablePlanStep>,
        var currentIndex: Int = 0,
        var gamesInCurrentStep: Int = 0,
        var totalGamesPlayed: Int = 0,
        var blockedUntil: Long? = null
    )

    private data class PendingDisable(val disconnect: Boolean)

    private enum class StepType {
        PLAY,
        PAUSE
    }

    private sealed class ExecutablePlanStep(val type: StepType) {
        class Play(val mode: String, val games: Int) : ExecutablePlanStep(StepType.PLAY)
        class Pause(val durationMs: Long) : ExecutablePlanStep(StepType.PAUSE)
    }
}

