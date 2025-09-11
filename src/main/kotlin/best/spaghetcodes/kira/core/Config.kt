package best.spaghetcodes.kira.core

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.bots.*
import best.spaghetcodes.kira.gui.CustomConfigGUI
import gg.essential.vigilance.Vigilant
import gg.essential.vigilance.data.Property
import gg.essential.vigilance.data.PropertyType
import net.minecraft.client.gui.GuiScreen
import java.io.File

class Config : Vigilant(File(kira.configLocation), sortingBehavior = ConfigSorter()) {

    @Property(
        type = PropertyType.SELECTOR,
        name = "Current Bot",
        description = "The bot you want to use",
        category = "General",
        options = ["Classic", "ClassicV2", "OP", "Combo", "Sumo", "Boxing", "Bow", "Blitz"]
    )
    var currentBot = 0

    @Property(
        type = PropertyType.TEXT,
        name = "API Key",
        description = "This account's API key, can also be set using \"/api new\".",
        placeholder = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        category = "General",
    )
    var apiKey = ""

    @Property(type = PropertyType.SWITCH, name = "Lobby Movement", description = "Whether or not the bot should move in pre-game lobbies.", category = "General")
    var lobbyMovement = true

    @Property(type = PropertyType.SWITCH, name = "Disable Chat Messages", description = "When this is enabled, the bot will not send any chat messages.", category = "General")
    var disableChatMessages = false

    @Property(type = PropertyType.SWITCH, name = "Show Stats Overlay", description = "Display session stats on screen.", category = "General")
    var showStatsOverlay = true

    @Property(type = PropertyType.NUMBER, name = "Throw After X Games", description = "After X games the bot will underperform and throw the game. 0 = disabled.", category = "General", min = 0, max = 1000, increment = 10)
    var throwAfterGames = 0

    @Property(type = PropertyType.SLIDER, name = "Disconnect After X Games", description = "After X games the bot will toggle off and disconnect. 0 = disabled.", category = "General", min = 0, max = 10000)
    var disconnectAfterGames = 0

    @Property(type = PropertyType.NUMBER, name = "Disconnect After X Minutes", description = "After X minutes the bot will toggle off and disconnect. 0 = disabled", category = "General", min = 0, max = 500, increment = 30)
    var disconnectAfterMinutes = 0

    @Property(type = PropertyType.NUMBER, name = "Min CPS", description = "The minimum CPS that the bot will be clicking at.", category = "Combat", min = 1, max = 25, increment = 1)
    var minCPS = 15

    @Property(type = PropertyType.NUMBER, name = "Max CPS", description = "The maximum CPS that the bot will be clicking at.", category = "Combat", min = 1, max = 25, increment = 1)
    var maxCPS = 19

    @Property(type = PropertyType.NUMBER, name = "Horizontal Look Speed", description = "Horizontal look speed.", category = "Combat", min = 1, max = 50, increment = 1)
    var lookSpeedHorizontal = 14

    @Property(type = PropertyType.NUMBER, name = "Vertical Look Speed", description = "Vertical look speed.", category = "Combat", min = 1, max = 50, increment = 1)
    var lookSpeedVertical = 5

    @Property(type = PropertyType.DECIMAL_SLIDER, name = "Look Randomization", description = "Random offset added to view movement.", category = "Combat", minF = 0f, maxF = 2f)
    var lookRand = 0.3f

    @Property(type = PropertyType.NUMBER, name = "Max Look Distance", description = "Max distance for tracking.", category = "Combat", min = 10, max = 200, increment = 5)
    var maxDistanceLook = 150

    @Property(type = PropertyType.NUMBER, name = "Max Attack Distance", description = "Max distance for attacking.", category = "Combat", min = 3, max = 6, increment = 1)
    var maxDistanceAttack = 5

    @Property(type = PropertyType.SWITCH, name = "Kira Hit", description = "Automatically attack opponents.", category = "Combat")
    var kiraHit = true

    @Property(type = PropertyType.SWITCH, name = "Hit & Block", description = "Briefly block after successful sword hits.", category = "Combat")
    var hitBlock = false

    @Property(
        type = PropertyType.SELECTOR,
        name = "Hit & Block Mode",
        description = "How Hit & Block triggers.",
        category = "Combat",
        options = ["Chance", "Cooldown Hits"]
    )
    var hitBlockMode = 0

    @Property(type = PropertyType.NUMBER, name = "Hit & Block Min Interval", description = "Minimum interval between Hit & Block actions (ms).", category = "Combat", min = 0, max = 2000, increment = 10)
    var hitBlockMinInterval = 0

    @Property(type = PropertyType.NUMBER, name = "Hit & Block Chance", description = "Percentage chance for Hit & Block when in Chance mode.", category = "Combat", min = 0, max = 100, increment = 1)
    var hitBlockChance = 0

    @Property(type = PropertyType.NUMBER, name = "Hit & Block Min Hits", description = "Minimum successful hits before Hit & Block when in Cooldown mode.", category = "Combat", min = 1, max = 10, increment = 1)
    var hitBlockMinHits = 2

    @Property(type = PropertyType.NUMBER, name = "Hit & Block Max Hits", description = "Maximum successful hits before Hit & Block when in Cooldown mode.", category = "Combat", min = 1, max = 10, increment = 1)
    var hitBlockMaxHits = 4

    @Property(type = PropertyType.SWITCH, name = "Enable AutoGG", description = "Send a gg message after every game", category = "AutoGG")
    var sendAutoGG = true

    @Property(type = PropertyType.TEXT, name = "AutoGG Message", description = "AutoGG message the bot sends after every game", category = "AutoGG")
    var ggMessage = "gg"

    @Property(type = PropertyType.NUMBER, name = "AutoGG Delay", description = "How long to wait after the game before sending the message", category = "AutoGG", min = 50, max = 1000, increment = 50)
    var ggDelay = 100

    @Property(type = PropertyType.SWITCH, name = "Game Start Message", description = "Send a message as soon as the game starts", category = "AutoGG")
    var sendStartMessage = false

    @Property(type = PropertyType.TEXT, name = "Start Message", description = "Message to send at the beginning of the game", category = "AutoGG")
    var startMessage = "gl"

    @Property(type = PropertyType.NUMBER, name = "Start Message Delay", description = "How long to wait before sending the start message", category = "AutoGG", min = 50, max = 1000, increment = 50)
    var startMessageDelay = 100

    @Property(type = PropertyType.NUMBER, name = "Auto Requeue Delay", description = "How long to wait after a game before re-queueing", category = "Auto Requeue", min = 500, max = 5000, increment = 50)
    var autoRqDelay = 2500

    @Property(type = PropertyType.NUMBER, name = "Requeue After No Game", description = "How long to wait before re-queueing if no game starts", category = "Auto Requeue", min = 15, max = 60, increment = 5)
    var rqNoGame = 30

    @Property(type = PropertyType.SWITCH, name = "Paper Requeue", description = "Use the paper to requeue", category = "Auto Requeue")
    var paperRequeue = true

    @Property(type = PropertyType.SWITCH, name = "Fast Requeue", description = "Faster Requeue (no rewards)", category = "Auto Requeue")
    var fastRequeue = true

    @Property(type = PropertyType.SWITCH, name = "Enable Queue Dodging", description = "Whether or not the bot should dodge people based on stats", category = "Queue Dodging")
    var enableDodging = false

    @Property(type = PropertyType.SLIDER, name = "Dodge Wins", description = "How many wins a player can have before being dodged", category = "Queue Dodging", min = 500, max = 20000)
    var dodgeWins = 4000

    @Property(type = PropertyType.NUMBER, name = "Dodge WS", description = "How large a player's winstreak can be before being dodged", category = "Queue Dodging", min = 10, max = 100, increment = 5)
    var dodgeWS = 15

    @Property(type = PropertyType.DECIMAL_SLIDER, name = "Dodge W/L", description = "How large a player's w/l ratio can be before being dodged", category = "Queue Dodging", minF = 2f, maxF = 15f)
    var dodgeWLR = 3.0f

    @Property(type = PropertyType.PARAGRAPH, name = "Specific Players to Dodge", description = "Players to dodge regardless of stats (comma separated)", category = "Queue Dodging")
    var dodgePlayersList = ""

    @Property(type = PropertyType.SWITCH, name = "Dodge Lost To", description = "Whether or not the bot should dodge people it already lost against", category = "Queue Dodging")
    var dodgeLostTo = true

    @Property(type = PropertyType.SWITCH, name = "Dodge No Stats", description = "Whether or not the bot should dodge when no stats are found (nicked player or hypixel error)", category = "Queue Dodging")
    var dodgeNoStats = true

    @Property(type = PropertyType.SWITCH, name = "Strict Dodging", description = "If Hypixel prevents the bot from leaving (woah there, slow down!), it will disconnect and reconnect to dodge.", category = "Queue Dodging")
    var strictDodging = false

    @Property(type = PropertyType.SWITCH, name = "Send Webhook Messages", description = "Whether or not the bot should send a discord webhook message after each game.", category = "Webhook")
    var sendWebhookMessages = false

    @Property(type = PropertyType.TEXT, name = "Discord Webhook URL", description = "The webhook URL to send messages to.", category = "Webhook")
    var webhookURL = ""

    @Property(type = PropertyType.SWITCH, name = "Send Queue Stats", description = "Should the bot send the stats of the player in the lobby to the webhook?", category = "Webhook")
    var sendWebhookStats = false

    @Property(type = PropertyType.SWITCH, name = "Send Dodge Alerts", description = "If enabled, the bot will send a webhook whenever it dodged a player/nick.", category = "Webhook")
    var sendWebhookDodge = false

    @Property(type = PropertyType.SWITCH, name = "Boxing Fish", description = "Switch between the sword and the fish in boxing.", category = "Misc")
    var boxingFish = false

    // --- Typage explicite + ordre conservé (utile pour l'UI) ---
    val bots: Map<Int, BotBase> = linkedMapOf(
        0 to Classic(),
        1 to ClassicV2(),
        2 to OP(),
        3 to Combo(),
        4 to Sumo(),
        5 to Boxing(),
        6 to BowDuel(),
        7 to Blitz()
    )

    /** Accès typé et sûr depuis le reste du code (élimine tout Any aux call-sites). */
    fun getBot(idx: Int): BotBase? = bots[idx]

    init {
        addDependency("webhookURL", "sendWebhookMessages")
        addDependency("ggMessage", "sendAutoGG")
        addDependency("ggDelay", "sendAutoGG")
        addDependency("startMessage", "sendStartMessage")
        addDependency("startMessageDelay", "sendStartMessage")
        addDependency("dodgeWins", "enableDodging")
        addDependency("dodgeWS", "enableDodging")
        addDependency("dodgeWLR", "enableDodging")
        addDependency("dodgeLostTo", "enableDodging")
        addDependency("dodgeNoStats", "enableDodging")

        // Toujours utiliser getBot ici -> pas d'Any
        registerListener("currentBot") { idx: Int ->
            getBot(idx)?.let { kira.swapBot(it) }
        }

        initialize()
    }

    fun getCustomGui(): GuiScreen = CustomConfigGUI()
}
