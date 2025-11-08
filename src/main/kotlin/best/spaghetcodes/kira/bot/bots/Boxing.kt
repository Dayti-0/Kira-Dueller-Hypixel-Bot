package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.StateManager
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Boxing : BotBase("/play duels_boxing_duel"), MovePriority {

    override fun getName(): String = "Boxing"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.boxing_duel_wins",
                "losses" to "player.stats.Duels.boxing_duel_losses",
                "ws" to "player.stats.Duels.current_boxing_winstreak",
            )
        )
    }

    // ======== ÉTAT ========
    private var tapping = false
    private var fishTimer: Timer? = null

    // Strafe
    private var strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f
    private var lastTacticalJumpAt = 0L

    // Close-range state machine
    private var closeStrafeMode = 0
    private val MODE_BURST = 0
    private val MODE_HOLD_LEFT = 1
    private val MODE_HOLD_RIGHT = 2
    private var closeStrafeNextAt = 0L
    private var closeStrafeToggleAt = 0L

    // “Long strafe” opportuniste (hérité d’OP)
    private var lastCloseStrafeSwitch = 0L
    private var longStrafeUntil = 0L
    private var longStrafeChance = 25
    private fun computeCloseStrafeDelay(distance: Float): Long = when {
        distance < 2.0f -> RandomUtils.randomIntInRange(120, 160).toLong()
        distance < 2.8f -> RandomUtils.randomIntInRange(180, 250).toLong()
        else -> RandomUtils.randomIntInRange(220, 300).toLong()
    }
    private fun shouldStartLongStrafe(distance: Float, nowMs: Long): Boolean {
        if (longStrafeUntil > nowMs) return false
        if (distance > 3.8f) return false
        val chance = when {
            distance < 2.5f -> longStrafeChance + 15
            distance < 3.2f -> longStrafeChance + 5
            else -> longStrafeChance
        }
        return RandomUtils.randomIntInRange(1, 100) <= chance
    }

    override fun onGameStart() {
        // Mouvement + tracking + AC (inchangés)
        Movement.startSprinting()
        Movement.startForward()
        if (kira.config?.boxingFish == true) {
            TimeUtils.setTimeout(this::fishFunc, RandomUtils.randomIntInRange(10000, 20000))
        }
        Mouse.startTracking()
        if (kira.config?.kiraHit == true) Mouse.startLeftAC() else Mouse.stopLeftAC()

        // Init strafe
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        lastStrafeSwitch = 0L
        prevDistance = -1f
        lastTacticalJumpAt = 0L

        // Close-range init
        closeStrafeMode = MODE_BURST
        closeStrafeNextAt = 0L
        closeStrafeToggleAt = 0L
        lastCloseStrafeSwitch = 0L
        longStrafeUntil = 0L
    }

    private fun fishFunc(fish: Boolean = true) {
        if (StateManager.state == StateManager.States.PLAYING) {
            if (fish) Inventory.setInvItem("fish") else Inventory.setInvItem("sword")
            fishTimer = TimeUtils.setTimeout({ fishFunc(!fish) }, RandomUtils.randomIntInRange(10000, 20000))
        }
    }

    override fun onGameEnd() {
        TimeUtils.setTimeout({
            Movement.clearAll()
            Mouse.stopLeftAC()
            Combat.stopRandomStrafe()
            fishTimer?.cancel()
            Mouse.stopTracking()
        }, RandomUtils.randomIntInRange(100, 300))
    }

    override fun onAttack() {
        // W-tap court + léger stick avant pour éviter le “strafe-only”
        tapping = true
        Combat.wTap(100)
        TimeUtils.setTimeout({ tapping = false }, 100)
        if (combo >= 3) Movement.clearLeftRight()
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        // Saut anti-bloc devant (inchangé)
        if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(150, 250))
            lastTacticalJumpAt = System.currentTimeMillis()
        }

        // Tracking + AC permanents (inchangés)
        Mouse.startTracking()
        if (kira.config?.kiraHit == true) Mouse.startLeftAC() else Mouse.stopLeftAC()

        val now = System.currentTimeMillis()
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        // Micro-jump combo (déjà présent) quand on mène le combo
        if (combo >= 3 && distance >= 3.2f && p.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(100, 150))
            lastTacticalJumpAt = now
        }

        // Avancer/stopper (plus souple)
        if (distance < 1.0f || (distance < 2.2f && combo >= 2 && approaching)) {
            Movement.stopForward()
        } else if (!tapping) {
            Movement.startForward()
        }

        // ================== Priorités & Flags ==================
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        // ======= Strafe directionnel selon facing =======
        if (EntityUtils.entityFacingAway(p, opp)) {
            // l’adversaire fuit -> on privilégie le côté menant vers 0,0 pour rester sur la ligne
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
        } else {
            // ======= CLOSE RANGE < 2.6 blocs : machine d’état =======
            if (distance < 2.6f) {
                // Long strafe opportuniste (hérité d’OP) — évite spam d’inversion au corps-à-corps
                if (shouldStartLongStrafe(distance, now)) {
                    longStrafeUntil = now + RandomUtils.randomIntInRange(1200, 2500)
                    strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                    lastCloseStrafeSwitch = now
                    closeStrafeNextAt = longStrafeUntil + RandomUtils.randomIntInRange(100, 300)
                }

                if (now >= closeStrafeNextAt) {
                    val roll = RandomUtils.randomIntInRange(0, 99)
                    closeStrafeMode = when {
                        roll < 50 -> MODE_BURST          // alternances ultra courtes
                        roll < 75 -> MODE_HOLD_LEFT      // maintien gauche
                        else      -> MODE_HOLD_RIGHT     // maintien droit
                    }
                    closeStrafeNextAt = now + when (closeStrafeMode) {
                        MODE_BURST -> RandomUtils.randomIntInRange(280, 420).toLong()
                        else       -> RandomUtils.randomIntInRange(220, 340).toLong()
                    }
                    if (closeStrafeMode == MODE_BURST) {
                        closeStrafeToggleAt = now + RandomUtils.randomIntInRange(60, 110)
                    } else {
                        strafeDir = if (closeStrafeMode == MODE_HOLD_LEFT) -1 else 1
                    }
                } else if (closeStrafeMode == MODE_BURST && now >= closeStrafeToggleAt && now >= longStrafeUntil) {
                    // burst: on retourne très vite
                    strafeDir = -strafeDir
                    closeStrafeToggleAt = now + RandomUtils.randomIntInRange(60, 110)
                } else if (now >= longStrafeUntil && now >= closeStrafeNextAt && now - lastCloseStrafeSwitch >= 150) {
                    // cadence mini entre flips quand pas en “long strafe”
                    strafeDir = -strafeDir
                    lastCloseStrafeSwitch = now
                    closeStrafeNextAt = now + computeCloseStrafeDelay(distance)
                } else if (closeStrafeNextAt == 0L) {
                    closeStrafeNextAt = now + computeCloseStrafeDelay(distance)
                }

                val weightClose = if (longStrafeUntil > now) 6 else 4
                if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                Movement.startSprinting()
                randomStrafe = false
            } else {
                // ======= MID (2.6–6.5) / LONG (≥6.5) =======
                // Inversions cadencées (moins fréquentes si >5.5)
                if (distance < 5.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(1500, 2200)) {
                    strafeDir = -strafeDir; lastStrafeSwitch = now
                } else if (distance >= 5.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(2000, 3000)) {
                    strafeDir = -strafeDir; lastStrafeSwitch = now
                }

                // Anti-stagnation : si la distance varie très peu à ~close-mid, on flippe
                val deltaDist = if (prevDistance > 0f) abs(distance - prevDistance) else 999f
                if (distance in 1.8f..3.6f && deltaDist < 0.03f && now - lastStrafeSwitch > 260) {
                    strafeDir = -strafeDir
                    lastStrafeSwitch = now
                }

                val weight = if (distance < 4f) 7 else 5
                if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight

                // Strafe aléatoire “safe” à longue portée (évite le saut quand opp. à l’arc/rod dans d’autres modes)
                randomStrafe = distance >= 8.0f && distance < 15.0f
            }
        }

        // Sauts contextuels (sobres) pour casser la visée adverse sans casser le sprint
        if (distance > 8.0f) {
            if (p.onGround && now - lastTacticalJumpAt >= 520) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                lastTacticalJumpAt = now
            }
        } else if (distance in 4.5f..8.0f) {
            val facingAway = EntityUtils.entityFacingAway(p, opp)
            val oppStill = EntityUtils.getDistanceNoY(p, opp) > 0f && (abs(opp.posX - opp.lastTickPosX) + abs(opp.posZ - opp.lastTickPosZ) < 0.06)
            if ((facingAway || oppStill) && p.onGround && now - lastTacticalJumpAt >= 720) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                lastTacticalJumpAt = now
            }
        }

        // ==== Conserver ton “handle(...)” pour appliquer movePriority / randomStrafe ====
        handle(clear, randomStrafe, movePriority)

        prevDistance = distance
    }
}
