package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.ChatUtils
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils

interface Potion {

    var lastPotion: Long

    fun useSplashPotion(
        damage: Int,
        run: Boolean,
        facingAway: Boolean,
        doubleClick: Boolean = false
    ) {
        lastPotion = System.currentTimeMillis()
        fun pot(dmg: Int) {
            Mouse.stopLeftAC()
            if (Inventory.setInvItemByDamage(dmg)) {
                val msg = when (dmg) {
                    16386 -> "Splash Speed"
                    16385 -> "Splash Regen"
                    else -> "About to splash $dmg"
                }
                ChatUtils.info(msg)
                TimeUtils.setTimeout(fun() {
                    Mouse.setUsingPotion(true)

                    fun finishAfter(delay: Int) {
                        TimeUtils.setTimeout(fun() {
                            Mouse.setUsingPotion(false)
                            TimeUtils.setTimeout(fun() {
                                Inventory.setInvItem("sword")

                                TimeUtils.setTimeout(fun() {
                                    Mouse.setRunningAway(false)
                                }, RandomUtils.randomIntInRange(500, 700))
                            }, RandomUtils.randomIntInRange(80, 120))
                        }, delay)
                    }

                    TimeUtils.setTimeout(fun() {
                        val firstHold = RandomUtils.randomIntInRange(80, 120)
                        Mouse.rClick(firstHold)

                        val afterFirst = firstHold + RandomUtils.randomIntInRange(80, 120)

                        if (doubleClick) {
                            val betweenClicks = RandomUtils.randomIntInRange(40, 70)
                            TimeUtils.setTimeout(fun() {
                                val secondHold = RandomUtils.randomIntInRange(70, 110)
                                Mouse.rClick(secondHold)
                                val afterSecond = secondHold + RandomUtils.randomIntInRange(80, 120)
                                finishAfter(afterSecond)
                            }, afterFirst + betweenClicks)
                        } else {
                            finishAfter(afterFirst)
                        }
                    }, RandomUtils.randomIntInRange(100, 200))
                }, RandomUtils.randomIntInRange(50, 100))
            }
        }

        if (run && !facingAway) {
            Mouse.setUsingProjectile(false)
            Mouse.setRunningAway(true)
            TimeUtils.setTimeout(fun() {
                pot(damage)
            }, RandomUtils.randomIntInRange(300, 500))
        } else {
            pot(damage)
        }
    }

    fun usePotion(damage: Int, run: Boolean, facingAway: Boolean) {
        fun pot(dmg: Int) {
            Mouse.stopLeftAC()
            if (Inventory.setInvItemByDamage(dmg)) {
                ChatUtils.info("About to use $dmg")
                TimeUtils.setTimeout(fun () {
                    val r = RandomUtils.randomIntInRange(1900, 2050)
                    Mouse.rClick(r)
                    TimeUtils.setTimeout(fun () {
                        Inventory.setInvItem("sword")
                        TimeUtils.setTimeout(fun() {
                            Mouse.setRunningAway(false)
                        }, RandomUtils.randomIntInRange(500, 700))
                    }, r + RandomUtils.randomIntInRange(80, 120))
                }, RandomUtils.randomIntInRange(200, 400))
            } else {
                ChatUtils.error("No $dmg potion in inventory")
            }
        }

        if (run && !facingAway) {
            Mouse.setUsingProjectile(false)
            Mouse.setRunningAway(true)
            TimeUtils.setTimeout(fun() {
                pot(damage)
            }, RandomUtils.randomIntInRange(300, 500))
        } else {
            pot(damage)
        }
    }

}