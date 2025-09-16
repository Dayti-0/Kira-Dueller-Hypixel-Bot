package best.spaghetcodes.kira.bot.features

import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import best.spaghetcodes.kira.utils.ChatUtils

/**
 * Gestion du briquet (flint and steel) pour Hypixel OP.
 * Place rapidement un feu au sol en regardant vers le bas et en reculant.
 */
interface Flint {

    /** Nombre d'utilisations restantes. */
    var flintUses: Int

    /**
     * Utilise le briquet si disponible. Le joueur regarde vers le bas (~45°),
     * recule pendant l'ignition puis exécute le callback [after].
     */
    fun useFlint(distance: Float, after: () -> Unit = {}) {
        if (flintUses <= 0) return
        // Unlocalized name is "item.flintAndSteel" -> substring "flintandsteel"
        if (!Inventory.setInvItem("flintandsteel")) {
            ChatUtils.warn("No flint and steel found in hotbar")
            after()
            return
        }

        ChatUtils.info("About to use flint and steel")
        flintUses--
        Mouse.stopLeftAC()
        kira.mc.thePlayer?.rotationPitch = RandomUtils.randomIntInRange(44, 50).toFloat()
        Movement.stopForward()
        Movement.startBackward()

        val clickMs = RandomUtils.randomIntInRange(80, 110)
        Mouse.rClick(clickMs)

        TimeUtils.setTimeout({
            after()
        }, clickMs + RandomUtils.randomIntInRange(120, 180))
    }
}

