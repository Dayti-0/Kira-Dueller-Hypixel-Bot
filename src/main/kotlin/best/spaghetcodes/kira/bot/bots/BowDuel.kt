package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.bot.features.Bow
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.bot.tuning.BowDuelTuner
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs

class BowDuel : BotBase("/play duels_bow_duel"), Bow, MovePriority {

    override fun getName(): String = "Bow"

    init {
        setStatKeys(
            mapOf(
                "wins"   to "player.stats.Duels.bow_duel_wins",
                "losses" to "player.stats.Duels.bow_duel_losses",
                "ws"     to "player.stats.Duels.current_bow_winstreak",
            )
        )
    }

    // ---------- Tir / cadence ----------
    private var shotsFired = 0
    private var lastShot = 0L
    private var shotCooldownMs = 800L

    // Rafale -> phase d’esquive
    private var burstCount = 0
    private var evasionUntil = 0L
    private var strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
    private var strafeFlipAt = 0L

    // Hop-shot (saut latéral entre les tirs)
    private var forceStrafeDir = 1
    private var forceStrafeUntil = 0L
    private var shotPlannedUntil = 0L

    // ---------- Pearl (1 usage, défensif) ----------
    private var pearls = 1
    private var lastPearl = 0L
    private var pearlCooldown = 6000L
    private var pearlEscapeDist = 20f
    private var burstMinShots = 3
    private var burstMaxShots = 4

    private fun applyParams(params: BowDuelTuner.BowParams) {
        shotCooldownMs = params.shotCooldownMs
        pearlCooldown = params.pearlCooldownMs
        pearlEscapeDist = params.pearlEscapeDist
        burstMinShots = params.burstMin
        burstMaxShots = params.burstMax
    }
    // ------------------------------------------------

    override fun onGameStart() {
        val params = try {
            BowDuelTuner.pickParams()
        } catch (_: Throwable) {
            BowDuelTuner.defaults()
        }
        applyParams(params)
        Mouse.startTracking()
        Movement.startSprinting()
        // IMPORTANT : ne pas avancer vers l’adversaire en bow duel
        Movement.stopForward()
        Mouse.stopLeftAC()

        shotsFired = 0
        lastShot = 0L
        burstCount = 0
        evasionUntil = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        strafeFlipAt = 0L

        pearls = 1
        lastPearl = 0L

        forceStrafeDir = 1
        forceStrafeUntil = 0L
        shotPlannedUntil = 0L

        // Équiper l'arc immédiatement
        Inventory.setInvItem("bow")
    }

    override fun onGameEnd() {
        val win = when {
            Session.wins > Session.losses -> true
            Session.losses > Session.wins -> false
            else -> false
        }
        BowDuelTuner.report(win, 0)
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            shotsFired = 0
            burstCount = 0
            evasionUntil = 0L
            pearls = 1
            lastPearl = 0L
            forceStrafeUntil = 0L
            shotPlannedUntil = 0L
        }, RandomUtils.randomIntInRange(200, 400))
    }

    override fun onTick() {
        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        Mouse.stopLeftAC() // aucune attaque melee

        val distance = EntityUtils.getDistanceNoY(p, opp)
        val now = System.currentTimeMillis()

        // Toujours garder l'arc en main
        if (p.heldItem == null || !p.heldItem.unlocalizedName.lowercase().contains("bow")) {
            Inventory.setInvItem("bow")
        }

        // Anti-bloc : micro-saut si un bloc devant
        if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround && !Mouse.rClickDown) {
            Movement.singleJump(RandomUtils.randomIntInRange(140, 220))
        }

        // Ne JAMAIS sauter pendant qu'on bande l'arc (précision)
        if (Mouse.rClickDown) {
            Movement.stopJumping()
        }

        // --------- PEARL (défensif : s'éloigner quand trop proche) ----------
        if (pearls > 0 &&
            (now - lastPearl) > pearlCooldown &&
            distance <= pearlEscapeDist &&
            !Mouse.isUsingProjectile() &&
            !Mouse.rClickDown) {

            lastPearl = now
            Inventory.setInvItem("pearl")

            // Pendant le lancer on “fuit” (le système de caméra du mod saura orienter à l’opposé)
            Mouse.setRunningAway(true)
            Mouse.setUsingProjectile(true)

            val clickDur = RandomUtils.randomIntInRange(95, 125)
            TimeUtils.setTimeout({
                Mouse.rClick(clickDur)
                TimeUtils.setTimeout({
                    Mouse.setUsingProjectile(false)
                    Mouse.setRunningAway(false)
                    Inventory.setInvItem("bow")
                    pearls--
                }, RandomUtils.randomIntInRange(220, 320))
            }, RandomUtils.randomIntInRange(70, 110))
            return
        }
        // -------------------------------------------------------------------

        // --------- ÉVASION ACTIVE après rafale ----------
        val evading = now < evasionUntil
        if (evading && !Mouse.rClickDown) {
            // évitement : petits sauts + strafe latéral
            Movement.startJumping()
            if (now >= strafeFlipAt) {
                strafeDir = -strafeDir
                strafeFlipAt = now + RandomUtils.randomIntInRange(220, 360)
            }
        } else if (!evading && !Mouse.rClickDown) {
            // Pas de saut pendant les phases calmes (sauf hop-shot)
            Movement.stopJumping()
        }
        // -----------------------------------------------

        // --------- Tir à l’arc orchestré : HOP puis SHOOT ----------
        // 1) Si un tir est planifié, on le déclenche juste après le hop (full charge)
        if (shotPlannedUntil != 0L && now >= shotPlannedUntil && !Mouse.isUsingProjectile()) {
            shotPlannedUntil = 0L
            useBowImmediateFull {
                shotsFired++
                lastShot = System.currentTimeMillis()
                burstCount++

                // Après 3–4 flèches consécutives -> courte phase d'évasion latérale
                if (burstCount >= RandomUtils.randomIntInRange(burstMinShots, burstMaxShots)) {
                    evasionUntil = lastShot + RandomUtils.randomIntInRange(900, 1200)
                    strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                    strafeFlipAt = lastShot + RandomUtils.randomIntInRange(220, 360)
                    burstCount = 0
                }
            }
            return
        }

        // 2) Sinon, décider de lancer un hop-shot (alternance gauche/droite), puis shooter
        if (!Mouse.isUsingProjectile() && (now - lastShot) > shotCooldownMs && shotPlannedUntil == 0L) {
            val shootProb = when {
                distance > 28f -> 100
                distance > 20f -> 90
                distance > 14f -> 75
                distance > 10f -> 60
                else           -> 40
            }

            if (RandomUtils.randomIntInRange(0, 99) < shootProb) {
                // Hop latéral court (entre les tirs), pas pendant la charge
                val hopDur = RandomUtils.randomIntInRange(150, 220)
                forceStrafeDir = -forceStrafeDir
                forceStrafeUntil = now + hopDur
                Movement.singleJump(hopDur)

                // Planifier le tir juste après le hop (stabilisation)
                shotPlannedUntil = now + hopDur + RandomUtils.randomIntInRange(60, 90)
                // Empêcher ré-entrée prématurée
                lastShot = shotPlannedUntil
                return
            }
        } else if ((now - lastShot) > (shotCooldownMs + 700)) {
            // Si on ne tire pas depuis un moment, on casse la rafale
            burstCount = 0
        }
        // ------------------------------------------------------------

        // --------- Strafe / déplacement ----------
        val movePriority = arrayListOf(0, 0)
        var clear = false

        val randomStrafe = when {
            // Strafe forcé pendant hop-shot
            now < forceStrafeUntil -> {
                val w = 10
                if (forceStrafeDir < 0) movePriority[0] += w else movePriority[1] += w
                false
            }
            now < evasionUntil -> {
                val w = if (distance > 18f) 9 else 7
                if (strafeDir < 0) movePriority[0] += w else movePriority[1] += w
                false
            }
            else -> {
                // strafe “intelligent” face à la cible
                val rotations = EntityUtils.getRotations(opp, p, false)
                if (rotations != null && abs(rotations[0]) > 0.0f) {
                    if (rotations[0] < 0) movePriority[1] += 3 else movePriority[0] += 3
                }
                true
            }
        }

        // Rester à distance : ne jamais coller; pas de forward “gratuit”
        Movement.stopForward()

        handle(clear, randomStrafe, movePriority)
    }
}
