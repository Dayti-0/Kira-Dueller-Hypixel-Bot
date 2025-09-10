package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.Gap
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.features.Potion
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.WorldUtils
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.potion.Potion as MCPotion
import net.minecraft.util.Vec3

class Combo : BotBase("/play duels_combo_duel"), MovePriority, Gap, Potion {

    // ---- State ----
    private var gameStartAt = 0L
    private var openingPhase = true
    private var openingScheduled = false
    private var strengthDosesUsed = 0
    override var lastPotion = 0L
    private var strengthCycleStarted = false
    private var nextStrengthAt = 0L
    override var lastGap = 0L
    private var gapCycleStarted = false
    private var nextGapAt = 0L
    private var pearls = 5
    private var lastPearl = 0L
    private var consumingUntil = 0L
    private var lastFarJumpAt = 0L
    private var strafeDir = 1
    private var closeStrafeNextAt = 0L
    private var lastCloseStrafeSwitch = 0L
    private var lockLeftAC = false
    private var lockLeftACSince = 0L
    private var tapping = false
    private var leftACActive = false
    private var lastOpponentDistance = 0f
    private val strengthPeriodMs = 294_000L
    private val gapPeriodMs = 26_000L
    private fun isConsuming(): Boolean = System.currentTimeMillis() < consumingUntil

    private fun computeCloseStrafeDelay(distance: Float): Long {
        return when {
            distance < 1.4f -> 340L
            distance < 2.0f -> 260L
            else -> 200L
        }
    }

    enum class ArmorEnum { BOOTS, LEGGINGS, CHESTPLATE, HELMET }
    private var armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)

    override fun getName(): String = "Combo"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.combo_duel_wins",
                "losses" to "player.stats.Duels.combo_duel_losses",
                "ws" to "player.stats.Duels.current_combo_winstreak"
            )
        )
    }

    // ------------------- Helpers item -------------------
    private fun equipAny(vararg keys: String): Boolean {
        for (k in keys) if (Inventory.setInvItem(k)) return true
        return false
    }
    private fun isHoldingGap(): Boolean {
        val it = mc.thePlayer?.heldItem ?: return false
        return it.item == Items.golden_apple
    }
    private fun isHoldingPotion(): Boolean {
        val it = mc.thePlayer?.heldItem ?: return false
        return it.item == Items.potionitem
    }

    // ------------------- Fenêtre conso & clic -------------------
    /** Verrouille la durée de la conso, coupe l’AC gauche, puis (optionnel) revient épée. */
    private fun beginConsumeWindow(holdMs: Int, returnSword: Boolean) {
        val now = System.currentTimeMillis()
        consumingUntil = now + holdMs + 150
        lockLeftAC = true
        lockLeftACSince = now
        Mouse.stopLeftAC()
        TimeUtils.setTimeout({
            if (returnSword) {
                Inventory.setInvItem("sword")
                TimeUtils.setTimeout({
                    lockLeftAC = false
                    lockLeftACSince = 0L
                }, RandomUtils.randomIntInRange(120, 200))
            } else {
                lockLeftAC = false
                lockLeftACSince = 0L
            }
        }, holdMs + 300)
    }

    /** Équipe (avec pré-délai), vérifie l’item en main, puis tient le clic droit. */
    private fun equipAndHoldRightClick(
        select: () -> Boolean,
        verify: () -> Boolean,
        preMs: Int,
        holdMs: Int,
        returnSword: Boolean
    ): Boolean {
        val selected = select()
        if (!selected) return false
        TimeUtils.setTimeout({
            // Si pas encore le bon item (latence de switch), retente une fois
            if (!verify()) {
                select()
            }
            beginConsumeWindow(holdMs, returnSword)
            Mouse.rClick(holdMs)
        }, preMs)
        return true
    }

    // ------------------- Ouverture : potion -> gap (séquencé) -------------------
    private fun drinkStrength(preMs: Int, holdMs: Int, returnSword: Boolean): Boolean {
        val player = mc.thePlayer ?: return false
        var potsBefore = 0
        for (stack in player.inventory.mainInventory) {
            if (stack != null && stack.item == Items.potionitem) {
                potsBefore += stack.stackSize
            }
        }
        val ok = equipAndHoldRightClick(
            { equipAny("potion", "strength", "str") },
            { isHoldingPotion() },
            preMs, holdMs, returnSword
        )
        if (ok) {
            TimeUtils.setTimeout({
                var potsAfter = 0
                for (stack in player.inventory.mainInventory) {
                    if (stack != null && stack.item == Items.potionitem) {
                        potsAfter += stack.stackSize
                    }
                }
                val hasStrength = player.isPotionActive(MCPotion.damageBoost)
                if (hasStrength || potsAfter < potsBefore) {
                    lastPotion = System.currentTimeMillis()
                    strengthDosesUsed += 1
                    if (!strengthCycleStarted) {
                        strengthCycleStarted = true
                        nextStrengthAt = lastPotion + strengthPeriodMs
                    } else {
                        nextStrengthAt = 0L
                    }
                } else {
                    drinkStrength(0, 2600, returnSword)
                }
            }, preMs + holdMs + 150)
        }
        return ok
    }

    private fun eatGap(preMs: Int, holdMs: Int, distance: Float, player: net.minecraft.entity.player.EntityPlayer, target: net.minecraft.entity.Entity): Boolean {
        var gapsBefore = 0
        for (stack in player.inventory.mainInventory) {
            if (stack != null && stack.item == Items.golden_apple) {
                gapsBefore += stack.stackSize
            }
        }
        val hadAbsorption = player.isPotionActive(MCPotion.absorption)
        val ok = equipAndHoldRightClick(
            { equipAny("gap", "gapple", "apple", "golden_apple") },
            { isHoldingGap() },
            preMs, holdMs, /*returnSword=*/true
        )
        if (ok) {
            fun confirmGap() {
                val hasAbsorption = player.isPotionActive(MCPotion.absorption)
                var gapsAfter = 0
                for (stack in player.inventory.mainInventory) {
                    if (stack != null && stack.item == Items.golden_apple) {
                        gapsAfter += stack.stackSize
                    }
                }
                val countDecreased = gapsAfter < gapsBefore
                val eaten = countDecreased && (hadAbsorption || hasAbsorption)
                when {
                    eaten -> {
                        lastGap = System.currentTimeMillis()
                        if (!gapCycleStarted) gapCycleStarted = true
                        nextGapAt = lastGap + gapPeriodMs
                    }
                    hasAbsorption || countDecreased -> {
                        TimeUtils.setTimeout({ confirmGap() }, RandomUtils.randomIntInRange(150, 200))
                    }
                    else -> {
                        eatGap(0, 2900, EntityUtils.getDistanceNoY(player, target), player, target)
                    }
                }
            }
            TimeUtils.setTimeout({ confirmGap() }, preMs + holdMs + 150)
        } else {
            // Fallback minimal : on essaie via helper (sélection interne) SANS tenir un 2e clic par-dessus
            useGap(distance, distance < 2f, EntityUtils.entityFacingAway(player, target))
        }
        return ok
    }

    private fun startOpening(player: net.minecraft.entity.player.EntityPlayer, target: net.minecraft.entity.Entity) {
        val prePot = RandomUtils.randomIntInRange(0, 10)
        val holdPot = 2100

        // 1) Potion d’ouverture
        drinkStrength(prePot, holdPot, /*returnSword=*/false)

        // 2) Gap seulement une fois la potion confirmée
        fun chainGap() {
            val hasStrength = player.isPotionActive(MCPotion.damageBoost)
            if (isConsuming() || !hasStrength) {
                TimeUtils.setTimeout({ chainGap() }, 40)
            } else {
                val preGap = RandomUtils.randomIntInRange(0, 10)
                val holdGap = 2400
                eatGap(preGap, holdGap, EntityUtils.getDistanceNoY(player, target), player, target)

                // 3) Fin de l’ouverture lorsque la gap est consommée
                TimeUtils.setTimeout({
                    fun confirmGap() {
                        val hasAbsorption = player.isPotionActive(MCPotion.absorption)
                        if (isConsuming() || !hasAbsorption) {
                            TimeUtils.setTimeout({ confirmGap() }, 40)
                        } else {
                            openingPhase = false
                        }
                    }
                    confirmGap()
                }, preGap + holdGap + 150)
            }
        }
        chainGap()
    }

    // ------------------- Lifecycle -------------------
    override fun onGameStart() {
        Mouse.rClickUp()
        Movement.startSprinting()
        Movement.startForward()
        Mouse.startTracking()
        Mouse.stopLeftAC()
        Inventory.setInvItem("sword")

        gameStartAt = System.currentTimeMillis()

        openingPhase = true
        openingScheduled = true

        TimeUtils.setTimeout({
            val player = mc.thePlayer
            val target = opponent()
            if (player == null || target == null) {
                openingScheduled = false
                return@setTimeout
            }
            startOpening(player, target)
            openingScheduled = true
        }, 0)

        strengthDosesUsed = 0
        lastPotion = 0L
        strengthCycleStarted = false
        nextStrengthAt = 0L

        lastGap = 0L
        gapCycleStarted = false
        nextGapAt = 0L

        pearls = 5
        lastPearl = 0L

        consumingUntil = 0L
        lastFarJumpAt = 0L

        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        closeStrafeNextAt = 0L

        lockLeftAC = false
        lockLeftACSince = 0L
        tapping = false
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Mouse.rClickUp()
            Inventory.setInvItem("sword")
            Combat.stopRandomStrafe()
            tapping = false
            lockLeftAC = false
            lockLeftACSince = 0L

            strengthDosesUsed = 0
            lastPotion = 0L
            strengthCycleStarted = false
            nextStrengthAt = 0L

            lastGap = 0L
            gapCycleStarted = false
            nextGapAt = 0L

            pearls = 5
            lastPearl = 0L

            armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)

            openingPhase = false
            openingScheduled = false
            consumingUntil = 0L
            Mouse.stopTracking()
        }, RandomUtils.randomIntInRange(100, 300))
    }

    // ------------------- Tick principal -------------------
    override fun onTick() {
        val player = mc.thePlayer ?: return
        val target = opponent() ?: return

        val distance = EntityUtils.getDistanceNoY(player, target)
        val now = System.currentTimeMillis()

        // Fail-safe : unlock left AC if stuck too long
        if (lockLeftAC && now - lockLeftACSince > 3000) {
            lockLeftAC = false
            lockLeftACSince = 0L
            if (!isConsuming() && !openingPhase && distance < 10) {
                val held = player.heldItem
                if (held != null && held.unlocalizedName.lowercase().contains("sword") && kira.config?.enableHits == true) {
                    Mouse.startLeftAC()
                }
            }
        }

        // Toujours sprinter
        if (!player.isSprinting) Movement.startSprinting()

        // Tracking / auto-clic : jamais pendant une conso ou l’ouverture
        if (distance < 150) Mouse.startTracking() else Mouse.stopTracking()
        val shouldAttack = !isConsuming() && !openingPhase && distance < 10 &&
            player.heldItem != null &&
            player.heldItem.unlocalizedName.lowercase().contains("sword") &&
            !lockLeftAC &&
            kira.config?.enableHits == true
        if (shouldAttack) {
            Mouse.startLeftAC()
            leftACActive = true
        } else {
            Mouse.stopLeftAC()
            leftACActive = false
        }

        lastOpponentDistance = distance

        // Sauts > 8 blocs
        if (distance > 8f && player.onGround && now - lastFarJumpAt >= 540L) {
            Movement.singleJump(RandomUtils.randomIntInRange(130, 180))
            lastFarJumpAt = now
        }
        if (distance < 8f) Movement.stopJumping()

        // Micro-hop anti-flat
        if (combo >= 3 && distance >= 3.2f && player.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
        }

        // Avance en continu (sauf pendant un wTap)
        if (!tapping) {
            Movement.startForward()
        }

        // Pas de runaway si mur
        if (WorldUtils.blockInFront(player, 3f, 1.5f) != Blocks.air) {
            Mouse.setRunningAway(false)
        }

        // Ouverture
        if (openingPhase && !openingScheduled) {
            openingScheduled = true
            startOpening(player, target)
        }

        // Potion #2 à +296s (aucun check d'effet)
        if (!openingPhase && strengthCycleStarted && strengthDosesUsed == 1 && !isConsuming()) {
            if (now >= nextStrengthAt) {
                val pre = RandomUtils.randomIntInRange(110, 160)
                val hold = 2400
                drinkStrength(pre, hold, /*returnSword=*/true)
            }
        }

        // Armure simple (hors conso)
        if (!isConsuming()) {
            for (i in 0..3) {
                if (player.inventory.armorItemInSlot(i) == null) {
                    Mouse.stopLeftAC()
                    lockLeftAC = true
                    lockLeftACSince = now
                    if ((armor[i] ?: 0) > 0) {
                        TimeUtils.setTimeout({
                            val a = Inventory.setInvItem(ArmorEnum.values()[i].name.lowercase())
                            if (a) {
                                armor[i] = (armor[i] ?: 1) - 1
                                TimeUtils.setTimeout({
                                    val r = RandomUtils.randomIntInRange(100, 150)
                                    Mouse.rClick(r)
                                    TimeUtils.setTimeout({
                                        Inventory.setInvItem("sword")
                                        TimeUtils.setTimeout({
                                            lockLeftAC = false
                                            lockLeftACSince = 0L
                                        }, RandomUtils.randomIntInRange(200, 300))
                                    }, r + RandomUtils.randomIntInRange(100, 150))
                                }, RandomUtils.randomIntInRange(200, 400))
                            } else {
                                TimeUtils.setTimeout({
                                    lockLeftAC = false
                                    lockLeftACSince = 0L
                                }, RandomUtils.randomIntInRange(100, 150))
                            }
                        }, RandomUtils.randomIntInRange(250, 500))
                    } else {
                        TimeUtils.setTimeout({
                            lockLeftAC = false
                            lockLeftACSince = 0L
                        }, RandomUtils.randomIntInRange(200, 300))
                    }
                }
            }
        }

        // Gap toutes les 26s (infini) + watchdog anti-stall
        if (!openingPhase && gapCycleStarted && !isConsuming()) {
            if (now >= nextGapAt || now - lastGap > gapPeriodMs + 2000) {
                val pre = RandomUtils.randomIntInRange(110, 160)
                val hold = 2400
                eatGap(pre, hold, distance, player, target)
            }
        }

        // Defensive pearl: break juggle when launched upward (11s cooldown)
        if (!isConsuming() &&
            player.motionY > 0.15 &&
            !player.onGround &&
            now - gameStartAt > 45_000L &&
            pearls > 0 &&
            now - lastPearl > 11000 // 11s cooldown
        ) {
            lastPearl = now
            Mouse.stopLeftAC()
            lockLeftAC = true
            lockLeftACSince = now
            TimeUtils.setTimeout({
                if (Inventory.setInvItem("pearl")) {
                    pearls--
                    Mouse.setUsingProjectile(true)
                    TimeUtils.setTimeout({
                        Mouse.rClick(RandomUtils.randomIntInRange(100, 150))
                        TimeUtils.setTimeout({
                            Mouse.setUsingProjectile(false)
                            Inventory.setInvItem("sword")

                            TimeUtils.setTimeout({
                                lockLeftAC = false
                                lockLeftACSince = 0L
                            }, RandomUtils.randomIntInRange(200, 300))

                            TimeUtils.setTimeout({ lockLeftAC = false }, RandomUtils.randomIntInRange(200, 300))

                        }, RandomUtils.randomIntInRange(250, 300))
                    }, RandomUtils.randomIntInRange(80, 120))
                } else {
                    TimeUtils.setTimeout({
                        lockLeftAC = false
                        lockLeftACSince = 0L
                    }, RandomUtils.randomIntInRange(200, 300))
                }
            }, RandomUtils.randomIntInRange(50, 100))
        }

        // Quick pearl (safe hors conso, 11s cooldown)
        if (!isConsuming() &&
            !openingPhase &&
            distance > 24f &&
            now - gameStartAt > 45_000L &&
            EntityUtils.entityFacingAway(target, player) &&
            !Mouse.isRunningAway() &&
            now - lastPearl > 11000 && // 11s cooldown
            pearls > 0
        ) {
            fun pearlRoutine() {
                lastPearl = System.currentTimeMillis()
                Mouse.stopLeftAC()
                lockLeftAC = true
                TimeUtils.setTimeout({
                    if (Inventory.setInvItem("pearl")) {
                        pearls--
                        Mouse.setUsingProjectile(true)
                        TimeUtils.setTimeout({
                            Mouse.rClick(RandomUtils.randomIntInRange(100, 150))
                            TimeUtils.setTimeout({
                                Mouse.setUsingProjectile(false)
                                Inventory.setInvItem("sword")
                                TimeUtils.setTimeout({
                                    lockLeftAC = false
                                }, RandomUtils.randomIntInRange(200, 300))
                            }, RandomUtils.randomIntInRange(250, 300))
                        }, RandomUtils.randomIntInRange(300, 600))
                    } else {
                        lockLeftAC = false
                    }
                }, RandomUtils.randomIntInRange(250, 500))
            }
            val timeUntilGap = nextGapAt - now
            if (gapCycleStarted && timeUntilGap <= 12_000L) {
                val pre = RandomUtils.randomIntInRange(110, 160)
                val hold = 2400
                eatGap(pre, hold, distance, player, target)
                val delay = pre + hold + RandomUtils.randomIntInRange(40, 80)
                TimeUtils.setTimeout({ pearlRoutine() }, delay)
            } else {
                pearlRoutine()
            }
        }

        // Strafes “Classic-like”
        val movePriority = arrayListOf(0, 0)
        val randomStrafe: Boolean

        if (distance < 8f) {
            if (target.isInvisibleToPlayer(player)) {
                if (WorldUtils.leftOrRightToPoint(player, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
                randomStrafe = false
            } else {
                if (distance < 2.6f) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs >= closeStrafeNextAt && nowMs - lastCloseStrafeSwitch >= 150) {
                        strafeDir = -strafeDir
                        lastCloseStrafeSwitch = nowMs
                        closeStrafeNextAt = nowMs + computeCloseStrafeDelay(distance)
                    } else if (closeStrafeNextAt == 0L) {
                        closeStrafeNextAt = nowMs + computeCloseStrafeDelay(distance)
                    }
                    val weightClose = 6
                    if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                    randomStrafe = false
                } else {
                    closeStrafeNextAt = 0L
                    if (distance < 4f && combo > 2) {
                        val rotations = EntityUtils.getRotations(target, player, false)
                        if (rotations != null) {
                            if (rotations[0] < 0) movePriority[1] += 5 else movePriority[0] += 5
                        }
                        randomStrafe = false
                    } else {
                        randomStrafe = true
                    }
                }
            }
        } else {
            randomStrafe = true
        }

        handle(false, randomStrafe, movePriority)
    }

    override fun onAttack() {
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 120)
    }
}

