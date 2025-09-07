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

    // ---- état combat / clic ----
    private var tapping = false
    private var lockLeftAC = false

    // ---- cycles déterministes (aucun check d'effet) ----
    // Strength : 2 doses, 2e à +294 s après le début de la 1re
    private var strengthDosesUsed = 0
    override var lastPotion = 0L
    private var strengthCycleStarted = false
    private var nextStrengthAt = 0L
    private val strengthPeriodMs = 294_000L

    // Gapples : toutes les 26 s, indéfiniment
    override var lastGap = 0L
    private var gapCycleStarted = false
    private var nextGapAt = 0L
    private val gapPeriodMs = 26_000L

    // Ressources diverses
    private var pearls = 5
    private var lastPearl = 0L

    // Ouverture
    private var openingPhase = true
    private var openingScheduled = false

    // Verrou conso (empêche interruptions pendant ~2s)
    private var consumingUntil = 0L
    private fun isConsuming(): Boolean = System.currentTimeMillis() < consumingUntil

    // Sauts / strafes
    private var lastFarJumpAt = 0L
    private var strafeDir = 1
    private var closeStrafeToggleAt = 0L

    enum class ArmorEnum { BOOTS, LEGGINGS, CHESTPLATE, HELMET }
    private var armor = hashMapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1)

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
        Mouse.stopLeftAC()
        TimeUtils.setTimeout({
            if (returnSword) {
                Inventory.setInvItem("sword")
                TimeUtils.setTimeout({
                    lockLeftAC = false
                }, RandomUtils.randomIntInRange(120, 200))
            } else {
                lockLeftAC = false
            }
        }, holdMs + 140)
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
        val ok = equipAndHoldRightClick(
            { equipAny("potion", "strength", "str") },
            { isHoldingPotion() },
            preMs, holdMs, returnSword
        )
        if (ok) {
            // Horodatage au démarrage (après pré-délai ; ~équiv au début du hold)
            TimeUtils.setTimeout({
                lastPotion = System.currentTimeMillis()
                strengthDosesUsed += 1
                if (!strengthCycleStarted) {
                    strengthCycleStarted = true
                    nextStrengthAt = lastPotion + strengthPeriodMs
                } else {
                    nextStrengthAt = 0L
                }
            }, preMs + 5)
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
        val ok = equipAndHoldRightClick(
            { equipAny("gap", "gapple", "apple", "golden_apple") },
            { isHoldingGap() },
            preMs, holdMs, /*returnSword=*/true
        )
        if (ok) {
            TimeUtils.setTimeout({
                val hasAbsorption = player.isPotionActive(MCPotion.absorption)
                var gapsAfter = 0
                for (stack in player.inventory.mainInventory) {
                    if (stack != null && stack.item == Items.golden_apple) {
                        gapsAfter += stack.stackSize
                    }
                }
                if (hasAbsorption || gapsAfter < gapsBefore) {
                    lastGap = System.currentTimeMillis()
                    if (!gapCycleStarted) gapCycleStarted = true
                    nextGapAt = lastGap + gapPeriodMs
                } else {
                    eatGap(0, 2600, EntityUtils.getDistanceNoY(player, target), player, target)
                }
            }, preMs + holdMs + 150)
        } else {
            // Fallback minimal : on essaie via helper (sélection interne) SANS tenir un 2e clic par-dessus
            useGap(distance, distance < 2f, EntityUtils.entityFacingAway(player, target))
            // On recale quand même le cycle, et le watchdog rattrapera si la conso n'est pas partie
            lastGap = System.currentTimeMillis()
            if (!gapCycleStarted) gapCycleStarted = true
            nextGapAt = lastGap + gapPeriodMs
        }
        return ok
    }

    private fun startOpening(player: net.minecraft.entity.player.EntityPlayer, target: net.minecraft.entity.Entity) {
        val prePot = RandomUtils.randomIntInRange(110, 160)
        val holdPot = 2100
        val preGap = RandomUtils.randomIntInRange(110, 160)
        val holdGap = 2100

        // 1) Potion d’ouverture (ne pas forcer retour épée ici, on enchaîne la gap juste après)
        drinkStrength(prePot, holdPot, /*returnSword=*/false)

        // 2) Gap juste après la fin de la boisson (préPot + holdPot + petite marge)
        val delayToGap = prePot + holdPot + RandomUtils.randomIntInRange(40, 80)
        TimeUtils.setTimeout({
            // si une autre action a prolongé la conso, on attend la libération
            fun tryChain() {
                if (isConsuming()) {
                    TimeUtils.setTimeout({ tryChain() }, 40)
                } else {
                    eatGap(preGap, holdGap, EntityUtils.getDistanceNoY(player, target), player, target)
                    openingPhase = false
                }
            }
            tryChain()
        }, delayToGap)
    }

    // ------------------- Lifecycle -------------------
    override fun onGameStart() {
        Movement.startSprinting()
        Movement.startForward()
        Mouse.startTracking()
        Mouse.stopLeftAC()

        openingPhase = true
        openingScheduled = false

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
        closeStrafeToggleAt = 0L

        lockLeftAC = false
        tapping = false
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            tapping = false
            lockLeftAC = false

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

        // Toujours sprinter
        if (!player.isSprinting) Movement.startSprinting()

        // Tracking / auto-clic : jamais pendant une conso ou l’ouverture
        if (distance < 150) Mouse.startTracking() else Mouse.stopTracking()
        if (!isConsuming() && !openingPhase && distance < 10) {
            if (player.heldItem != null && player.heldItem.unlocalizedName.lowercase().contains("sword")) {
                if (!lockLeftAC && kira.config?.enableHits == true) Mouse.startLeftAC()
            }
        } else {
            Mouse.stopLeftAC()
        }

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
                val hold = 2100
                drinkStrength(pre, hold, /*returnSword=*/true)
            }
        }

        // Armure simple (hors conso)
        if (!isConsuming()) {
            for (i in 0..3) {
                if (player.inventory.armorItemInSlot(i) == null) {
                    Mouse.stopLeftAC()
                    lockLeftAC = true
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
                                        }, RandomUtils.randomIntInRange(200, 300))
                                    }, r + RandomUtils.randomIntInRange(100, 150))
                                }, RandomUtils.randomIntInRange(200, 400))
                            } else {
                                lockLeftAC = false
                            }
                        }, RandomUtils.randomIntInRange(250, 500))
                    }
                }
            }
        }

        // Gap toutes les 26s (infini) + watchdog anti-stall
        if (!openingPhase && gapCycleStarted && !isConsuming()) {
            if (now >= nextGapAt || now - lastGap > gapPeriodMs + 2000) {
                val pre = RandomUtils.randomIntInRange(110, 160)
                val hold = 2100
                eatGap(pre, hold, distance, player, target)
            }
        }

        // Quick pearl (safe hors conso)
        if (!isConsuming() &&
            distance > 18f &&
            EntityUtils.entityFacingAway(target, player) &&
            !Mouse.isRunningAway() &&
            now - lastPearl > 5000 &&
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
            if (timeUntilGap <= 10_000) {
                val pre = RandomUtils.randomIntInRange(110, 160)
                val hold = 2100
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
                    if (nowMs >= closeStrafeToggleAt) {
                        strafeDir = -strafeDir
                        closeStrafeToggleAt = nowMs + RandomUtils.randomIntInRange(40, 90)
                        val tapDuration = RandomUtils.randomIntInRange(40, 80)
                        if (strafeDir < 0) {
                            Combat.aTap(tapDuration)
                        } else {
                            Combat.dTap(tapDuration)
                        }
                    }
                    val weightClose = 6
                    if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                    randomStrafe = false
                } else {
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
