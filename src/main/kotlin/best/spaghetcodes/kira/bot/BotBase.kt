package best.spaghetcodes.kira.bot

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.player.*
import best.spaghetcodes.kira.core.KeyBindings
import best.spaghetcodes.kira.utils.*
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
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
import net.minecraft.network.play.server.S3EPacketTeams
import net.minecraft.network.play.server.S45PacketTitle
import net.minecraft.util.EnumChatFormatting
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.event.entity.EntityJoinWorldEvent
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.fml.client.FMLClientHandler
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.Timer
import kotlin.concurrent.thread

open class BotBase(val queueCommand: String, val quickRefresh: Int = 10000) {

    protected val mc = Minecraft.getMinecraft()

    private var toggled = false
    fun toggled() = toggled
    fun toggle() { toggled = !toggled }

    private var attackedID = -1

    private var statKeys: Map<String, String> = mapOf("wins" to "", "losses" to "", "ws" to "")

    private var playerCache: HashMap<String, String> = hashMapOf()
    private var playersSent: ArrayList<String> = arrayListOf()
    private var playersQuit: ArrayList<String> = arrayListOf()
    private var playersLost: ArrayList<String> = arrayListOf()

    private var opponent: EntityPlayer? = null
    private var opponentTimer: Timer? = null
    private var calledFoundOpponent = false

    protected var combo = 0
    protected var opponentCombo = 0
    protected var ticksSinceHit = 0

    private var reconnectTimer: Timer? = null

    private var ticksSinceGameStart = 0

    private var lastOpponentName = ""

    private var calledGameEnd = false

    // évite les doubles comptages (titre + chat)
    private var resultCounted = false

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

    protected fun setStatKeys(keys: Map<String, String>) { statKeys = keys }

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
                            } else if (mc.thePlayer != null && entity.entityId == mc.thePlayer.entityId) {
                                onAttacked()
                                combo = 0
                                opponentCombo++
                            }
                        }
                    }
                }
                is S3EPacketTeams -> {
                    if (packet.action == 3 && packet.name == "§7§k") {
                        val players = packet.players
                        for (player in players) {
                            if (playersQuit.contains(player)) playersQuit.remove(player)
                            TimeUtils.setTimeout({ handlePlayer(player) }, 1500)
                        }
                    } else if (packet.action == 4 && packet.name == "§7§k") {
                        val players = packet.players
                        for (player in players) playersQuit.add(player)
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

                                    val (winner, loser, iWon) =
                                        if (unformatted.contains(me.lowercase())) {
                                            Session.wins++
                                            Triple(me, lastOpponentName, true)
                                        } else {
                                            Session.losses++
                                            ChatUtils.info("Adding $p to the list of players to dodge...")
                                            playersLost.add(p)
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
                                        if (System.currentTimeMillis() - Session.startTime >= kira.config?.disconnectAfterMinutes!! * 60 * 1000) {
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

                                    if (kira.config?.sendWebhookMessages == true) {
                                        if (kira.config?.webhookURL != "") {
                                            val opponentName = if (iWon) loser else winner
                                            val faceUrl = if (playerCache.containsKey(opponentName)) {
                                                "https://crafatar.com/avatars/${playerCache[opponentName]}"
                                            } else {
                                                "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png"
                                            }
                                            val duration = StateManager.lastGameDuration / 1000
                                            val fields = WebHook.buildFields(arrayListOf(
                                                mapOf("name" to "Winner", "value" to winner, "inline" to "true"),
                                                mapOf("name" to "Loser", "value" to loser, "inline" to "true"),
                                                mapOf("name" to "Bot Started", "value" to "<t:${(Session.startTime / 1000).toInt()}:R>", "inline" to "false")
                                            ))
                                            val footer = WebHook.buildFooter(ChatUtils.removeFormatting(Session.getSession()), "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
                                            val author = WebHook.buildAuthor("Duck Dueller - ${getName()}", "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
                                            val thumbnail = WebHook.buildThumbnail(faceUrl)

                                            WebHook.sendEmbed(
                                                kira.config?.webhookURL!!,
                                                WebHook.buildEmbed(
                                                    "${if (iWon) ":smirk:" else ":confused:"} Game ${if (iWon) "WON" else "LOST"}!",
                                                    "Game Duration: `${duration}`s",
                                                    fields, footer, author, thumbnail, if (iWon) 0x66ed8a else 0xed6d66
                                                )
                                            )
                                        } else {
                                            ChatUtils.error("Webhook URL hasn't been set!")
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
            if (kira.config?.enableHits != true) {
                ev.isCanceled = true
            } else {
                attackedID = ev.target.entityId
            }
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
                if (distance > 5 && (combo != 0 || opponentCombo != 0)) {
                    combo = 0
                    opponentCombo = 0
                    ChatUtils.info("combo reset")
                }
            }
        }

        if (KeyBindings.toggleBotKeyBinding.isPressed) {
            toggle()
            ChatUtils.info("Kira has been toggled ${if (toggled()) "${EnumChatFormatting.GREEN}on" else "${EnumChatFormatting.RED}off"}")
            if (toggled()) {
                ChatUtils.info("Current selected bot: ${EnumChatFormatting.GREEN}${getName()}")
                joinGame()
                Session.startTime = System.currentTimeMillis()
                resultCounted = false
            }
        }
    }

    @SubscribeEvent
    fun onChat(ev: ClientChatReceivedEvent) {
        val unformatted = ev.message.unformattedText
        if (toggled() && mc.thePlayer != null) {

            val needStats = (kira.config?.enableDodging == true) || (kira.config?.sendWebhookStats == true)

            if (unformatted.contains("The game starts in 2 seconds!")) {
                println(playersSent.joinToString(", "))
                var found = false
                if (playersSent.contains(mc.thePlayer.displayNameString)) {
                    if (playersSent.size > 1) found = true
                } else {
                    if (playersSent.size > 0) found = true
                }

                if (!found && kira.config?.dodgeNoStats == true && needStats) {
                    ChatUtils.info("No stats found, dodging...")
                    leaveGame()
                    sendDodgeWebhook("")
                    TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(4000, 6000))
                }
            } else if (unformatted.contains("The game starts in 1 second!")) {
                beforeStart()
            }

            if (unformatted.contains("Are you sure? Type /lobby again")) {
                leaveGame()
            }

            if (unformatted.contains("Opponent:")) {
                gameStart()
            }

            // FR: fin de partie détectée par le récapitulatif
            if (unformatted.contains("Melee") && !calledGameEnd) {
                calledGameEnd = true
                gameEnd()
            }

            // Fallback résultat via résumé (FR/EN)
            if (!resultCounted && (unformatted.contains("GAGNANT") || unformatted.contains("WINNER"))) {
                parseWinnerFromSummary(unformatted)?.let { (winner, _) ->
                    val me = mc.thePlayer.gameProfile.name
                    val iWon = winner.equals(me, ignoreCase = true)
                    if (iWon) {
                        Session.wins++
                    } else {
                        Session.losses++
                        playersLost.add(winner)
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
                        playersLost.add(winner)
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

        if (unformatted.contains("Your new API key is ")) {
            val needStats = (kira.config?.enableDodging == true) || (kira.config?.sendWebhookStats == true)
            if (needStats) {
                val key = ev.message.unformattedText.split("Your new API key is ")[1]
                kira.config?.apiKey = key
                kira.config?.writeData()
                ChatUtils.info("Saved API Key!")
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
            val author = WebHook.buildAuthor("Duck Dueller - ${getName()}", "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
            val thumbnail = WebHook.buildThumbnail("https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
            WebHook.sendEmbed(
                kira.config?.webhookURL!!,
                WebHook.buildEmbed("Reconnected!", "The bot successfully reconnected!", JsonArray(), JsonObject(), author, thumbnail, 0x66ed8a)
            )
            reconnectTimer?.cancel()
            TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(6000, 8000))
        }
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onDisconnect(event: ClientDisconnectionFromServerEvent) {
        if (toggled()) {
            println("Disconnected from server, reconnecting...")
            val author = WebHook.buildAuthor("Duck Dueller - ${getName()}", "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
            val thumbnail = WebHook.buildThumbnail("https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
            WebHook.sendEmbed(
                kira.config?.webhookURL!!,
                WebHook.buildEmbed("Disconnected!", "The bot was disconnected! Attempting to reconnect...", JsonArray(), JsonObject(), author, thumbnail, 0xed6d66)
            )
            TimeUtils.setTimeout({
                reconnectTimer = TimeUtils.setInterval(this::reconnect, 0, 30000)
            }, RandomUtils.randomIntInRange(5000, 7000))
        }
    }

    private fun resetVars() {
        playersSent.clear()
        playersQuit.clear()
        calledFoundOpponent = false
        opponentTimer?.cancel()
        opponent = null
        combo = 0
        opponentCombo = 0
        ticksSinceHit = 0
        ticksSinceGameStart = 0
        resultCounted = false
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

    private fun handlePlayer(player: String) {
        thread {
            if (StateManager.state == StateManager.States.GAME) {
                if (player.length > 2) {
                    val needStats = (kira.config?.enableDodging == true) || (kira.config?.sendWebhookStats == true)

                    if (!needStats) {
                        if (mc.thePlayer != null && player == mc.thePlayer.displayNameString) {
                            onJoinGame()
                        }
                        return@thread
                    }

                    val uuid: String? = if (playerCache.containsKey(player)) {
                        playerCache[player]
                    } else {
                        HttpUtils.usernameToUUID(player)
                    }
                    println(player)

                    if (uuid == null) {
                        if (kira.config?.dodgeLostTo == true && playersLost.contains(player)) {
                            beforeLeave()
                            leaveGame()
                            TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(4000, 6000))
                        }
                    } else {
                        playerCache[player] = uuid
                        if (!playersSent.contains(player)) {
                            if (mc.thePlayer != null) {
                                if (player == mc.thePlayer.displayNameString) {
                                    onJoinGame()
                                } else {
                                    val stats = HttpUtils.getPlayerStats(uuid) ?: return@thread
                                    handleStats(stats, player)
                                }
                            } else {
                                val stats = HttpUtils.getPlayerStats(uuid) ?: return@thread
                                handleStats(stats, player)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleStats(stats: JsonObject, player: String) {
        if (toggled() && stats.get("success").asBoolean) {
            if (statKeys.containsKey("wins") && statKeys.containsKey("losses") && statKeys.containsKey("ws")) {
                fun getStat(key: String): JsonElement? {
                    var tmpObj = stats
                    for (p in key.split(".")) {
                        if (tmpObj.has(p) && tmpObj.get(p).isJsonObject) tmpObj = tmpObj.get(p).asJsonObject
                        else if (tmpObj.has(p)) return tmpObj.get(p)
                        else return null
                    }
                    return null
                }

                if (stats.get("player") == JsonNull.INSTANCE) return
                if (playersQuit.contains(player)) return
                if (!playersSent.contains(player)) playersSent.add(player) else return

                val wins = getStat(statKeys["wins"]!!)?.asInt ?: 0
                val losses = getStat(statKeys["losses"]!!)?.asInt ?: 0
                val ws = getStat(statKeys["ws"]!!)?.asInt ?: 0

                val df = DecimalFormat("#.##")
                df.roundingMode = RoundingMode.DOWN
                val wlr = wins.toDouble() / (if (losses == 0) 1.0 else losses.toDouble())

                ChatUtils.info("$player ${EnumChatFormatting.GOLD} >> ${EnumChatFormatting.GOLD}Wins: ${EnumChatFormatting.GREEN}$wins ${EnumChatFormatting.GOLD}WLR: ${EnumChatFormatting.GREEN}${df.format(wlr)} ${EnumChatFormatting.GOLD}WS: ${EnumChatFormatting.GREEN}$ws")

                if (kira.config?.sendWebhookStats == true) {
                    val fields = WebHook.buildFields(arrayListOf(
                        mapOf("name" to "Wins", "value" to "$wins", "inline" to "true"),
                        mapOf("name" to "W/L", "value" to df.format(wlr), "inline" to "true"),
                        mapOf("name" to "WS", "value" to "$ws", "inline" to "true")
                    ))
                    val footer = WebHook.buildFooter(ChatUtils.removeFormatting(Session.getSession()), "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
                    val author = WebHook.buildAuthor("Duck Dueller - ${getName()}", "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
                    val thumbnail = WebHook.buildThumbnail("https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")

                    WebHook.sendEmbed(
                        kira.config?.webhookURL!!,
                        WebHook.buildEmbed("Stats of $player:", "", fields, footer, author, thumbnail, 0x581ff2)
                    )
                }

                var dodge = false
                if (kira.config?.enableDodging == true) {
                    val config = kira.config
                    if (wins > config?.dodgeWins!!) dodge = true
                    else if (wlr > config.dodgeWLR) dodge = true
                    else if (ws > config.dodgeWS) dodge = true
                    else if (kira.config?.dodgeLostTo == true) {
                        if (playersLost.contains(player)) dodge = true
                    }
                }

                if (dodge) {
                    beforeLeave()
                    leaveGame()
                    sendDodgeWebhook(player)
                    TimeUtils.setTimeout(this::joinGame, RandomUtils.randomIntInRange(4000, 6000))
                }
            }
        } else if (toggled()) {
            ChatUtils.error("Error getting stats! Check the log for more information.")
            println("Error getting stats! success == false")
            println(kira.gson.toJson(stats))
        }
    }

    private fun sendDodgeWebhook(player: String) {
        if (kira.config?.sendWebhookDodge == true) {
            val footer = WebHook.buildFooter(ChatUtils.removeFormatting(Session.getSession()), "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
            val author = WebHook.buildAuthor("Duck Dueller - ${getName()}", "https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
            val thumbnail = WebHook.buildThumbnail("https://raw.githubusercontent.com/HumanDuck23/upload-stuff-here/main/duck_dueller.png")
            WebHook.sendEmbed(
                kira.config?.webhookURL!!,
                WebHook.buildEmbed("Dodged someone!", if (player != "") "Dodged $player." else "Dodged a nick!", JsonArray(), footer, author, thumbnail, 0x581ff2)
            )
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
