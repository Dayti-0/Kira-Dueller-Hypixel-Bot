package best.spaghetcodes.kira.gui

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.utils.ChatUtils
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.max
import kotlin.math.min

class CustomConfigGUI : GuiScreen() {

    private var currentTab = 0
    private val tabNames = listOf("General", "Combat", "Stats")
    private var fadeIn = 0f

    private var scroll = 0
    private var maxScroll = 0

    private val primaryColor = Color(0, 240, 255, 255).rgb
    private val backgroundColor = Color(15, 15, 25, 240).rgb
    private val cardColor = Color(25, 25, 40, 200).rgb
    private val cardShadow = Color(10, 10, 20, 140).rgb

    private data class Rect(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)
    private val hotspots = mutableListOf<Rect>()

    private enum class Tf { NONE, START_MSG, GG_MSG }
    private var focus = Tf.NONE
    private var startMsgBuf = ""
    private var ggMsgBuf = ""

    override fun initGui() {
        super.initGui()
        fadeIn = 0f
        scroll = 0
        maxScroll = 0
        Keyboard.enableRepeatEvents(true)

        val cfg = kira.config
        startMsgBuf = cfg?.startMessage ?: ""
        ggMsgBuf = cfg?.ggMessage ?: ""

        buttonList.clear()
        buttonList.add(GuiButton(100, width / 2 - 60, height - 30, 120, 20, "Save & Close"))
    }

    override fun onGuiClosed() {
        super.onGuiClosed()
        Keyboard.enableRepeatEvents(false)
        focus = Tf.NONE
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val dWheel = Mouse.getEventDWheel()
        if (dWheel != 0) {
            val delta = if (dWheel > 0) -30 else 30
            scroll = (scroll + delta).coerceIn(0, maxScroll)
        }
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        hotspots.clear()
        if (fadeIn < 1f) fadeIn = min(1f, fadeIn + 0.05f)

        // Fond
        drawGradientRect(
            0, 0, width, height,
            Color(10, 10, 20, (250 * fadeIn).toInt()).rgb,
            Color(30, 10, 50, (250 * fadeIn).toInt()).rgb
        )

        // Container
        val containerX = 40
        val containerY = 40
        val containerW = width - 80
        val containerH = height - 80

        drawRect(containerX + 2, containerY + 2, containerX + containerW + 2, containerY + containerH + 2, cardShadow)
        drawRect(containerX, containerY, containerX + containerW, containerY + containerH, backgroundColor)
        drawBorder(containerX, containerY, containerW, containerH, primaryColor)

        drawCenteredString(fontRendererObj, "§lKIRA CONFIG", width / 2, 15, primaryColor)

        // Tabs
        drawTabs(containerX, containerY - 25)

        // Zone scrollable
        val contentX = containerX + 20
        val contentY = containerY + 20
        val innerW = containerW - 40
        val innerH = containerH - 40

        scissor(contentX, contentY, innerW, innerH) {
            val endY = when (currentTab) {
                0 -> drawGeneralTab(contentX, contentY)
                1 -> drawCombatTab(contentX, contentY)
                else -> drawStatsTab(contentX, contentY)
            }
            val contentH = (endY - contentY).coerceAtLeast(0)
            maxScroll = (contentH - innerH).coerceAtLeast(0)
            if (scroll > maxScroll) scroll = maxScroll
        }

        drawScrollbar(contentX + innerW - 4, contentY, innerH)

        val status = if (kira.bot?.toggled() == true) "§aONLINE" else "§cOFFLINE"
        drawString(fontRendererObj, "Status: $status", width - 80, height - 10, -1)
        drawString(fontRendererObj, "§7KIRA UI", 5, height - 10, Color.GRAY.rgb)

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    // ----------------- UI helpers -----------------

    private fun drawBorder(x: Int, y: Int, w: Int, h: Int, color: Int) {
        drawHorizontalLine(x, x + w, y, color)
        drawHorizontalLine(x, x + w, y + h, color)
        drawVerticalLine(x, y, y + h, color)
        drawVerticalLine(x + w, y, y + h, color)
    }

    private fun addHotspot(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hotspots += Rect(x, y, x + w, y + h, onClick)
    }

    private fun drawTabs(x: Int, y: Int) {
        var tabX = x
        tabNames.forEachIndexed { index, name ->
            val selected = index == currentTab
            val bg = if (selected) Color(0, 240, 255, 100).rgb else Color(40, 40, 60, 100).rgb
            drawRect(tabX, y, tabX + 100, y + 25, bg)
            drawCenteredString(fontRendererObj, name, tabX + 50, y + 8, if (selected) primaryColor else -1)
            addHotspot(tabX, y, 100, 25) {
                currentTab = index
                scroll = 0
                focus = Tf.NONE
            }
            tabX += 102
        }
    }

    private fun drawButton(x: Int, y: Int, w: Int, h: Int, label: String, active: Boolean = true) {
        val bg = if (active) Color(40, 40, 60, 160).rgb else Color(60, 60, 60, 120).rgb
        drawRect(x, y, x + w, y + h, bg)
        drawCenteredString(fontRendererObj, label, x + w / 2, y + 6, if (active) -1 else Color(160,160,160).rgb)
    }

    private fun selector(label: String, x: Int, y: Int, get: () -> Int, set: (Int) -> Unit, options: List<String>) {
        val cur = min(max(0, get()), options.lastIndex)
        drawString(fontRendererObj, "$label:", x, y - scroll, -1)
        drawButton(x + 110, y - 4 - scroll, 18, 18, "«")
        addHotspot(x + 110, y - 4 - scroll, 18, 18) {
            val v = if (cur <= 0) options.lastIndex else cur - 1
            set(v)
        }
        drawRect(x + 132, y - 6 - scroll, x + 282, y + 14 - scroll, cardColor)
        drawCenteredString(fontRendererObj, options[cur], x + 207, y - scroll, primaryColor)
        drawButton(x + 286, y - 4 - scroll, 18, 18, "»")
        addHotspot(x + 286, y - 4 - scroll, 18, 18) {
            val v = if (cur >= options.lastIndex) 0 else cur + 1
            set(v)
        }
    }

    private fun toggle(label: String, x: Int, y: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val v = get()
        drawString(fontRendererObj, label, x, y - scroll, -1)
        val boxX = x + 260; val boxY = y - 2 - scroll; val w = 60; val h = 18
        drawRect(boxX, boxY, boxX + w, boxY + h, if (v) Color(30, 90, 30, 200).rgb else Color(90, 30, 30, 200).rgb)
        drawCenteredString(fontRendererObj, if (v) "ON" else "OFF", boxX + w/2, boxY + 5, if (v) Color(0x55FF55).rgb else Color(0xFF5555).rgb)
        addHotspot(boxX, boxY, w, h) { set(!get()) }
    }

    private fun number(label: String, x: Int, y: Int, get: () -> Int, set: (Int) -> Unit, minV: Int, maxV: Int, step: Int = 1) {
        val v = get()
        drawString(fontRendererObj, label, x, y - scroll, -1)
        drawButton(x + 200, y - 4 - scroll, 18, 18, "–")
        addHotspot(x + 200, y - 4 - scroll, 18, 18) { set(max(minV, v - step)) }
        drawRect(x + 222, y - 6 - scroll, x + 282, y + 14 - scroll, cardColor)
        drawCenteredString(fontRendererObj, v.toString(), x + 252, y - scroll, primaryColor)
        drawButton(x + 286, y - 4 - scroll, 18, 18, "+")
        addHotspot(x + 286, y - 4 - scroll, 18, 18) { set(min(maxV, v + step)) }
    }

    private fun decimal(label: String, x: Int, y: Int, get: () -> Float, set: (Float) -> Unit, minV: Float, maxV: Float, step: Float = 0.05f) {
        val v = get()
        drawString(fontRendererObj, label, x, y - scroll, -1)
        drawButton(x + 200, y - 4 - scroll, 18, 18, "–")
        addHotspot(x + 200, y - 4 - scroll, 18, 18) { set(max(minV, (v - step))) }
        drawRect(x + 222, y - 6 - scroll, x + 282, y + 14 - scroll, cardColor)
        drawCenteredString(fontRendererObj, String.format("%.2f", v), x + 252, y - scroll, primaryColor)
        drawButton(x + 286, y - 4 - scroll, 18, 18, "+")
        addHotspot(x + 286, y - 4 - scroll, 18, 18) { set(min(maxV, (v + step))) }
    }

    /** Champ texte simple. Retourne le nouveau Y (avec marge). */
    private fun textField(
        label: String,
        x: Int,
        y: Int,
        buf: String,
        isFocused: Boolean,
        onFocus: () -> Unit
    ): Int {
        val fieldY = y - scroll
        drawString(fontRendererObj, label, x, fieldY, -1)
        val boxX = x + 140
        val boxY = fieldY - 4
        val w = 260
        val h = 18
        drawRect(boxX - 1, boxY - 1, boxX + w + 1, boxY + h + 1, cardShadow)
        drawRect(boxX, boxY, boxX + w, boxY + h, cardColor)
        val textShown = if (isFocused && (System.currentTimeMillis() / 500) % 2L == 0L) "$buf|" else buf
        drawString(fontRendererObj, textShown, boxX + 4, boxY + 5, primaryColor)
        addHotspot(boxX, boxY, w, h) { onFocus() }
        return y + 20
    }

    /** Petite carte “stat” pour l’onglet Stats. */
    private fun drawStatCard(title: String, x: Int, y: Int, value: String, color: Int) {
        drawRect(x + 2, y + 2, x + 102, y + 52, cardShadow)
        drawRect(x, y, x + 100, y + 50, cardColor)
        drawString(fontRendererObj, title, x + 5, y + 5, Color.GRAY.rgb)
        GL11.glPushMatrix()
        GL11.glTranslatef((x + 50).toFloat(), (y + 25).toFloat(), 0f)
        GL11.glScalef(1.5f, 1.5f, 1f)
        drawCenteredString(fontRendererObj, value, 0, 0, color)
        GL11.glPopMatrix()
    }

    // ----------------- Tabs content -----------------

    private fun drawGeneralTab(x: Int, yStart: Int): Int {
        var y = yStart
        drawString(fontRendererObj, "§lGENERAL SETTINGS", x, y - scroll, primaryColor); y += 25

        val cfg = kira.config ?: return y

        val botNames = listOf("Classic", "ClassicV2", "OP", "Combo", "Sumo", "Boxing", "Bow", "Blitz")
        selector("Current Bot", x, y, { cfg.currentBot }, { v ->
            cfg.currentBot = v
            kira.config?.getBot(v)?.let { kira.swapBot(it) }
        }, botNames); y += 24

        toggle("Lobby Movement", x, y, { cfg.lobbyMovement }, { cfg.lobbyMovement = it }); y += 20
        toggle("Fast Requeue", x, y, { cfg.fastRequeue }, { cfg.fastRequeue = it }); y += 20
        toggle("Paper Requeue", x, y, { cfg.paperRequeue }, { cfg.paperRequeue = it }); y += 20
        toggle("Disable Chat Messages", x, y, { cfg.disableChatMessages }, { cfg.disableChatMessages = it }); y += 24

        number("Disconnect After Games", x, y, { cfg.disconnectAfterGames }, { cfg.disconnectAfterGames = it }, 0, 10000, 10); y += 20
        number("Disconnect After Minutes", x, y, { cfg.disconnectAfterMinutes }, { cfg.disconnectAfterMinutes = it }, 0, 500, 5); y += 24
        number("Auto Requeue Delay (ms)", x, y, { cfg.autoRqDelay }, { cfg.autoRqDelay = it }, 500, 5000, 50); y += 20
        number("Requeue After No Game (s)", x, y, { cfg.rqNoGame }, { cfg.rqNoGame = it }, 15, 60, 1); y += 24

        toggle("Game Start Message", x, y, { cfg.sendStartMessage }, { cfg.sendStartMessage = it }); y += 20
        y = textField(
            label = "Start Message",
            x = x,
            y = y,
            buf = startMsgBuf,
            isFocused = (focus == Tf.START_MSG),
            onFocus = { focus = Tf.START_MSG }
        )
        number("Start Message Delay (ms)", x, y, { cfg.startMessageDelay }, { cfg.startMessageDelay = it }, 50, 1000, 50); y += 24

        toggle("Enable AutoGG", x, y, { cfg.sendAutoGG }, { cfg.sendAutoGG = it }); y += 20
        y = textField(
            label = "AutoGG Message",
            x = x,
            y = y,
            buf = ggMsgBuf,
            isFocused = (focus == Tf.GG_MSG),
            onFocus = { focus = Tf.GG_MSG }
        )
        number("AutoGG Delay (ms)", x, y, { cfg.ggDelay }, { cfg.ggDelay = it }, 50, 1000, 50); y += 20

        return y
    }

    private fun drawCombatTab(x: Int, yStart: Int): Int {
        var y = yStart
        drawString(fontRendererObj, "§lCOMBAT SETTINGS", x, y - scroll, primaryColor); y += 25

        val cfg = kira.config ?: return y

        number("Min CPS", x, y, { cfg.minCPS }, { cfg.minCPS = it }, 15, 25, 1); y += 20
        number("Max CPS", x, y, { cfg.maxCPS }, { cfg.maxCPS = it }, 19, 25, 1); y += 24
        number("Horizontal Look Speed", x, y, { cfg.lookSpeedHorizontal }, { cfg.lookSpeedHorizontal = it }, 1, 50, 1); y += 20
        number("Vertical Look Speed", x, y, { cfg.lookSpeedVertical }, { cfg.lookSpeedVertical = it }, 1, 50, 1); y += 20
        decimal("Look Randomization", x, y, { cfg.lookRand }, { cfg.lookRand = it }, 0f, 2f, 0.05f); y += 24
        number("Max Look Distance", x, y, { cfg.maxDistanceLook }, { cfg.maxDistanceLook = it }, 10, 200, 5); y += 20
        number("Max Attack Distance", x, y, { cfg.maxDistanceAttack }, { cfg.maxDistanceAttack = it }, 3, 6, 1); y += 20
        toggle("Kira Hit", x, y, { cfg.kiraHit }, { cfg.kiraHit = it }); y += 24

        toggle("Hit & Block", x, y, { cfg.hitBlock }, { cfg.hitBlock = it }); y += 24
        selector(
            "H&B Mode",
            x,
            y,
            { cfg.hitBlockMode },
            { cfg.hitBlockMode = it },
            listOf("Chance", "Cooldown Hits")
        );
        y += 24
        number(
            "H&B Min Interval (ms)",
            x,
            y,
            { cfg.hitBlockMinInterval },
            { cfg.hitBlockMinInterval = it },
            0,
            2000,
            10
        );
        y += 20
        number("H&B Chance (%)", x, y, { cfg.hitBlockChance }, { cfg.hitBlockChance = it }, 0, 100, 1); y += 20
        number("H&B Min Hits", x, y, { cfg.hitBlockMinHits }, { cfg.hitBlockMinHits = it }, 1, 10, 1); y += 20
        number("H&B Max Hits", x, y, { cfg.hitBlockMaxHits }, { cfg.hitBlockMaxHits = it }, 1, 10, 1); y += 24

        // Option Boxing Fish dans l’onglet Combat
        toggle("Boxing: Use Fish", x, y, { cfg.boxingFish }, { cfg.boxingFish = it }); y += 20

        return y
    }

    private fun drawStatsTab(x: Int, yStart: Int): Int {
        var y = yStart
        drawString(fontRendererObj, "§lSESSION STATISTICS", x, y - scroll, primaryColor); y += 25
        val cfg = kira.config
        cfg?.let { config ->
            toggle("Show Stats Overlay", x, y, { config.showStatsOverlay }, { config.showStatsOverlay = it })
            y += 24
        }

        val wins = Session.wins
        val losses = Session.losses
        val total = wins + losses
        val wlr = if (losses == 0) wins.toFloat() else wins.toFloat() / losses
        val winrate = if (total == 0) 0f else (wins.toFloat() / total) * 100f

        drawStatCard("WINS", x, y - scroll, wins.toString(), Color.GREEN.rgb)
        drawStatCard("LOSSES", x + 120, y - scroll, losses.toString(), Color.RED.rgb)
        drawStatCard("W/L RATIO", x + 240, y - scroll, String.format("%.2f", wlr), primaryColor); y += 60

        drawStatCard("WIN RATE", x, y - scroll, String.format("%.1f%%", winrate), Color.CYAN.rgb)
        drawStatCard("GAMES", x + 120, y - scroll, total.toString(), Color.YELLOW.rgb)

        if (Session.startTime > 0) {
            val minutes = max(0L, (System.currentTimeMillis() - Session.startTime) / 1000 / 60)
            drawStatCard("TIME", x + 240, y - scroll, "${minutes}m", Color.MAGENTA.rgb)
        }
        y += 60

        return y
    }

    // ----------------- utilities -----------------

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        super.mouseClicked(mouseX, mouseY, mouseButton)
        var clickedTextField = false
        hotspots.firstOrNull { mouseX in it.x1..it.x2 && mouseY in it.y1..it.y2 }?.let {
            it.onClick.invoke()
            clickedTextField = (focus != Tf.NONE)
        }
        if (!clickedTextField) {
            focus = Tf.NONE
        }
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            saveAndClose(); return
        }
        when (focus) {
            Tf.START_MSG -> {
                when (keyCode) {
                    Keyboard.KEY_BACK -> if (startMsgBuf.isNotEmpty()) startMsgBuf = startMsgBuf.dropLast(1)
                    Keyboard.KEY_RETURN -> focus = Tf.NONE
                    else -> if (isPrintable(typedChar)) startMsgBuf += typedChar
                }
            }
            Tf.GG_MSG -> {
                when (keyCode) {
                    Keyboard.KEY_BACK -> if (ggMsgBuf.isNotEmpty()) ggMsgBuf = ggMsgBuf.dropLast(1)
                    Keyboard.KEY_RETURN -> focus = Tf.NONE
                    else -> if (isPrintable(typedChar)) ggMsgBuf += typedChar
                }
            }
            else -> super.keyTyped(typedChar, keyCode)
        }
    }

    override fun actionPerformed(button: GuiButton) {
        when (button.id) {
            100 -> saveAndClose()
        }
    }

    private fun saveAndClose() {
        kira.config?.let {
            it.startMessage = startMsgBuf
            it.ggMessage = ggMsgBuf
            it.writeData()
        }
        ChatUtils.info("Configuration saved!")
        mc.displayGuiScreen(null)
    }

    override fun doesGuiPauseGame(): Boolean = false

    private inline fun scissor(x: Int, y: Int, w: Int, h: Int, draw: () -> Unit) {
        val sf = ScaledResolution(mc).scaleFactor
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(x * sf, (height - (y + h)) * sf, w * sf, h * sf)
        try {
            draw()
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
        }
    }

    private fun drawScrollbar(x: Int, y: Int, innerH: Int) {
        if (maxScroll <= 0) return
        drawRect(x, y, x + 2, y + innerH, Color(60, 60, 80, 120).rgb)
        val contentH = maxScroll + innerH
        val thumbH = max(20, (innerH.toFloat() * innerH / contentH).toInt())
        val thumbY = y + ((innerH - thumbH).toFloat() * (scroll.toFloat() / maxScroll)).toInt()
        drawRect(x - 1, thumbY, x + 3, thumbY + thumbH, primaryColor)
    }

    private fun isPrintable(c: Char): Boolean = c.code in 32..126
}
