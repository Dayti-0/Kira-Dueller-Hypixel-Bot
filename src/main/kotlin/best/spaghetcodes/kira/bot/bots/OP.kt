package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.bot.features.*
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3

class OP : BotBase("/play duels_op_duel"), Bow, Rod, MovePriority, Potion, Gap {

    override fun getName(): String = "OP"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.op_duel_wins",
                "losses" to "player.stats.Duels.op_duel_losses",
                "ws" to "player.stats.Duels.current_op_winstreak",
            )
        )
    }

    var shotsFired = 0
    var maxArrows = 20

    var speedDamage = 16386
    var regenDamage = 16385

    var speedPotsLeft = 2
    var regenPotsLeft = 2
    var gapsLeft = 6

    var lastSpeedUse = 0L
    var lastRegenUse = 0L
    override var lastPotion = 0L
    override var lastGap = 0L

    private var gameStartAt = 0L
    private var retreating = false

    var tapping = false

    private fun retreatAndSplash(damage: Int, onComplete: () -> Unit) {
        retreating = true
        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(false)
        Mouse.setRunningAway(true)
        Movement.startForward()
        Movement.startSprinting()
        Movement.startJumping()

        var check: java.util.Timer? = null
        check = TimeUtils.setInterval({
            val opp = opponent()
            val player = mc.thePlayer
            if (opp != null && player != null) {
                val dist = EntityUtils.getDistanceNoY(player, opp)
                if (dist >= 6f) {
                    check?.cancel()
                    useSplashPotion(damage, false, EntityUtils.entityFacingAway(player, opp))
                    TimeUtils.setTimeout({ retreating = false }, RandomUtils.randomIntInRange(900, 1100))
                    onComplete()
                }
            } else {
                check?.cancel()
                retreating = false
            }
        }, 0, 50)
    }

    override fun onGameStart() {
        gameStartAt = System.currentTimeMillis()
        Mouse.startTracking()                 // tracking ON
        Movement.startSprinting()
        Movement.startForward()
        TimeUtils.setTimeout(Movement::startJumping, RandomUtils.randomIntInRange(400, 1200))
        if (kira.config?.kiraHit == true) {
            Mouse.startLeftAC()
        } else {
            Mouse.stopLeftAC()
        }
    }

    override fun onGameEnd() {
        shotsFired = 0

        speedPotsLeft = 2
        regenPotsLeft = 2
        gapsLeft = 6

        lastSpeedUse = 0L
        lastRegenUse = 0L
        lastPotion = 0L
        lastGap = 0L
        gameStartAt = 0L
        retreating = false

        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout(fun () {
            i?.cancel()
            Mouse.stopTracking()              // clean
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
    }

    override fun onAttack() {
        val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())
        if (mc.thePlayer != null && mc.thePlayer.heldItem != null) {
            val n = mc.thePlayer.heldItem.unlocalizedName.lowercase()
            if (n.contains("rod")) {
                Combat.wTap(300)
                tapping = true
                combo--
                TimeUtils.setTimeout(fun () { tapping = false }, 300)
            } else if (n.contains("sword")) {
                if (distance < 2f) {
                    Mouse.rClick(RandomUtils.randomIntInRange(60, 90))
                } else {
                    Combat.wTap(100)
                    tapping = true
                    TimeUtils.setTimeout(fun () { tapping = false }, 100)
                }
            }
        }
    }

    override fun onTick() {
        if (opponent() != null && mc.theWorld != null && mc.thePlayer != null) {
            if (!mc.thePlayer.isSprinting) Movement.startSprinting()

            val distance = EntityUtils.getDistanceNoY(mc.thePlayer, opponent())

            var hasSpeed = false
            for (effect in mc.thePlayer.activePotionEffects) {
                if (effect.effectName.lowercase().contains("speed")) hasSpeed = true
            }

            // tracking ON en continu
            Mouse.startTracking()

            if (kira.config?.kiraHit == true && !retreating) {
                Mouse.startLeftAC()
            } else {
                Mouse.stopLeftAC()
            }

            if (distance > 8.8f) {
                if (opponent() != null && opponent()!!.heldItem != null && opponent()!!.heldItem.unlocalizedName.lowercase().contains("bow")) {
                    if (!Mouse.isRunningAway()) Movement.stopJumping()
                } else {
                    Movement.startJumping()
                }
            } else {
                Movement.stopJumping()
            }

            val movePriority = arrayListOf(0, 0)
            var clear = false
            var randomStrafe = false

            if (distance < 0.7f || (distance < 1.4f && combo >= 1)) {
                Movement.stopForward()
            } else if (!tapping) {
                Movement.startForward()
            }

            if (distance < 1.5f && mc.thePlayer.heldItem != null &&
                !mc.thePlayer.heldItem.unlocalizedName.lowercase().contains("sword") &&
                !Mouse.isUsingPotion()) {
                Inventory.setInvItem("sword")
                Mouse.rClickUp()
            }

            if (!hasSpeed && speedPotsLeft > 0 &&
                System.currentTimeMillis() - lastSpeedUse > 15000 &&
                System.currentTimeMillis() - lastPotion > 3500) {
                if (speedPotsLeft == 2) {
                    Movement.startJumping()
                    useSplashPotion(speedDamage, distance < 3.5f, EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!))
                    speedPotsLeft--
                    lastSpeedUse = System.currentTimeMillis()
                    if (regenPotsLeft == 2) {
                        var check: java.util.Timer? = null
                        check = TimeUtils.setInterval({
                            if (!Mouse.isUsingPotion()) {
                                check?.cancel()
                                Movement.startJumping()
                                TimeUtils.setTimeout({
                                    useSplashPotion(regenDamage, false, EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!))
                                    regenPotsLeft--
                                    lastRegenUse = System.currentTimeMillis()
                                }, RandomUtils.randomIntInRange(150, 300))
                            }
                        }, 0, 50)
                    }
                } else {
                    retreatAndSplash(speedDamage) {
                        speedPotsLeft--
                        lastSpeedUse = System.currentTimeMillis()
                    }
                }
            }

            if (WorldUtils.blockInFront(mc.thePlayer, 3f, 1.5f) != Blocks.air) {
                Mouse.setRunningAway(false)
            }

            val now = System.currentTimeMillis()
            if (((distance > 3f && mc.thePlayer.health < 12) || mc.thePlayer.health < 9) &&
                combo < 2 && mc.thePlayer.health <= opponent()!!.health) {
                if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() &&
                    now - lastPotion > 3500) {
                    if (gapsLeft > 0 && now - lastGap > 4000) {
                        useGap(distance, distance < 2f, EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!))
                        gapsLeft--
                    } else if (regenPotsLeft > 0 && now - gameStartAt >= 120000 && now - lastRegenUse > 3500) {
                        retreatAndSplash(regenDamage) {
                            regenPotsLeft--
                            lastRegenUse = System.currentTimeMillis()
                        }
                    }
                }
            }

            if (!Mouse.isUsingProjectile() && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown &&
                System.currentTimeMillis() - lastGap > 2500) {

                if ((distance in 5.7f..6.5f || distance in 9.0f..9.5f) &&
                    !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                    useRod()
                } else if ((EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!) && distance in 3.5f..30f) ||
                           (distance in 28.0f..33.0f && !EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!))) {
                    if (distance > 10f && shotsFired < maxArrows && System.currentTimeMillis() - lastPotion > 5000) {
                        clear = true
                        useBow(distance) { shotsFired++ }
                    } else {
                        clear = false
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                        else movePriority[1] += 4
                    }
                } else {
                    if (opponent()!!.isInvisibleToPlayer(mc.thePlayer)) {
                        clear = false
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                        else movePriority[1] += 4
                    } else if (EntityUtils.entityFacingAway(mc.thePlayer, opponent()!!)) {
                        if (WorldUtils.leftOrRightToPoint(mc.thePlayer, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4
                        else movePriority[1] += 4
                    } else {
                        if (distance < 8f) {
                            val rotations = EntityUtils.getRotations(opponent()!!, mc.thePlayer, false)
                            if (rotations != null) {
                                if (rotations[0] < 0) movePriority[1] += 5 else movePriority[0] += 5
                            }
                        } else {
                            randomStrafe = (opponent()!!.heldItem != null &&
                                (opponent()!!.heldItem.unlocalizedName.lowercase().contains("bow") ||
                                 opponent()!!.heldItem.unlocalizedName.lowercase().contains("rod")))
                            if (randomStrafe && distance < 15f) Movement.stopJumping()
                        }
                    }
                }
            }

            if (WorldUtils.blockInPath(mc.thePlayer, RandomUtils.randomIntInRange(3, 7), 1f) == Blocks.fire) {
                Movement.singleJump(RandomUtils.randomIntInRange(200, 400))
            }

            handle(clear, randomStrafe, movePriority)
        }
    }
}
