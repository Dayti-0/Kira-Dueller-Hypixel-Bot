package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.bot.tuning.BlitzTuner
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3

class Blitz : BotBase("/play duels_blitz_duel"), MovePriority {

    override fun getName(): String = "Blitz"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.blitz_duel_wins",
                "losses" to "player.stats.Duels.blitz_duel_losses",
                "ws" to "player.stats.Duels.current_blitz_winstreak",
            )
        )
    }

    private var tapping = false
    private var lastKitSwitch = 0L
    private var kitSwitchCooldown = 3000L
    private var jumpAggroDist = 3.0f
    private var stopForwardDist = 1.2f
    private var stopForwardComboDist = 2.5f
    private var potionHpThreshold = 15f
    private var potionUseChance = 30

    private fun applyParams(params: BlitzTuner.BlitzParams) {
        jumpAggroDist = params.jumpAggroDist
        stopForwardDist = params.stopForwardDist
        stopForwardComboDist = params.stopForwardComboDist
        kitSwitchCooldown = params.kitSwitchCooldownMs
        potionHpThreshold = params.potionHpThreshold
        potionUseChance = params.potionUseChance
    }

    override fun onGameStart() {
        val params = try {
            BlitzTuner.pickParams()
        } catch (_: Throwable) {
            BlitzTuner.defaults()
        }
        applyParams(params)
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Movement.startJumping()
        if (kira.config?.kiraHit == true) {
            Mouse.startLeftAC()
        } else {
            Mouse.stopLeftAC()
        }

        lastKitSwitch = 0L

        // Équiper l'épée au début
        Inventory.setInvItem("sword")
    }

    override fun onGameEnd() {
        val win = when {
            Session.wins > Session.losses -> true
            Session.losses > Session.wins -> false
            else -> false
        }
        BlitzTuner.report(win, 0)
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
            tapping = false
        }, RandomUtils.randomIntInRange(200, 400))
    }

    override fun onAttack() {
        ChatUtils.info("W-Tap 100")
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 100)
        if (combo >= 3) Movement.clearLeftRight()
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        if (kira.config?.kiraHit == true) {
            Mouse.startLeftAC()
        } else {
            Mouse.stopLeftAC()
        }

        val distance = EntityUtils.getDistanceNoY(p, opp)
        val now = System.currentTimeMillis()

        // Sauts (Blitz un peu plus agressif)
        if (distance > jumpAggroDist) {
            Movement.startJumping()
        } else {
            Movement.stopJumping()
        }

        // Éviter les obstacles
        if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(150, 250))
        }

        // Avancer / reculer
        if (distance < stopForwardDist || (distance < stopForwardComboDist && combo >= 1)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // Gestion d’objets/kits (Blitz)
        if ((now - lastKitSwitch) > kitSwitchCooldown) {
            when {
                distance > 8f -> {
                    // Essayer arc/proj à longue distance
                    if (Inventory.setInvItem("bow")) {
                        lastKitSwitch = now
                    }
                }
                distance < 3f -> {
                    // S'assurer d'avoir l'épée au cac
                    if (p.heldItem?.unlocalizedName?.lowercase()?.contains("sword") != true) {
                        Inventory.setInvItem("sword")
                        lastKitSwitch = now
                    }
                }
                else -> {
                    // Distance moyenne : objets spéciaux/potions si présents
                    if (Inventory.hasItem("potion")) {
                        if (p.health < potionHpThreshold && RandomUtils.randomIntInRange(0, 100) < potionUseChance) {
                            Inventory.setInvItem("potion")
                            TimeUtils.setTimeout({
                                Mouse.rClick(RandomUtils.randomIntInRange(80, 120))
                                TimeUtils.setTimeout({
                                    Inventory.setInvItem("sword")
                                }, RandomUtils.randomIntInRange(200, 300))
                            }, RandomUtils.randomIntInRange(100, 200))
                            lastKitSwitch = now
                        }
                    }
                }
            }
        }

        // Strafe (Blitz plus agressif)
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        if (EntityUtils.entityFacingAway(p, opp)) {
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) {
                movePriority[0] += 4
            } else {
                movePriority[1] += 4
            }
        } else {
            when {
                distance < 3f -> {
                    val rotations = EntityUtils.getRotations(opp, p, false)
                    if (rotations != null) {
                        if (rotations[0] < 0) movePriority[1] += 6 else movePriority[0] += 6
                    }
                }
                distance < 8f -> {
                    val rotations = EntityUtils.getRotations(opp, p, false)
                    if (rotations != null) {
                        if (rotations[0] < 0) movePriority[1] += 4 else movePriority[0] += 4
                    }
                }
                else -> {
                    randomStrafe = true
                }
            }
        }

        handle(clear, randomStrafe, movePriority)
    }
}
