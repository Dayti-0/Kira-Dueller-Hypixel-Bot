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
import net.minecraft.item.ItemSword
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.event.entity.EntityJoinWorldEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.fml.client.FMLClientHandler
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent

open class BotBase(val queueCommand: String, val quickRefresh: Int = 10000) {

    protected val mc = Minecraft.getMinecraft()

    private var toggled = false
    fun toggled() = toggled
    fun toggle() {
        toggled = !toggled
        Session.updateBotEnabled(toggled)
        if (!toggled) {
            resetAntiDetection()
        }
    }

    private var attackedID = -1

    private var opponent: EntityPlayer? = null
    private var opponentTimer: TimeUtils.TaskHandle? = null
    private var calledFoundOpponent = false

    protected var combo = 0
    protected var opponentCombo = 0
    protected var ticksSinceHit = 0

    // Hit & Block state
    private var hbNextAllowedAt = 0L
    private var hbHitsSince = 0
    private var hbTargetHits = 0
    private var hbLastHitAt = 0L
    protected var hbActiveUntil = 0L

    private var reconnectTimer: TimeUtils.TaskHandle? = null

    private var ticksSinceGameStart = 0

    private var lastOpponentName = ""

    private var calledGameEnd = false

    // évite les doubles comptages (titre + chat)
    private var resultCounted = false

    private var antiDetectionStage = 0

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
            }
        }
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
                                resetAntiDetection()
                                maybeHitBlock()
                            } else if (mc.thePlayer != null && entity.entityId == mc.thePlayer.entityId) {
                                onAttacked()
                                combo = 0
                                opponentCombo++
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
                                            Session.wins++
                                            Triple(me, lastOpponentName, true)
                                        } else {
                                            Session.losses++
                                            Triple(p, me, false)
                                        }

                                    resultCounted = true
                                    ChatUtils.info(Session.getSession())

                                    if (!iWon) {
                                        TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(1000, 2000))
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

            if (StateManager.state != StateManager.States.PLAYING) {
                ticksSinceGameStart++
                if (ticksSinceGameStart / 20 > (kira.config?.rqNoGame ?: 30)) {
                    ticksSinceGameStart = 0
                    joinGame()
                }
            } else {
                ticksSinceGameStart = 0
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

            // FR: fin de partie détectée par le récapitulatif
            if (unformatted.contains("Melee") && !calledGameEnd) {
                calledGameEnd = true
                gameEnd()
            }

            // Fallback résultat via résumé (FR/EN)
            if (!resultCounted && (unformatted.contains("GAGNANT!") || unformatted.contains("WINNER!"))) {
                parseWinnerFromSummary(unformatted)?.let { (winner, _) ->
                    val me = mc.thePlayer.gameProfile.name
                    val iWon = winner.equals(me, ignoreCase = true)
                    if (iWon) {
                        Session.wins++
                    } else {
                        Session.losses++
                    }
                    resultCounted = true
                    ChatUtils.info(Session.getSession())
                }
            }

            // Secours immédiat : ligne de kill (FR/EN)
            if (!resultCounted) {
                parseKillLine(unformatted)?.let { (winner, _) ->
                    val me = mc.thePlayer.gameProfile.name
                    val iWon = winner.equals(me, ignoreCase = true)
                    if (iWon) {
                        Session.wins++
                    } else {
                        Session.losses++
                    }
                    resultCounted = true
                    ChatUtils.info(Session.getSession())
                }
            }

            if (unformatted.lowercase().contains("something went wrong trying") || unformatted.lowercase().contains("please don't spam the command")) {
                TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(6000, 8000))
            } else if (unformatted.contains("A disconnect occurred in your connection, so you were put")) {
                Movement.clearAll()
                Mouse.stopLeftAC()
                TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(6000, 8000))
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
            TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(6000, 8000))
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

            val delay = kira.config?.autoRqDelay ?: 2000
            if (kira.config?.fastRequeue == true) {
                TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(300, 500))
            } else {
                TimeUtils.setTimeout(this::joinGame, delay)
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

    private fun joinGame(second: Boolean = false) {
        if (toggled() && StateManager.state != StateManager.States.PLAYING && !StateManager.gameFull) {
            if (StateManager.state == StateManager.States.GAME) {
                val paper = kira.config?.paperRequeue == true && Inventory.setInvItem("paper")
                if (paper) {
                    TimeUtils.setTimeout({
                        Mouse.rClick(RandomUtils.randomIntInRange(30, 70))
                        TimeUtils.setTimeout({ Mouse.rClick(RandomUtils.randomIntInRange(30, 70)) }, RandomUtils.randomIntInRange(100, 300))
                    }, RandomUtils.randomIntInRange(100, 300))
                } else {
                    if (second) {
                        TimeUtils.setTimeout({ ChatUtils.sendAsPlayer(queueCommand) }, RandomUtils.randomIntInRange(100, 300))
                    } else {
                        TimeUtils.setTimeout({ joinGame(true) }, RandomUtils.randomIntInRange(1000, 1400))
                    }
                }
            } else {
                TimeUtils.setTimeout({ ChatUtils.sendAsPlayer(queueCommand) }, RandomUtils.randomIntInRange(100, 300))
            }
        }
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
