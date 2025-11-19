package best.spaghetcodes.kira.bot

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.player.*
import best.spaghetcodes.kira.core.KeyBindings
import best.spaghetcodes.kira.bot.bots.*
import best.spaghetcodes.kira.utils.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.multiplayer.GuiConnecting
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.network.Packet
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.network.play.server.S45PacketTitle
import net.minecraft.util.EnumChatFormatting
import net.minecraft.item.ItemBow
import net.minecraft.item.ItemSword
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.event.entity.EntityJoinWorldEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.fml.client.FMLClientHandler
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent
import java.util.Timer
import kotlin.math.max

open class BotBase(val queueCommand: String, val quickRefresh: Int = 10000) {

    protected val mc = Minecraft.getMinecraft()

    private var toggled = false
    fun toggled() = toggled
    fun toggle() {
        val newState = !toggled
        if (!newState) {
            cancelWinSneak()
        }
        toggled = newState
        Session.updateBotEnabled(toggled)
        ModeRotationManager.onBotToggle(toggled)
        if (!toggled) {
            resetAntiDetection()
        }
    }

    private var attackedID = -1

    private var opponent: EntityPlayer? = null
    private var opponentTimer: Timer? = null
    private var calledFoundOpponent = false

    private val winSneakTimers = mutableListOf<Timer>()
    private var winSneakCleanupTimer: Timer? = null
    private var winSneakActiveUntil = 0L

    protected var combo = 0
    protected var opponentCombo = 0
    protected var ticksSinceHit = 0

    // Hit & Block state
    private var hbNextAllowedAt = 0L
    private var hbHitsSince = 0
    private var hbTargetHits = 0
    private var hbLastHitAt = 0L
    protected var hbActiveUntil = 0L

    private var reconnectTimer: Timer? = null

    private var ticksSinceGameStart = 0

    private var lastOpponentName = ""

    private var calledGameEnd = false

    private var lastDuelDurationSeenAt = 0L

    // évite les doubles comptages (titre + chat)
    private var resultCounted = false

    private val meleeSummaryRegex = Regex(
        """^\\s*\\d+(?:\\.\\d+)?%?\\s*-\\s*(?:pr[eé]cision en\\s+melee|melee accuracy)\\s*-\\s*\\d+(?:\\.\\d+)?%?\\s*$""",
        RegexOption.IGNORE_CASE
    )

    private var antiDetectionStage = 0
    private var antiDetectionSequenceFinished = false
    private var lastAntiDetectionReplyAt = 0L
    private var hasCombatContact = false
    private var hasBowBeenUsed = false

    private val suspicionKeywords = listOf(
        "bot",
        "accuracy",
        "cheat",
        "hacker",
        "hack",
        "macro",
        "autoclick",
        "auto click",
        "aimbot",
        "reach"
    )

    private val suspicionResponses = listOf(
        "wdym?",
        "bro relax",
        "lol I'm just clicking",
        "??",
        "what are you on about",
        "stop coping",
        "just play the game",
        "nah I'm legit",
        "lmao no",
        "dude chill"
    )

    fun opponent() = opponent

    open fun getName(): String = "Base"
    protected open fun onAttack() {}
    protected open fun onAttacked() {}
    protected open fun onGameStart() {}
    protected open fun onGameEnd() {}
    protected open fun onJoinGame() {}
    protected open fun beforeStart() {}
    protected open fun beforeLeave() {}
    protected open fun onFoundOpponent() {}
    protected open fun onTick() {}

    @Suppress("UNUSED_PARAMETER")
    protected fun setStatKeys(keys: Map<String, String>) {}

    // -------- Résultat via résumé & kill (FR/EN) --------

    private fun parseWinnerFromSummary(lineRaw: String): Pair<String, String>? {
        val plain = ChatUtils.removeFormatting(lineRaw)
            .replace(Regex("\\[[^\\]]+\\]\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val leftWins = Regex("^([A-Za-z0-9_]{2,16})\\s+(?:GAGNANT!?|WINNER!?|GAGNANT|WINNER)\\s+([A-Za-z0-9_]{2,16})$", RegexOption.IGNORE_CASE)
        leftWins.matchEntire(plain)?.let { m ->
            return m.groupValues[1] to m.groupValues[2]
        }
        val rightWins = Regex("^([A-Za-z0-9_]{2,16})\\s+([A-Za-z0-9_]{2,16})\\s+(?:GAGNANT!?|WINNER!?|GAGNANT|WINNER)$", RegexOption.IGNORE_CASE)
        rightWins.matchEntire(plain)?.let { m ->
            return m.groupValues[2] to m.groupValues[1]
        }
        return null
    }

    private fun parseKillLine(lineRaw: String): Pair<String, String>? {
        val plain = ChatUtils.removeFormatting(lineRaw)
            .replace(Regex("\\[[^\\]]+\\]\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val fr = Regex("^([A-Za-z0-9_]{2,16}) a été tué par ([A-Za-z0-9_]{2,16})\\.?$", RegexOption.IGNORE_CASE)
        fr.matchEntire(plain)?.let { m -> return m.groupValues[2] to m.groupValues[1] }
        val en = Regex("^([A-Za-z0-9_]{2,16}) was killed by ([A-Za-z0-9_]{2,16})\\.?$", RegexOption.IGNORE_CASE)
        en.matchEntire(plain)?.let { m -> return m.groupValues[2] to m.groupValues[1] }
        return null
    }

    // ----------------------------------------------------

    private fun performHitBlock(now: Long) {
        val dur = RandomUtils.randomIntInRange(40, 80)
        val delay = RandomUtils.randomIntInRange(0, 20)
        hbActiveUntil = now + delay + dur
        TimeUtils.setTimeout({ Mouse.rClick(dur) }, delay)
        hbNextAllowedAt = now
    }

    private fun resetAntiDetection() {
        antiDetectionStage = 0
    }

    private fun recordBowUsage() {
        if (hasBowBeenUsed) return
        val playerBow = isUsingBow(mc.thePlayer)
        val opponentBow = isUsingBow(opponent)
        if (playerBow || opponentBow) {
            hasBowBeenUsed = true
        }
    }

    private fun isUsingBow(entity: EntityPlayer?): Boolean {
        if (entity == null || !entity.isUsingItem) return false
        val item = entity.itemInUse?.item
        return item is ItemBow
    }

    private fun performSneakCycles(cycles: Int) {
        var delay = 0
        repeat(cycles) { index ->
            TimeUtils.setTimeout(Movement::startSneaking, delay)
            val hold = RandomUtils.randomIntInRange(120, 240)
            TimeUtils.setTimeout(Movement::stopSneaking, delay + hold)
            delay += hold
            if (index < cycles - 1) {
                val pause = RandomUtils.randomIntInRange(120, 260)
                delay += pause
            }
        }
    }

    private fun triggerWinSneakCelebration() {
        val cfg = kira.config ?: return
        if (!cfg.winSneak) return
        var delay = 0
        val cycles = RandomUtils.randomIntInRange(4, 6)
        repeat(cycles) { index ->
            scheduleWinSneakAction(delay) { Movement.startSneaking() }
            val hold = RandomUtils.randomIntInRange(70, 140)
            scheduleWinSneakAction(delay + hold) { Movement.stopSneaking() }
            delay += hold
            if (index < cycles - 1) {
                delay += RandomUtils.randomIntInRange(60, 110)
            }
        }
        val totalDuration = delay + 200
        winSneakActiveUntil = System.currentTimeMillis() + totalDuration
        winSneakCleanupTimer?.cancel()
        winSneakCleanupTimer = TimeUtils.setTimeout({ clearWinSneakTimers() }, totalDuration)
    }

    private fun scheduleWinSneakAction(delay: Int, action: () -> Unit) {
        TimeUtils.setTimeout({
            action()
        }, delay)?.let { timer ->
            synchronized(winSneakTimers) { winSneakTimers.add(timer) }
        }
    }

    private fun cancelWinSneak() {
        winSneakCleanupTimer?.cancel()
        winSneakCleanupTimer = null
        winSneakActiveUntil = 0L
        synchronized(winSneakTimers) {
            winSneakTimers.forEach { it.cancel() }
            winSneakTimers.clear()
        }
        Movement.stopSneaking()
    }

    private fun clearWinSneakTimers() {
        synchronized(winSneakTimers) { winSneakTimers.clear() }
        winSneakCleanupTimer = null
        winSneakActiveUntil = 0L
    }

    private fun winSneakDelayRemaining(): Int {
        val remaining = winSneakActiveUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining.toInt() else 0
    }

    private fun sendAntiDetectionMessage(message: String) {
        ChatUtils.sendAsPlayer("/ac $message")
    }

    private fun handleAntiDetection(distance: Float) {
        val cfg = kira.config ?: return
        if (!cfg.antiDetection) {
            resetAntiDetection()
            return
        }

        if (StateManager.state != StateManager.States.PLAYING) {
            resetAntiDetection()
            return
        }

        if (distance <= 7f) {
            return
        }

        if (hasCombatContact) {
            antiDetectionSequenceFinished = true
            return
        }

        if (hasBowBeenUsed || antiDetectionSequenceFinished) {
            return
        }

        when (antiDetectionStage) {
            0 -> if (ticksSinceHit >= 30 * 20) {
                performSneakCycles(RandomUtils.randomIntInRange(2, 3))
                sendAntiDetectionMessage("??")
                antiDetectionStage = 1
            }
            1 -> if (ticksSinceHit >= 50 * 20) {
                sendAntiDetectionMessage("what are you doing?")
                antiDetectionStage = 2
            }
            2 -> if (ticksSinceHit >= 70 * 20) {
                sendAntiDetectionMessage("You're wasting your time.")
                antiDetectionStage = 3
                antiDetectionSequenceFinished = true
            }
        }
    }

    private fun maybeRespondToSuspicion(rawMessage: String) {
        val cfg = kira.config ?: return
        if (!cfg.antiDetection) return
        if (cfg.disableChatMessages == true) return

        val opponentName = opponent?.gameProfile?.name ?: lastOpponentName
        if (opponentName.isBlank()) return

        val plain = ChatUtils.removeFormatting(rawMessage).trim()
        val colonIndex = plain.indexOf(":")
        if (colonIndex <= 0) return

        var author = plain.substring(0, colonIndex).trim()
        author = author.replace(Regex("\\[[^\\]]+\\]"), "").trim()
        if (!author.equals(opponentName, ignoreCase = true)) return

        val content = plain.substring(colonIndex + 1).lowercase()
        val matchedKeyword = suspicionKeywords.any { keyword -> content.contains(keyword) }
        if (!matchedKeyword) return

        val now = System.currentTimeMillis()
        if (now - lastAntiDetectionReplyAt < 6000) return
        lastAntiDetectionReplyAt = now

        val idx = RandomUtils.randomIntInRange(0, suspicionResponses.lastIndex)
        sendAntiDetectionMessage(suspicionResponses[idx])
    }

    private fun maybeHitBlock() {
        val cfg = kira.config ?: return
        if (!cfg.hitBlock) return
        if (this !is Classic && this !is ClassicV2 && this !is Combo && this !is OP && this !is Blitz) return
        val player = mc.thePlayer ?: return
        val opp = opponent ?: return
        if (EntityUtils.getDistanceNoY(player, opp) > 4f) return
        val item = player.heldItem?.item
        if (item !is ItemSword) return

        val now = System.currentTimeMillis()
        val allowed = now >= hbNextAllowedAt

        when (cfg.hitBlockMode) {
            0 -> { // Chance
                if (allowed && cfg.hitBlockChance > 0 && RandomUtils.randomIntInRange(1, 100) <= cfg.hitBlockChance) {
                    performHitBlock(now)
                }
            }
            1 -> { // Cooldown hits
                if (now - hbLastHitAt > 2000) {
                    hbHitsSince = 0
                    hbTargetHits = 0
                }
                hbHitsSince++
                if (allowed) {
                    if (hbTargetHits == 0) {
                        hbTargetHits = RandomUtils.randomIntInRange(cfg.hitBlockMinHits, cfg.hitBlockMaxHits)
                    }
                    if (hbHitsSince >= hbTargetHits) {
                        performHitBlock(now)
                        hbHitsSince = 0
                        hbTargetHits = 0
                    }
                }
            }
        }
        hbLastHitAt = now
    }

    private fun recordResult(iWon: Boolean) {
        if (iWon) {
            Session.wins++
            triggerWinSneakCelebration()
        } else {
            Session.losses++
            cancelWinSneak()
        }
    }

    private fun isCombatSummaryLine(raw: String): Boolean {
        if (!raw.lowercase().contains("melee")) return false

        val plain = ChatUtils.removeFormatting(raw).trim()
        // Ignore standard chat lines like "[VIP+] Player: 100% - Melee Accuracy - 100%" that still
        // contain a player prefix and colon. Real recap lines do not include a speaker prefix.
        val chatPrefixed = Regex("^\\s*(\\[[^\\]]+\\]\\s*)?[A-Za-z0-9_]{2,16}\\s*:\\s*.+")
        if (chatPrefixed.matches(plain)) return false
        return meleeSummaryRegex.matches(plain)
    }

    private val duelDurationRegex = Regex("(?i)\\bduel\\b\\s*-\\s*\\d{2}:\\d{2}\\b")

    private fun updateDuelDurationMarker(raw: String) {
        val plain = ChatUtils.removeFormatting(raw)
        if (duelDurationRegex.containsMatchIn(plain)) {
            lastDuelDurationSeenAt = System.currentTimeMillis()
        }
    }

    private fun hasRecentDuelDuration(): Boolean {
        if (lastDuelDurationSeenAt == 0L) return false
        return System.currentTimeMillis() - lastDuelDurationSeenAt <= 15_000
    }

    fun onPacket(packet: Packet<*>) {
        if (toggled) {
            when (packet) {
                is S19PacketEntityStatus -> {
                    if (packet.opCode.toInt() == 2) {
                        val entity = packet.getEntity(mc.theWorld)
                        if (entity != null) {
                            if (entity.entityId == attackedID) {
                                attackedID = -1
                                onAttack()
                                combo++
                                opponentCombo = 0
                                ticksSinceHit = 0
                                hasCombatContact = true
                                resetAntiDetection()
                                maybeHitBlock()
                            } else if (mc.thePlayer != null && entity.entityId == mc.thePlayer.entityId) {
                                onAttacked()
                                combo = 0
                                opponentCombo++
                                hasCombatContact = true
                            }
                        }
                    }
                }
                is S45PacketTitle -> {
                    if (mc.theWorld != null) {
                        TimeUtils.setTimeout({
                            if (packet.message != null) {
                                val unformatted = packet.message.unformattedText.lowercase()
                                if (!resultCounted && unformatted.contains("won the duel!") && mc.thePlayer != null) {
                                    val me = mc.thePlayer.displayNameString
                                    val p = ChatUtils.removeFormatting(packet.message.unformattedText).split("won")[0].trim()

                                    val (_, _, iWon) =
                                        if (unformatted.contains(me.lowercase())) {
                                            recordResult(true)
                                            Triple(me, lastOpponentName, true)
                                        } else {
                                            recordResult(false)
                                            Triple(p, me, false)
                                        }

                                    resultCounted = true
                                    ChatUtils.info(Session.getSession())

                                    if (!iWon) {
                                        TimeUtils.setTimeout({ joinGame() }, RandomUtils.randomIntInRange(1000, 2000))
                                    }

                                    if ((kira.config?.disconnectAfterGames ?: 0) > 0) {
                                        if (Session.wins + Session.losses >= kira.config?.disconnectAfterGames!!) {
                                            ChatUtils.info("Played ${kira.config?.disconnectAfterGames} games, disconnecting...")
                                            TimeUtils.setTimeout({
                                                ChatUtils.sendAsPlayer("/l duels")
                                                TimeUtils.setTimeout({
                                                    toggle()
                                                    disconnect()
                                                }, RandomUtils.randomIntInRange(2300, 5000))
                                            }, RandomUtils.randomIntInRange(900, 1700))
                                        }
                                    }

                                    if ((kira.config?.disconnectAfterMinutes ?: 0) > 0) {
                                        val activeDuration = Session.getActiveDurationMs()
                                        if (activeDuration >= kira.config?.disconnectAfterMinutes!! * 60 * 1000) {
                                            ChatUtils.info("Played for ${kira.config?.disconnectAfterMinutes} minutes, disconnecting...")
                                            TimeUtils.setTimeout({
                                                ChatUtils.sendAsPlayer("/l duels")
                                                TimeUtils.setTimeout({
                                                    toggle()
                                                    disconnect()
                                                }, RandomUtils.randomIntInRange(2300, 5000))
                                            }, RandomUtils.randomIntInRange(900, 1700))
                                        }
                                    }

                                }
                            }
                        }, 1000)
                    }
                }
            }
        }
    }

    @SubscribeEvent
    fun onAttackEntityEvent(ev: AttackEntityEvent) {
        if (toggled() && ev.entity == mc.thePlayer) {
            attackedID = ev.target.entityId
        }
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onClientTick(ev: ClientTickEvent) {
        registerPacketListener()
        if (toggled) {
            onTick()
            recordBowUsage()

            if (StateManager.state != StateManager.States.PLAYING) {
                ticksSinceGameStart++
                val rotationDecision = ModeRotationManager.onQueueWaitingTick(this)
                if (rotationDecision != null) {
                    ticksSinceGameStart = 0
                    TimeUtils.setTimeout({ rotationDecision.botToQueue.queueNextGame(rotationDecision.forceQueueCommand) }, RandomUtils.randomIntInRange(300, 500))
                } else if (ticksSinceGameStart / 20 > (kira.config?.rqNoGame ?: 30)) {
                    ticksSinceGameStart = 0
                    joinGame()
                }
            } else {
                ticksSinceGameStart = 0
                ModeRotationManager.onOpponentFound()
            }

            if (mc.thePlayer != null && opponent != null) {
                ticksSinceHit++
                val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent)
                handleAntiDetection(distance)
                if (distance > 5 && (combo != 0 || opponentCombo != 0)) {
                    combo = 0
                    opponentCombo = 0
                    ChatUtils.info("combo reset")
                }
            }
        }

        if (KeyBindings.toggleBotKeyBinding.isPressed) {
            toggle()
            ChatUtils.info(
                "Kira has been toggled ${if (toggled()) "${EnumChatFormatting.GREEN}on" else "${EnumChatFormatting.RED}off"}",
                force = true
            )
            if (toggled()) {
                ChatUtils.info(
                    "Current selected bot: ${EnumChatFormatting.GREEN}${getName()}",
                    force = true
                )
                joinGame()
                resultCounted = false
            }
        }
    }

    @SubscribeEvent
    fun onChat(ev: ClientChatReceivedEvent) {
        val unformatted = ev.message.unformattedText
        if (toggled() && mc.thePlayer != null) {
            updateDuelDurationMarker(unformatted)
            maybeRespondToSuspicion(unformatted)

            if (unformatted.contains("The game starts in 1 second!") ||
                unformatted.contains("dans 1 secondes!")) {
                beforeStart()
            }

            if (unformatted.contains("Are you sure? Type /lobby again")) {
                leaveGame()
            }

            if (unformatted.contains("Opponent:") || unformatted.contains("adversaires")) {
                gameStart()
            }

            // FR/EN : fin de partie détectée par le récapitulatif (ligne précision melee)
            if (isCombatSummaryLine(unformatted) && hasRecentDuelDuration() && !calledGameEnd) {
                calledGameEnd = true
                lastDuelDurationSeenAt = 0L
                gameEnd()
            }

            // Fallback résultat via résumé (FR/EN)
            if (!resultCounted && (unformatted.contains("GAGNANT!") || unformatted.contains("WINNER!"))) {
                parseWinnerFromSummary(unformatted)?.let { (winner, _) ->
                    val me = mc.thePlayer.gameProfile.name
                    val iWon = winner.equals(me, ignoreCase = true)
                    recordResult(iWon)
                    resultCounted = true
                    ChatUtils.info(Session.getSession())
                }
            }

            // Secours immédiat : ligne de kill (FR/EN)
            if (!resultCounted) {
                parseKillLine(unformatted)?.let { (winner, _) ->
                    val me = mc.thePlayer.gameProfile.name
                    val iWon = winner.equals(me, ignoreCase = true)
                    recordResult(iWon)
                    resultCounted = true
                    ChatUtils.info(Session.getSession())
                }
            }

            if (unformatted.lowercase().contains("something went wrong trying") || unformatted.lowercase().contains("please don't spam the command")) {
                TimeUtils.setTimeout({ joinGame() }, RandomUtils.randomIntInRange(6000, 8000))
            } else if (unformatted.contains("A disconnect occurred in your connection, so you were put")) {
                Movement.clearAll()
                Mouse.stopLeftAC()
                TimeUtils.setTimeout({ joinGame() }, RandomUtils.randomIntInRange(6000, 8000))
            }

            if (unformatted.contains("Woah there, slow down!") && kira.config?.strictDodging == true) {
                disconnect()
                TimeUtils.setTimeout(this::reconnect, RandomUtils.randomIntInRange(4000, 5000))
            }
        }

    }

    @SubscribeEvent
    fun onJoinWorld(ev: EntityJoinWorldEvent) {
        if (kira.mc.thePlayer != null && ev.entity == kira.mc.thePlayer) {
            if (toggled()) {
                resetVars()
                LobbyMovement.stop()
                Movement.clearAll()
                cancelWinSneak()
                Combat.stopRandomStrafe()
                Mouse.stopLeftAC()
                calledGameEnd = false
            }
        }
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onConnect(event: ClientConnectedToServerEvent) {
        if (toggled()) {
            println("Reconnect successful!")
            reconnectTimer?.cancel()
            TimeUtils.setTimeout({ joinGame() }, RandomUtils.randomIntInRange(6000, 8000))
        }
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onDisconnect(event: ClientDisconnectionFromServerEvent) {
        if (toggled()) {
            println("Disconnected from server, reconnecting...")
            TimeUtils.setTimeout({
                reconnectTimer = TimeUtils.setInterval(this::reconnect, 0, 30000)
            }, RandomUtils.randomIntInRange(5000, 7000))
        }
    }

    private fun resetVars() {
        calledFoundOpponent = false
        opponentTimer?.cancel()
        opponent = null
        combo = 0
        opponentCombo = 0
        ticksSinceHit = 0
        ticksSinceGameStart = 0
        resultCounted = false
        lastDuelDurationSeenAt = 0L
        hasCombatContact = false
        hasBowBeenUsed = false
        antiDetectionSequenceFinished = false
        resetAntiDetection()
    }

    private fun gameStart() {
        if (toggled()) {
            if (kira.config?.sendStartMessage == true) {
                TimeUtils.setTimeout({
                    ChatUtils.sendAsPlayer("/ac " + (kira.config?.startMessage ?: "glhf!"))
                }, kira.config?.startMessageDelay ?: 100)
            }
            val quickRefreshTimer = TimeUtils.setInterval(this::bakery, 200, 50)
            TimeUtils.setTimeout({
                quickRefreshTimer?.cancel()
                opponentTimer = TimeUtils.setInterval(this::bakery, 0, 500)
            }, quickRefresh)
            resultCounted = false
            ModeRotationManager.onOpponentFound()
            onGameStart()
        }
    }

    private fun gameEnd() {
        if (toggled()) {
            onGameEnd()
            resetVars()

            if (kira.config?.sendAutoGG == true) {
                TimeUtils.setTimeout({
                    ChatUtils.sendAsPlayer("/ac " + (kira.config?.ggMessage ?: "gg"))
                }, kira.config?.ggDelay ?: 100)
            }

            val rotationDecision = ModeRotationManager.onGameCompleted(this)
            val targetBot = rotationDecision?.botToQueue ?: this
            val forceCommand = rotationDecision?.forceQueueCommand ?: false
            val celebrationDelay = winSneakDelayRemaining()
            if (kira.config?.fastRequeue == true) {
                val baseDelay = RandomUtils.randomIntInRange(300, 500)
                TimeUtils.setTimeout({ targetBot.queueNextGame(forceCommand) }, max(baseDelay, celebrationDelay))
            } else {
                val baseDelay = kira.config?.autoRqDelay ?: 2000
                TimeUtils.setTimeout({ targetBot.queueNextGame(forceCommand) }, max(baseDelay, celebrationDelay))
            }
        }
    }

    private fun bakery() {
        if (StateManager.state == StateManager.States.PLAYING) {
            val entity = EntityUtils.getOpponentEntity()
            if (entity != null) {
                opponent = entity
                lastOpponentName = opponent!!.displayNameString
                if (!calledFoundOpponent) {
                    calledFoundOpponent = true
                    onFoundOpponent()
                }
            }
        }
    }


    private fun leaveGame() {
        if (toggled() && StateManager.state != StateManager.States.PLAYING) {
            TimeUtils.setTimeout({ ChatUtils.sendAsPlayer("/l") }, RandomUtils.randomIntInRange(100, 300))
        }
    }

    private fun joinGame(second: Boolean = false, forceCommand: Boolean = false) {
        cancelWinSneak()
        if (toggled() && StateManager.state != StateManager.States.PLAYING && !StateManager.gameFull) {
            if (StateManager.state == StateManager.States.GAME) {
                val paper = !forceCommand && kira.config?.paperRequeue == true && Inventory.setInvItem("paper")
                if (paper) {
                    TimeUtils.setTimeout({
                        Mouse.rClick(RandomUtils.randomIntInRange(30, 70))
                        TimeUtils.setTimeout({ Mouse.rClick(RandomUtils.randomIntInRange(30, 70)) }, RandomUtils.randomIntInRange(100, 300))
                    }, RandomUtils.randomIntInRange(100, 300))
                } else {
                    if (second || forceCommand) {
                        TimeUtils.setTimeout({ ChatUtils.sendAsPlayer(queueCommand) }, RandomUtils.randomIntInRange(100, 300))
                    } else {
                        TimeUtils.setTimeout({ joinGame(true, forceCommand) }, RandomUtils.randomIntInRange(1000, 1400))
                    }
                }
            } else {
                TimeUtils.setTimeout({ ChatUtils.sendAsPlayer(queueCommand) }, RandomUtils.randomIntInRange(100, 300))
            }
        }
    }

    fun queueNextGame(forceCommand: Boolean = false) {
        joinGame(forceCommand = forceCommand)
    }

    private fun disconnect() {
        if (mc.theWorld != null) {
            mc.addScheduledTask({
                mc.theWorld.sendQuittingDisconnectingPacket()
                mc.loadWorld(null)
                mc.displayGuiScreen(GuiMultiplayer(GuiMainMenu()))
            })
        }
    }

    private fun reconnect() {
        if (mc.theWorld == null) {
            if (mc.currentScreen is GuiMultiplayer) {
                mc.addScheduledTask({
                    println("Reconnecting...")
                    FMLClientHandler.instance().setupServerList()
                    FMLClientHandler.instance().connectToServer(mc.currentScreen, ServerData("hypixel", "mc.hypixel.net", false))
                })
            } else {
                if (mc.theWorld == null && mc.currentScreen !is GuiConnecting) {
                    mc.addScheduledTask({
                        println("Attempting to show new multiplayer screen...")
                        mc.displayGuiScreen(GuiMultiplayer(GuiMainMenu()))
                        reconnect()
                    })
                }
            }
        }
    }

    class PacketReader(private val container: BotBase) : SimpleChannelInboundHandler<Packet<*>>(false) {
        override fun channelRead0(ctx: ChannelHandlerContext?, msg: Packet<*>?) {
            if (msg != null) container.onPacket(msg)
            ctx?.fireChannelRead(msg)
        }
    }

    private fun registerPacketListener() {
        val pipeline = mc.thePlayer?.sendQueue?.networkManager?.channel()?.pipeline()
        if (pipeline != null && pipeline.get("${getName()}_packet_handler") == null && pipeline.get("packet_handler") != null) {
            pipeline.addBefore("packet_handler", "${getName()}_packet_handler", PacketReader(this))
            println("Registered ${getName()}_packet_handler")
        }
    }
}
