package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.kira
import net.minecraft.client.Minecraft
import java.util.Timer

// Timers used during bow usage so they can be cancelled if needed
var bowReleaseTimer: Timer? = null
var bowFinalTimer: Timer? = null
var bowPollTimer: Timer? = null

fun cancelBowTimers() {
    bowReleaseTimer?.cancel()
    bowFinalTimer?.cancel()
    bowPollTimer?.cancel()
    bowReleaseTimer = null
    bowFinalTimer = null
    bowPollTimer = null
}

/**
 * Arc (Hypixel Classic/OP)
 * Deux voies :
 *  - useBow(distance): chemin "safe" (petit pré-délai) — utilisé historiquement par OP
 *  - useBowImmediateFull(): chemin immédiat (zéro délai) — idéal pour Classic agressif
 */
interface Bow {

    val bowMinHoldMs: Int get() = 1150
    val bowMaxHoldMs: Int get() = 1300

    /**
     * Chemin "safe" conservé pour compat OP (pré-délai léger).
     */
    fun useBow(distance: Float, afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        // Switch sur l’arc + petit ‘settle’ (compat packs / ping OP)
        Inventory.setInvItem("bow")
        val preDelay = RandomUtils.randomIntInRange(60, 110)
        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        TimeUtils.setTimeout({
            // sécurité: s’assurer qu’on tient bien un arc (packs, latence)
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("bow")) {
                Inventory.setInvItem("bow")
            }

            Mouse.rClickDown()

            bowReleaseTimer = TimeUtils.setTimeout({
                Mouse.rClickUp()
            }, hold)

            var pollTimer: Timer? = null
            bowFinalTimer = TimeUtils.setTimeout({
                pollTimer?.cancel()
                Mouse.setUsingProjectile(false)
                Inventory.setInvItem("sword")
                afterShot()
                bowReleaseTimer = null
                bowFinalTimer = null
                bowPollTimer = null
            }, hold + RandomUtils.randomIntInRange(90, 150))

            pollTimer = TimeUtils.setInterval({
                val player = Minecraft.getMinecraft().thePlayer
                val opp = kira.bot?.opponent()
                if (player != null && opp != null) {
                    val dist = EntityUtils.getDistanceNoY(player, opp)
                    if (dist <= 5f) {
                        Mouse.rClickUp()
                        bowReleaseTimer?.cancel()
                        bowFinalTimer?.cancel()
                        bowPollTimer?.cancel()
                        Mouse.setUsingProjectile(false)
                        Inventory.setInvItem("sword")
                        afterShot()
                        bowReleaseTimer = null
                        bowFinalTimer = null
                        bowPollTimer = null
                    }
                }
            }, 0, 50)
            bowPollTimer = pollTimer
        }, preDelay)
    }

    /**
     * Chemin "immédiat" sans aucun pré-délai : switch ➜ rClick(hold) tout de suite.
     * A utiliser quand on veut zéro latence (ex. Classic agressif).
     */
    fun useBowImmediateFull(afterShot: () -> Unit = {}) {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        val hold = RandomUtils.randomIntInRange(bowMinHoldMs, bowMaxHoldMs)

        // Switch instant + clic droit immédiat
        Inventory.setInvItem("bow")
        Mouse.rClickDown()

        bowReleaseTimer = TimeUtils.setTimeout({
            Mouse.rClickUp()
        }, hold)

        var pollTimer: Timer? = null
        bowFinalTimer = TimeUtils.setTimeout({
            pollTimer?.cancel()
            Mouse.setUsingProjectile(false)
            Inventory.setInvItem("sword")
            afterShot()
            bowReleaseTimer = null
            bowFinalTimer = null
            bowPollTimer = null
        }, hold + RandomUtils.randomIntInRange(90, 150))

        pollTimer = TimeUtils.setInterval({
            val player = Minecraft.getMinecraft().thePlayer
            val opp = kira.bot?.opponent()
            if (player != null && opp != null) {
                val dist = EntityUtils.getDistanceNoY(player, opp)
                if (dist <= 5f) {
                    Mouse.rClickUp()
                    bowReleaseTimer?.cancel()
                    bowFinalTimer?.cancel()
                    bowPollTimer?.cancel()
                    Mouse.setUsingProjectile(false)
                    Inventory.setInvItem("sword")
                    afterShot()
                    bowReleaseTimer = null
                    bowFinalTimer = null
                    bowPollTimer = null
                }
            }
        }, 0, 50)
        bowPollTimer = pollTimer
    }
}
