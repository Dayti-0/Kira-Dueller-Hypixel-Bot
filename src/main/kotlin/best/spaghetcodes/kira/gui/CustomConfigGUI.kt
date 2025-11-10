package best.spaghetcodes.kira.gui

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.utils.ChatUtils
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CustomConfigGUI : GuiScreen() {

    private var currentTab = 0
    private val tabNames = listOf("General", "Combat", "Stats")

    private var fadeIn = 0f
    private var scroll = 0
    private var maxScroll = 0
    private var hoverX = 0
    private var hoverY = 0

    private val primaryColor = Color(0, 240, 255).rgb
    private val grayColor = Color(150, 150, 150).rgb
    private val darkGrayColor = Color(30, 30, 45).rgb
    private val highlightColor = Color.WHITE.rgb
    private val successColor = Color(85, 255, 85).rgb
    private val failureColor = Color(255, 85, 85).rgb
    private val accentColor = Color(40, 40, 60).rgb

    private val ROW_HEIGHT = 18
    private val SECTION_SPACING = 18

    private data class Rect(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)

    private data class ToggleSpec(
        val label: String,
        val getter: () -> Boolean,
        val setter: (Boolean) -> Unit
    )

    private val hotspots = mutableListOf<Rect>()

    override fun initGui() {
        super.initGui()
        fadeIn = 0f
        scroll = 0
        maxScroll = 0
        Keyboard.enableRepeatEvents(true)
        buttonList.clear()
    }

    override fun onGuiClosed() {
        super.onGuiClosed()
        Keyboard.enableRepeatEvents(false)
        saveConfig()
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val dWheel = Mouse.getEventDWheel()
        if (dWheel != 0) {
            val delta = if (dWheel > 0) -20 else 20
            scroll = (scroll + delta).coerceIn(0, maxScroll)
        }
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        hoverX = mouseX
        hoverY = mouseY
        hotspots.clear()
        if (fadeIn < 1f) fadeIn = min(1f, fadeIn + 0.05f)

        drawRect(0, 0, width, height, Color(10, 10, 15, (240 * fadeIn).toInt()).rgb)

        val contentWidth = 500
        val startX = (width - contentWidth) / 2
        val startY = 80
        val endY = height - 60

        drawCenteredString(fontRendererObj, "KIRA CONFIG", width / 2, 40, highlightColor)
        drawTabs(startX, 60, contentWidth)

        scissor(0, startY - 10, width, endY - startY + 20) {
            val finalY = when (currentTab) {
                0 -> drawGeneralTab(startX, startY)
                1 -> drawCombatTab(startX, startY)
                else -> drawStatsTab(startX, startY)
            }
            maxScroll = max(0, finalY - endY)
        }

        val footerY = height - 40
        val status = if (kira.bot?.toggled() == true) "Status: §aONLINE" else "Status: §cOFFLINE"
        drawString(fontRendererObj, status, 20, footerY + 4, -1)

        val saveLabel = "Save & Close"
        val saveW = fontRendererObj.getStringWidth(saveLabel)
        val saveX = width - saveW - 20
        val isSaveHovered = isHovered(saveX, footerY, saveW, 12)
        drawString(fontRendererObj, saveLabel, saveX, footerY + 4, if (isSaveHovered) primaryColor else highlightColor)
        addHotspot(saveX, footerY, saveW, 12) { this.mc.displayGuiScreen(null) }

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    private fun isHovered(x: Int, y: Int, w: Int, h: Int) = hoverX in x..(x + w) && hoverY in y..(y + h)

    private fun addHotspot(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hotspots += Rect(x, y, x + w, y + h, onClick)
    }

    private fun drawTabs(x: Int, y: Int, containerWidth: Int) {
        val totalTabsWidth = tabNames.sumOf { fontRendererObj.getStringWidth(it) }
        val tabSpacing = 40
        var currentX = x + (containerWidth - (totalTabsWidth + tabSpacing * (tabNames.size - 1))) / 2

        tabNames.forEachIndexed { index, name ->
            val w = fontRendererObj.getStringWidth(name)
            val selected = index == currentTab
            val hovered = isHovered(currentX, y, w, 12)
            val color = if (selected) primaryColor else if (hovered) highlightColor else grayColor

            drawCenteredString(fontRendererObj, name, currentX + w / 2, y, color)
            if (selected) {
                drawRect(currentX, y + 11, currentX + w, y + 12, primaryColor)
            }
            addHotspot(currentX, y, w, 12) {
                currentTab = index
                scroll = 0
            }
            currentX += w + tabSpacing
        }
    }

    private fun drawSectionHeader(label: String, x: Int, y: Int, width: Int): Int {
        val yPos = y - scroll
        drawString(fontRendererObj, "§l$label", x, yPos, highlightColor)
        drawHorizontalLine(x, x + width, yPos + 12, Color(120, 120, 120, 100).rgb)
        return y + ROW_HEIGHT + 4
    }

    private fun selector(
        label: String,
        x: Int,
        y: Int,
        width: Int,
        get: () -> Int,
        set: (Int) -> Unit,
        options: List<String>
    ) {
        val yPos = y - scroll
        val controlEdgeX = x + width
        val cur = min(max(0, get()), options.lastIndex)
        val value = options.getOrElse(cur) { "N/A" }
        drawString(fontRendererObj, label, x, yPos, highlightColor)

        val valueW = fontRendererObj.getStringWidth(value)
        val arrowChar = ">"
        val arrowW = fontRendererObj.getStringWidth(arrowChar)
        val interactiveWidth = 140

        val rightArrowX = controlEdgeX - arrowW
        val leftArrowX = controlEdgeX - interactiveWidth

        val leftHover = isHovered(leftArrowX, yPos - 1, arrowW + 10, 10)
        val rightHover = isHovered(rightArrowX - 10, yPos - 1, arrowW + 10, 10)

        // Center the text between the fixed arrows
        val textContainerStart = leftArrowX + arrowW + 10
        val textContainerEnd = rightArrowX - 10
        val textX = textContainerStart + (textContainerEnd - textContainerStart - valueW) / 2

        // Draw shadows/outlines
        drawString(fontRendererObj, "<", leftArrowX + 1, yPos + 1, Color.BLACK.rgb)
        drawString(fontRendererObj, arrowChar, rightArrowX + 1, yPos + 1, Color.BLACK.rgb)
        drawString(fontRendererObj, value, textX + 1, yPos + 1, Color(primaryColor).darker().darker().rgb)

        // Draw main text and arrows
        drawString(fontRendererObj, "<", leftArrowX, yPos, if (leftHover) highlightColor else grayColor)
        drawString(fontRendererObj, value, textX, yPos, primaryColor)
        drawString(fontRendererObj, arrowChar, rightArrowX, yPos, if (rightHover) highlightColor else grayColor)

        addHotspot(leftArrowX, yPos - 1, arrowW + 10, 10) { set(if (cur <= 0) options.lastIndex else cur - 1) }
        addHotspot(rightArrowX - 10, yPos - 1, arrowW + 10, 10) { set(if (cur >= options.lastIndex) 0 else cur + 1) }
    }

    private fun toggle(label: String, x: Int, y: Int, width: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val yPos = y - scroll
        val controlEdgeX = x + width
        val v = get()
        drawString(fontRendererObj, label, x, yPos, highlightColor)

        val trackWidth = 32
        val trackHeight = 14
        val trackX = controlEdgeX - trackWidth
        val trackY = yPos - trackHeight / 2
        val hovered = isHovered(trackX, trackY, trackWidth, trackHeight)
        val pressed = hovered && Mouse.isButtonDown(0)

        val baseTrack = if (v) primaryColor else accentColor
        val baseBorder = if (v) lighten(primaryColor, 0.15f) else darkGrayColor
        val knobBase = if (v) Color(230, 255, 245).rgb else Color(215, 220, 235).rgb

        var trackColor = baseTrack
        var borderColor = baseBorder
        var knobColor = knobBase

        if (hovered) {
            trackColor = lighten(trackColor, 0.12f)
            borderColor = lighten(borderColor, 0.08f)
            knobColor = lighten(knobColor, 0.08f)
        }

        if (pressed) {
            trackColor = darken(trackColor, 0.10f)
            borderColor = darken(borderColor, 0.06f)
            knobColor = darken(knobColor, 0.10f)
        }

        drawCapsule(trackX, trackY, trackWidth, trackHeight, borderColor)
        drawCapsule(trackX + 1, trackY + 1, trackWidth - 2, trackHeight - 2, trackColor)

        val knobDiameter = trackHeight - 4
        val knobX = trackX + 2 + if (v) trackWidth - knobDiameter - 4 else 0
        val knobY = trackY + 2

        val knobBorder = if (v) lighten(borderColor, 0.25f) else lighten(darkGrayColor, 0.2f)
        drawCapsule(knobX, knobY, knobDiameter, knobDiameter, knobBorder)
        drawCapsule(knobX + 1, knobY + 1, knobDiameter - 2, knobDiameter - 2, knobColor)

        if (!v && hovered) {
            val indicatorY = knobY + knobDiameter / 2
            drawRect(trackX + 4, indicatorY, trackX + trackWidth - 4, indicatorY + 1, lighten(trackColor, 0.25f))
        }

        addHotspot(trackX, trackY, trackWidth, trackHeight) { set(!v) }
    }

    private fun drawCapsule(x: Int, y: Int, width: Int, height: Int, color: Int) {
        if (width <= 0 || height <= 0) return

        val radius = min(width, height) / 2f
        val leftCenter = x + radius
        val rightCenter = x + width - radius

        for (row in 0 until height) {
            val dy = radius - (row + 0.5f)
            val dx = sqrt(max(0f, radius * radius - dy * dy))
            val left = floor(leftCenter - dx).toInt()
            val right = ceil(rightCenter + dx).toInt()
            if (right > left) {
                drawRect(left, y + row, right, y + row + 1, color)
            }
        }
    }

    private fun lighten(color: Int, amount: Float): Int = blend(color, -0x1, amount)

    private fun darken(color: Int, amount: Float): Int = blend(color, 0xFF000000.toInt(), amount)

    private fun blend(color: Int, target: Int, amount: Float): Int {
        val ratio = amount.coerceIn(0f, 1f)
        val inverse = 1f - ratio

        val a = ((color ushr 24) and 0xFF) * inverse + ((target ushr 24) and 0xFF) * ratio
        val r = ((color ushr 16) and 0xFF) * inverse + ((target ushr 16) and 0xFF) * ratio
        val g = ((color ushr 8) and 0xFF) * inverse + ((target ushr 8) and 0xFF) * ratio
        val b = (color and 0xFF) * inverse + (target and 0xFF) * ratio

        return (a.roundToInt() shl 24) or (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or b.roundToInt()
    }

    private fun drawToggleSection(
        label: String,
        x: Int,
        yStart: Int,
        width: Int,
        toggles: List<ToggleSpec>
    ): Int {
        if (toggles.isEmpty()) return yStart

        var y = drawSectionHeader(label, x, yStart, width)
        y += SECTION_SPACING

        toggles.forEach { spec ->
            toggle(spec.label, x, y, width, spec.getter, spec.setter)
            y += ROW_HEIGHT
        }

        y += SECTION_SPACING / 2

        return y
    }

    private fun numberControl(
        label: String,
        x: Int,
        y: Int,
        width: Int,
        valueStr: String,
        onDecrement: () -> Unit,
        onIncrement: () -> Unit
    ) {
        val yPos = y - scroll
        val controlEdgeX = x + width
        drawString(fontRendererObj, label, x, yPos, highlightColor)

        val valueW = fontRendererObj.getStringWidth(valueStr)
        val symbolW = fontRendererObj.getStringWidth("-")
        val padding = 8

        val plusX = controlEdgeX - symbolW
        val valueX = plusX - padding - valueW
        val minusX = valueX - padding - symbolW

        val minusHover = isHovered(minusX, yPos - 1, symbolW, 10)
        val plusHover = isHovered(plusX, yPos - 1, symbolW, 10)

        drawString(fontRendererObj, "-", minusX, yPos, if (minusHover) highlightColor else grayColor)
        drawString(fontRendererObj, valueStr, valueX, yPos, highlightColor)
        drawString(fontRendererObj, "+", plusX, yPos, if (plusHover) highlightColor else grayColor)

        addHotspot(minusX, yPos - 1, symbolW, 10, onDecrement)
        addHotspot(plusX, yPos - 1, symbolW, 10, onIncrement)
    }

    private fun number(
        label: String,
        x: Int,
        y: Int,
        width: Int,
        get: () -> Int,
        set: (Int) -> Unit,
        minV: Int,
        maxV: Int,
        step: Int = 1
    ) {
        numberControl(
            label,
            x,
            y,
            width,
            get().toString(),
            { set(max(minV, get() - step)) },
            { set(min(maxV, get() + step)) })
    }

    private fun decimal(
        label: String,
        x: Int,
        y: Int,
        width: Int,
        get: () -> Float,
        set: (Float) -> Unit,
        minV: Float,
        maxV: Float,
        step: Float = 0.05f
    ) {
        numberControl(
            label,
            x,
            y,
            width,
            String.format("%.2f", get()),
            { set(max(minV, get() - step)) },
            { set(min(maxV, get() + step)) })
    }

    private fun drawGeneralTab(x: Int, yStart: Int): Int {
        var y = yStart
        val cfg = kira.config ?: return y
        val width = 500

        y = drawSectionHeader("Bot Behavior", x, y, width); y += SECTION_SPACING
        val botNames = listOf("Classic", "ClassicV2", "OP", "Combo", "Sumo", "Boxing", "Bow", "Blitz")
        selector(
            "Current Bot",
            x,
            y,
            width,
            { cfg.currentBot },
            { v -> cfg.currentBot = v; kira.config?.getBot(v)?.let { kira.swapBot(it) } },
            botNames
        ); y += ROW_HEIGHT
        toggle("Lobby Movement", x, y, width, { cfg.lobbyMovement }, { cfg.lobbyMovement = it }); y += ROW_HEIGHT
        toggle("Fast Requeue", x, y, width, { cfg.fastRequeue }, { cfg.fastRequeue = it }); y += ROW_HEIGHT
        toggle("Paper Requeue", x, y, width, { cfg.paperRequeue }, { cfg.paperRequeue = it }); y += ROW_HEIGHT
        toggle(
            "Disable Chat Messages",
            x,
            y,
            width,
            { cfg.disableChatMessages },
            { cfg.disableChatMessages = it }); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader("Auto Disconnect", x, y, width); y += SECTION_SPACING
        number(
            "After X Games",
            x,
            y,
            width,
            { cfg.disconnectAfterGames },
            { cfg.disconnectAfterGames = it },
            0,
            10000,
            10
        ); y += ROW_HEIGHT
        number(
            "After X Minutes",
            x,
            y,
            width,
            { cfg.disconnectAfterMinutes },
            { cfg.disconnectAfterMinutes = it },
            0,
            500,
            5
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader("Requeue Timings", x, y, width); y += SECTION_SPACING
        number(
            "Auto Requeue Delay (ms)",
            x,
            y,
            width,
            { cfg.autoRqDelay },
            { cfg.autoRqDelay = it },
            500,
            5000,
            50
        ); y += ROW_HEIGHT
        number(
            "Requeue After No Game (s)",
            x,
            y,
            width,
            { cfg.rqNoGame },
            { cfg.rqNoGame = it },
            15,
            60,
            1
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader("Chat Macros", x, y, width); y += SECTION_SPACING
        toggle(
            "Game Start Message",
            x,
            y,
            width,
            { cfg.sendStartMessage },
            { cfg.sendStartMessage = it }); y += ROW_HEIGHT
        number(
            "Start Message Delay (ms)",
            x,
            y,
            width,
            { cfg.startMessageDelay },
            { cfg.startMessageDelay = it },
            50,
            1000,
            50
        ); y += ROW_HEIGHT
        toggle("Enable AutoGG", x, y, width, { cfg.sendAutoGG }, { cfg.sendAutoGG = it }); y += ROW_HEIGHT
        number("AutoGG Delay (ms)", x, y, width, { cfg.ggDelay }, { cfg.ggDelay = it }, 50, 1000, 50); y += ROW_HEIGHT

        return y
    }

    private fun drawCombatTab(x: Int, yStart: Int): Int {
        var y = yStart
        val cfg = kira.config ?: return y
        val width = 500

        y = drawSectionHeader("Clicker", x, y, width); y += SECTION_SPACING
        number("Min CPS", x, y, width, { cfg.minCPS }, { cfg.minCPS = it }, 15, 25, 1); y += ROW_HEIGHT
        number(
            "Max CPS",
            x,
            y,
            width,
            { cfg.maxCPS },
            { cfg.maxCPS = it },
            19,
            25,
            1
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader("Aim", x, y, width); y += SECTION_SPACING
        number(
            "Horizontal Look Speed",
            x,
            y,
            width,
            { cfg.lookSpeedHorizontal },
            { cfg.lookSpeedHorizontal = it },
            1,
            50,
            1
        ); y += ROW_HEIGHT
        number(
            "Vertical Look Speed",
            x,
            y,
            width,
            { cfg.lookSpeedVertical },
            { cfg.lookSpeedVertical = it },
            1,
            50,
            1
        ); y += ROW_HEIGHT
        decimal(
            "Look Randomization",
            x,
            y,
            width,
            { cfg.lookRand },
            { cfg.lookRand = it },
            0f,
            2f,
            0.05f
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader("Reach & Targeting", x, y, width); y += SECTION_SPACING
        number(
            "Max Look Distance",
            x,
            y,
            width,
            { cfg.maxDistanceLook },
            { cfg.maxDistanceLook = it },
            10,
            200,
            5
        ); y += ROW_HEIGHT
        number(
            "Max Attack Distance",
            x,
            y,
            width,
            { cfg.maxDistanceAttack },
            { cfg.maxDistanceAttack = it },
            3,
            6,
            1
        ); y += ROW_HEIGHT
        toggle("Kira Hit", x, y, width, { cfg.kiraHit }, { cfg.kiraHit = it }); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader("Hit & Block", x, y, width); y += SECTION_SPACING
        toggle("Hit & Block", x, y, width, { cfg.hitBlock }, { cfg.hitBlock = it }); y += ROW_HEIGHT
        selector(
            "H&B Mode",
            x,
            y,
            width,
            { cfg.hitBlockMode },
            { cfg.hitBlockMode = it },
            listOf("Chance", "Cooldown Hits")
        ); y += ROW_HEIGHT
        number(
            "H&B Chance (%)",
            x,
            y,
            width,
            { cfg.hitBlockChance },
            { cfg.hitBlockChance = it },
            0,
            100,
            1
        ); y += ROW_HEIGHT
        number(
            "H&B Min Hits",
            x,
            y,
            width,
            { cfg.hitBlockMinHits },
            { cfg.hitBlockMinHits = it },
            1,
            10,
            1
        ); y += ROW_HEIGHT
        number(
            "H&B Max Hits",
            x,
            y,
            width,
            { cfg.hitBlockMaxHits },
            { cfg.hitBlockMaxHits = it },
            1,
            10,
            1
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawToggleSection(
            "Misc",
            x,
            y,
            width,
            listOf(
                ToggleSpec("Boxing: Use Fish", { cfg.boxingFish }, { cfg.boxingFish = it }),
                ToggleSpec("Anti Detection", { cfg.antiDetection }, { cfg.antiDetection = it })
            )
        )

        return y
    }

    private fun drawStatsTab(x: Int, yStart: Int): Int {
        var y = yStart
        val width = 500
        y = drawSectionHeader("Session Statistics", x, y, width); y += SECTION_SPACING

        kira.config?.let { config ->
            toggle("Show Stats Overlay", x, y, width, { config.showStatsOverlay }, { config.showStatsOverlay = it })
            y += ROW_HEIGHT + SECTION_SPACING
        }

        val controlEdgeX = x + width
        val wins = Session.wins
        val losses = Session.losses
        val total = wins + losses
        val wlr = if (losses == 0) wins.toFloat() else wins.toFloat() / losses
        val winrate = if (total == 0) 0f else (wins.toFloat() / total) * 100f
        val minutes = (Session.getActiveDurationMs() / 1000 / 60).coerceAtLeast(0L)

        fun drawStat(label: String, value: String, valueColor: Int) {
            val yPos = y - scroll
            drawString(fontRendererObj, label, x, yPos, highlightColor)
            drawString(fontRendererObj, value, controlEdgeX - fontRendererObj.getStringWidth(value), yPos, valueColor)
            y += ROW_HEIGHT
        }

        drawStat("Wins", wins.toString(), successColor)
        drawStat("Losses", losses.toString(), failureColor)
        drawStat("Games Played", total.toString(), highlightColor)
        y += 6
        drawStat("W/L Ratio", String.format("%.2f", wlr), primaryColor)
        drawStat("Win Rate", String.format("%.1f%%", winrate), primaryColor)
        y += 6
        drawStat("Session Time", "${minutes}m", highlightColor)

        return y
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (mouseButton == 0) {
            hotspots.firstOrNull { mouseX in it.x1..it.x2 && mouseY in it.y1..it.y2 }?.onClick?.invoke()
        }
        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(null)
            return
        }
        super.keyTyped(typedChar, keyCode)
    }

    private fun saveConfig() {
        kira.config?.writeData()
        ChatUtils.info("Configuration saved!")
    }

    override fun doesGuiPauseGame(): Boolean = false

    private inline fun scissor(x: Int, y: Int, w: Int, h: Int, draw: () -> Unit) {
        val sr = ScaledResolution(this.mc)
        val sf = sr.scaleFactor
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(x * sf, (sr.scaledHeight - (y + h)) * sf, w * sf, h * sf)
        draw()
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
    }

}
