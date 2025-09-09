package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.Minecraft

/**
 * Canne (Hypixel Classic/OP)
 * Deux voies :
 *  - useRod(): chemin "safe" (petit pré-délai) — historiquement bon pour OP
 *  - useRodImmediate(): chemin immédiat (zéro délai) — pour close/mid range agressif
 */
interface Rod {

    /**
     * Chemin "safe" conservé (petit pré-délai).
     */
    fun useRod() {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        // Switch rod + petit ‘settle’
        Inventory.setInvItem("rod")
        val preDelay = RandomUtils.randomIntInRange(50, 90)
        val clickMs = RandomUtils.randomIntInRange(80, 110)
        val settleAfter = RandomUtils.randomIntInRange(260, 420)

        TimeUtils.setTimeout({
            // sécurité: vérifier qu’on tient bien la rod
            val held = Minecraft.getMinecraft().thePlayer?.heldItem
            if (held == null || !held.unlocalizedName.lowercase().contains("rod")) {
                Inventory.setInvItem("rod")
            }

            // Clic droit bref → lance la ligne
            Mouse.rClick(clickMs)

            // Revenir épée après un petit temps de vol
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                TimeUtils.setTimeout({
                    Mouse.setUsingProjectile(false)
                }, RandomUtils.randomIntInRange(80, 140))
            }, clickMs + settleAfter)
        }, preDelay)
    }

    /**
     * Chemin "immédiat" : switch ➜ clic droit tout de suite (zéro délai).
     * Idéal à très courte distance pour éviter que le clic ne soit "mangé".
     */
    fun useRodImmediate() {
        if (Mouse.isUsingProjectile()) return

        Mouse.stopLeftAC()
        Mouse.setUsingProjectile(true)

        val clickMs = RandomUtils.randomIntInRange(80, 110)
        val settleAfter = RandomUtils.randomIntInRange(260, 380)

        // Switch instant + clic droit immédiat
        Inventory.setInvItem("rod")
        Mouse.rClick(clickMs)

        // Retour épée après court temps de vol
        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            TimeUtils.setTimeout({
                Mouse.setUsingProjectile(false)
            }, RandomUtils.randomIntInRange(80, 140))
        }, clickMs + settleAfter)
    }
}
