package best.spaghetcodes.kira.gui

import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.core.RequeueMode
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.stats.StatsManager
import best.spaghetcodes.kira.utils.ChatUtils
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.resources.I18n
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

class CustomConfigGUI : GuiScreen() {

    private var currentTab = 0
    private val tabKeys = listOf("kira.gui.tab.general", "kira.gui.tab.combat", "kira.gui.tab.stats")
    private val botNameKeys = mapOf(
        "Classic" to "kira.gui.bot.classic",
        "ClassicV2" to "kira.gui.bot.classicv2",
        "OP" to "kira.gui.bot.op",
        "Combo" to "kira.gui.bot.combo",
        "Sumo" to "kira.gui.bot.sumo",
        "Boxing" to "kira.gui.bot.boxing",
        "Bow" to "kira.gui.bot.bow",
        "Blitz" to "kira.gui.bot.blitz"
    )
    private val hitBlockModeKeys = listOf(
        "kira.gui.option.hitBlockMode.chance",
        "kira.gui.option.hitBlockMode.cooldown",
        "kira.gui.option.hitBlockMode.prediction"
    )
    private var selectedStatsCategoryIndex = 0

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

    private fun tr(key: String, vararg args: Any): String = I18n.format(key, *args)

    private fun botLabels(names: List<String>): List<String> {
        return names.map { name -> botNameKeys[name]?.let { key -> tr(key) } ?: name }
    }

    private fun translateCategory(category: String): String {
        return when (category) {
            StatsManager.GLOBAL_CATEGORY -> tr("kira.gui.stats.category.global")
            StatsManager.SESSION_CATEGORY -> tr("kira.gui.stats.category.session")
            else -> botNameKeys[category]?.let { tr(it) } ?: category
        }
    }

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

        drawCenteredString(fontRendererObj, tr("kira.gui.title"), width / 2, 40, highlightColor)
        val tabNames = tabKeys.map { tr(it) }
        drawTabs(startX, 60, contentWidth, tabNames)

        scissor(0, startY - 10, width, endY - startY + 20) {
            val finalY = when (currentTab) {
                0 -> drawGeneralTab(startX, startY)
                1 -> drawCombatTab(startX, startY)
                else -> drawStatsTab(startX, startY)
            }
            maxScroll = max(0, finalY - endY)
        }

        val footerY = height - 40
        val status = if (kira.bot?.toggled() == true) tr("kira.gui.status.online") else tr("kira.gui.status.offline")
        drawString(fontRendererObj, status, 20, footerY + 4, -1)

        val saveLabel = tr("kira.gui.action.saveAndClose")
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

    private fun drawTabs(x: Int, y: Int, containerWidth: Int, tabNames: List<String>) {
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

    private inline fun mutateConfig(action: () -> Unit) {
        action()
        kira.config?.markDirty()
    }

    private fun selector(
        label: String,
        x: Int,
        y: Int,
        width: Int,
        get: () -> Int,
        set: (Int) -> Unit,
        options: List<String>,
        enabled: Boolean = true
    ) {
        val yPos = y - scroll
        val controlEdgeX = x + width
        val cur = min(max(0, get()), options.lastIndex)
        val value = options.getOrElse(cur) { tr("kira.gui.common.na") }
        val labelColor = if (enabled) highlightColor else grayColor
        drawString(fontRendererObj, label, x, yPos, labelColor)

        val valueW = fontRendererObj.getStringWidth(value)
        val arrowChar = ">"
        val arrowW = fontRendererObj.getStringWidth(arrowChar)
        val interactiveWidth = 140

        val rightArrowX = controlEdgeX - arrowW
        val leftArrowX = controlEdgeX - interactiveWidth

        val leftHover = enabled && isHovered(leftArrowX, yPos - 1, arrowW + 10, 10)
        val rightHover = enabled && isHovered(rightArrowX - 10, yPos - 1, arrowW + 10, 10)

        // Center the text between the fixed arrows
        val textContainerStart = leftArrowX + arrowW + 10
        val textContainerEnd = rightArrowX - 10
        val textX = textContainerStart + (textContainerEnd - textContainerStart - valueW) / 2

        // Draw shadows/outlines
        drawString(fontRendererObj, "<", leftArrowX + 1, yPos + 1, Color.BLACK.rgb)
        drawString(fontRendererObj, arrowChar, rightArrowX + 1, yPos + 1, Color.BLACK.rgb)
        val noneLabel = tr("kira.gui.common.none")
        val isDisabledOption = value.equals(noneLabel, ignoreCase = true)
        val valueShadowColor = if (enabled && !isDisabledOption) {
            Color(primaryColor).darker().darker().rgb
        } else {
            darkGrayColor
        }
        drawString(fontRendererObj, value, textX + 1, yPos + 1, valueShadowColor)

        // Draw main text and arrows
        val arrowColor = when {
            !enabled -> darkGrayColor
            else -> grayColor
        }
        val leftColor = when {
            !enabled -> arrowColor
            leftHover -> highlightColor
            else -> grayColor
        }
        val rightColor = when {
            !enabled -> arrowColor
            rightHover -> highlightColor
            else -> grayColor
        }
        val valueColor = if (enabled && !isDisabledOption) primaryColor else darkGrayColor
        drawString(fontRendererObj, "<", leftArrowX, yPos, leftColor)
        drawString(fontRendererObj, value, textX, yPos, valueColor)
        drawString(fontRendererObj, arrowChar, rightArrowX, yPos, rightColor)

        if (enabled) {
            addHotspot(leftArrowX, yPos - 1, arrowW + 10, 10) {
                mutateConfig { set(if (cur <= 0) options.lastIndex else cur - 1) }
            }
            addHotspot(rightArrowX - 10, yPos - 1, arrowW + 10, 10) {
                mutateConfig { set(if (cur >= options.lastIndex) 0 else cur + 1) }
            }
        }
    }

    private fun toggle(label: String, x: Int, y: Int, width: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val yPos = y - scroll
        val controlEdgeX = x + width
        val v = get()
        drawString(fontRendererObj, label, x, yPos, highlightColor)

        val radius = 4f
        val circleY = yPos + fontRendererObj.FONT_HEIGHT / 2f - 1
        val circleX = controlEdgeX.toFloat()

        glSettings {
            drawCircle(
                circleX,
                circleY,
                radius + 1f,
                if (v) Color(primaryColor).darker().darker().rgb else darkGrayColor
            )
            drawCircle(circleX, circleY, radius, if (v) primaryColor else accentColor)
        }

        addHotspot(controlEdgeX - 6, yPos - 2, 12, 12) {
            mutateConfig { set(!v) }
        }
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

        addHotspot(minusX, yPos - 1, symbolW, 10) { mutateConfig(onDecrement) }
        addHotspot(plusX, yPos - 1, symbolW, 10) { mutateConfig(onIncrement) }
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

        val botNamesRaw = listOf("Classic", "ClassicV2", "OP", "Combo", "Sumo", "Boxing", "Bow", "Blitz")
        val botNames = botLabels(botNamesRaw)
        val noneLabel = tr("kira.gui.common.none")
        val rotationBotNames = botNames + noneLabel

        y = drawSectionHeader(tr("kira.gui.section.botBehavior"), x, y, width); y += SECTION_SPACING
        val currentBotLabel = if (cfg.enableModeRotation) {
            tr("kira.gui.option.currentBot.disabled")
        } else {
            tr("kira.gui.option.currentBot.enabled")
        }
        selector(
            currentBotLabel,
            x,
            y,
            width,
            { cfg.currentBot },
            { v -> cfg.currentBot = v; kira.config?.getBot(v)?.let { kira.swapBot(it) } },
            botNames,
            enabled = !cfg.enableModeRotation
        ); y += ROW_HEIGHT
        toggle(tr("kira.gui.option.lobbyMovement"), x, y, width, { cfg.lobbyMovement }, { cfg.lobbyMovement = it }); y += ROW_HEIGHT
        val requeueModes = RequeueMode.values()
        selector(
            tr("kira.gui.option.requeueMode"),
            x,
            y,
            width,
            { requeueModes.indexOf(cfg.getRequeueMode()) },
            { idx -> cfg.setRequeueMode(requeueModes.getOrElse(idx) { RequeueMode.FAST }) },
            requeueModes.map { tr(it.displayNameKey) }
        ); y += ROW_HEIGHT
        toggle(
            tr("kira.gui.option.tuner"),
            x,
            y,
            width,
            { cfg.enableTuner },
            { cfg.enableTuner = it }
        ); y += ROW_HEIGHT
        toggle(
            tr("kira.gui.option.disableChatMessages"),
            x,
            y,
            width,
            { cfg.disableChatMessages },
            { cfg.disableChatMessages = it }); y += ROW_HEIGHT
        toggle(
            tr("kira.gui.option.cinematicCamera"),
            x,
            y,
            width,
            { cfg.cinematicCamera },
            { cfg.cinematicCamera = it }); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader(tr("kira.gui.section.modeRotation"), x, y, width); y += SECTION_SPACING
        toggle(
            tr("kira.gui.option.modeRotation"),
            x,
            y,
            width,
            { cfg.enableModeRotation },
            { cfg.enableModeRotation = it }
        ); y += ROW_HEIGHT
        number(
            tr("kira.gui.option.gamesPerMode"),
            x,
            y,
            width,
            { cfg.modeRotationGames },
            { cfg.modeRotationGames = it },
            1,
            1000,
            1
        ); y += ROW_HEIGHT
        selector(
            tr("kira.gui.option.rotationMode1"),
            x,
            y,
            width,
            { cfg.rotationMode1 },
            { cfg.rotationMode1 = it },
            botNames
        ); y += ROW_HEIGHT
        selector(
            tr("kira.gui.option.rotationMode2"),
            x,
            y,
            width,
            { cfg.rotationMode2 },
            { cfg.rotationMode2 = it },
            botNames
        ); y += ROW_HEIGHT
        selector(
            tr("kira.gui.option.rotationMode3"),
            x,
            y,
            width,
            { cfg.rotationMode3 },
            { cfg.rotationMode3 = it },
            rotationBotNames
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader(tr("kira.gui.section.autoDisconnect"), x, y, width); y += SECTION_SPACING
        number(
            tr("kira.gui.option.afterXGames"),
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
            tr("kira.gui.option.afterXMinutes"),
            x,
            y,
            width,
            { cfg.disconnectAfterMinutes },
            { cfg.disconnectAfterMinutes = it },
            0,
            500,
            5
        ); y += ROW_HEIGHT
        toggle(
            tr("kira.gui.option.antiBug"),
            x,
            y,
            width,
            { cfg.antiBug },
            { cfg.antiBug = it }
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader(tr("kira.gui.section.requeueTimings"), x, y, width); y += SECTION_SPACING
        number(
            tr("kira.gui.option.autoRequeueDelay"),
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
            tr("kira.gui.option.requeueAfterNoGame"),
            x,
            y,
            width,
            { cfg.rqNoGame },
            { cfg.rqNoGame = it },
            15,
            60,
            1
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader(tr("kira.gui.section.chatMacros"), x, y, width); y += SECTION_SPACING
        toggle(
            tr("kira.gui.option.gameStartMessage"),
            x,
            y,
            width,
            { cfg.sendStartMessage },
            { cfg.sendStartMessage = it }); y += ROW_HEIGHT
        number(
            tr("kira.gui.option.startMessageDelay"),
            x,
            y,
            width,
            { cfg.startMessageDelay },
            { cfg.startMessageDelay = it },
            50,
            1000,
            50
        ); y += ROW_HEIGHT
        toggle(tr("kira.gui.option.enableAutoGG"), x, y, width, { cfg.sendAutoGG }, { cfg.sendAutoGG = it }); y += ROW_HEIGHT
        number(tr("kira.gui.option.autoGGDelay"), x, y, width, { cfg.ggDelay }, { cfg.ggDelay = it }, 50, 1000, 50); y += ROW_HEIGHT

        return y
    }

    private fun drawCombatTab(x: Int, yStart: Int): Int {
        var y = yStart
        val cfg = kira.config ?: return y
        val width = 500

        y = drawSectionHeader(tr("kira.gui.section.clicker"), x, y, width); y += SECTION_SPACING
        number(tr("kira.gui.option.minCps"), x, y, width, { cfg.minCPS }, { cfg.minCPS = it }, 15, 25, 1); y += ROW_HEIGHT
        number(
            tr("kira.gui.option.maxCps"),
            x,
            y,
            width,
            { cfg.maxCPS },
            { cfg.maxCPS = it },
            19,
            25,
            1
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader(tr("kira.gui.section.aim"), x, y, width); y += SECTION_SPACING
        number(
            tr("kira.gui.option.horizontalLookSpeed"),
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
            tr("kira.gui.option.verticalLookSpeed"),
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
            tr("kira.gui.option.lookRandomization"),
            x,
            y,
            width,
            { cfg.lookRand },
            { cfg.lookRand = it },
            0f,
            2f,
            0.05f
        ); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader(tr("kira.gui.section.reachTarget"), x, y, width); y += SECTION_SPACING
        number(
            tr("kira.gui.option.maxLookDistance"),
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
            tr("kira.gui.option.maxAttackDistance"),
            x,
            y,
            width,
            { cfg.maxDistanceAttack },
            { cfg.maxDistanceAttack = it },
            3,
            6,
            1
        ); y += ROW_HEIGHT
        toggle(tr("kira.gui.option.kiraHit"), x, y, width, { cfg.kiraHit }, { cfg.kiraHit = it }); y += ROW_HEIGHT + SECTION_SPACING

        y = drawSectionHeader(tr("kira.gui.section.hitBlock"), x, y, width); y += SECTION_SPACING
        toggle(tr("kira.gui.option.hitBlock"), x, y, width, { cfg.hitBlock }, { cfg.hitBlock = it }); y += ROW_HEIGHT
        val hitBlockModes = hitBlockModeKeys.map { tr(it) }
        selector(
            tr("kira.gui.option.hitBlockMode"),
            x,
            y,
            width,
            { cfg.hitBlockMode },
            { cfg.hitBlockMode = it },
            hitBlockModes
        ); y += ROW_HEIGHT
        number(
            tr("kira.gui.option.hitBlockChance"),
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
            tr("kira.gui.option.hitBlockMinHits"),
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
            tr("kira.gui.option.hitBlockMaxHits"),
            x,
            y,
            width,
            { cfg.hitBlockMaxHits },
            { cfg.hitBlockMaxHits = it },
            1,
            10,
            1
        ); y += ROW_HEIGHT
        decimal(
            tr("kira.gui.option.hitBlockTradeDistance"),
            x,
            y,
            width,
            { cfg.hitBlockTradeDistance },
            { cfg.hitBlockTradeDistance = it },
            2.5f,
            5f,
            0.05f
        ); y += ROW_HEIGHT
        number(
            tr("kira.gui.option.hitBlockBlockDuration"),
            x,
            y,
            width,
            { cfg.hitBlockDurationTicks },
            { cfg.hitBlockDurationTicks = it },
            1,
            10,
            1
        ); y += ROW_HEIGHT
        y += SECTION_SPACING

        y = drawToggleSection(
            tr("kira.gui.section.misc"),
            x,
            y,
            width,
            listOf(
                ToggleSpec(tr("kira.gui.option.boxingFish"), { cfg.boxingFish }, { cfg.boxingFish = it }),
                ToggleSpec(tr("kira.gui.option.antiDetection"), { cfg.antiDetection }, { cfg.antiDetection = it }),
                ToggleSpec(tr("kira.gui.option.winSneak"), { cfg.winSneak }, { cfg.winSneak = it })
            )
        )

        return y
    }

    private fun drawStatsTab(x: Int, yStart: Int): Int {
        var y = yStart
        val width = 500
        y = drawSectionHeader(tr("kira.gui.section.statistics"), x, y, width); y += SECTION_SPACING

        val categories = StatsManager.DISPLAY_CATEGORIES
        val selectedCategory = categories.getOrElse(selectedStatsCategoryIndex) { StatsManager.GLOBAL_CATEGORY }
        val categoryLabels = categories.map { translateCategory(it) }

        selector(
            tr("kira.gui.option.category"),
            x,
            y,
            width,
            { selectedStatsCategoryIndex },
            { selectedStatsCategoryIndex = it.coerceIn(0, categories.lastIndex) },
            categoryLabels
        ); y += ROW_HEIGHT + 6

        kira.config?.let { config ->
            toggle(tr("kira.gui.option.showStatsOverlay"), x, y, width, { config.showStatsOverlay }, { config.showStatsOverlay = it })
            y += ROW_HEIGHT + SECTION_SPACING
        }

        val controlEdgeX = x + width
        val stats = StatsManager.getStatsForDisplay(selectedCategory)
        val wins = stats.wins
        val losses = stats.losses
        val total = wins + losses
        val wlr = if (losses == 0) wins.toFloat() else wins.toFloat() / losses
        val winrate = if (total == 0) 0f else (wins.toFloat() / total) * 100f
        val minutes = if (selectedCategory == StatsManager.SESSION_CATEGORY) {
            (Session.getActiveDurationMs() / 1000 / 60).coerceAtLeast(0L)
        } else {
            null
        }

        fun drawStat(label: String, value: String, valueColor: Int) {
            val yPos = y - scroll
            drawString(fontRendererObj, label, x, yPos, highlightColor)
            drawString(fontRendererObj, value, controlEdgeX - fontRendererObj.getStringWidth(value), yPos, valueColor)
            y += ROW_HEIGHT
        }

        drawStat(tr("kira.gui.stats.wins"), wins.toString(), successColor)
        drawStat(tr("kira.gui.stats.losses"), losses.toString(), failureColor)
        drawStat(tr("kira.gui.stats.gamesPlayed"), total.toString(), highlightColor)
        y += 6
        drawStat(tr("kira.gui.stats.wlr"), String.format("%.2f", wlr), primaryColor)
        drawStat(tr("kira.gui.stats.winRate"), String.format("%.1f%%", winrate), primaryColor)

        minutes?.let {
            y += 6
            drawStat(tr("kira.gui.stats.sessionTime"), tr("kira.gui.stats.sessionTimeValue", it), highlightColor)
        }

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
        kira.config?.markDirty()
        kira.config?.writeData()
        ChatUtils.info(tr("kira.gui.info.saved"))
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

    private fun setColor(color: Int, alphaMultiplier: Float = 1f) {
        val a = (color shr 24 and 255).toFloat() / 255.0f
        val r = (color shr 16 and 255).toFloat() / 255.0f
        val g = (color shr 8 and 255).toFloat() / 255.0f
        val b = (color and 255).toFloat() / 255.0f
        GlStateManager.color(r, g, b, a * fadeIn * alphaMultiplier)
    }

    private fun drawCircle(x: Float, y: Float, radius: Float, color: Int) {
        setColor(color)
        GL11.glBegin(GL11.GL_TRIANGLE_FAN)
        GL11.glVertex2f(x, y)
        for (i in 0..360 step 10) {
            GL11.glVertex2f(
                x + sin(Math.toRadians(i.toDouble())).toFloat() * radius,
                y + cos(Math.toRadians(i.toDouble())).toFloat() * radius
            )
        }
        GL11.glEnd()
    }

    private inline fun glSettings(block: () -> Unit) {
        GlStateManager.pushMatrix()
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        GlStateManager.shadeModel(GL11.GL_SMOOTH)
        try {
            block()
        } finally {
            GlStateManager.shadeModel(GL11.GL_FLAT)
            GlStateManager.enableTexture2D()
            GlStateManager.disableBlend()
            GlStateManager.popMatrix()
        }
    }

}
