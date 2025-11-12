package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.features.Bow
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.features.Rod
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Classic : BotBase("/play duels_classic_duel"), Bow, Rod, MovePriority {

    override fun getName(): String = "Classic"

    init {
        setStatKeys(
            mapOf(
                "wins" to "player.stats.Duels.classic_duel_wins",
                "losses" to "player.stats.Duels.classic_duel_losses",
                "ws" to "player.stats.Duels.current_classic_winstreak",
            )
        )
    }

    // =====================  ARC  =====================
    private val fullDrawMsMin = 820
    private val fullDrawMsMax = 980
    private val bowCancelCloseDist = 8.0f
    private val bowMinUseDist = 9.0f

    // Ouverture contrôlée
    private var openVolleyMax = 1
    private var openVolleyFired = 0
    private var openWindowUntil = 0L
    private var openStartDelayUntil = 0L
    private var lastShotAt = 0L
    private val openSpacingMin = 650L
    private val openSpacingMax = 900L
    private val openShotMinDist = 9.0f

    // Détection immobile / slow-bow
    private val stillFrameThreshold = 0.0125
    private val stillFramesNeeded = 10
    private val bowSlowThreshold = 0.06
    private val bowSlowFramesNeeded = 3
    private var oppLastX = 0.0
    private var oppLastZ = 0.0
    private var stillFrames = 0
    private var bowSlowFrames = 0

    private var shotsFired = 0
    private val maxArrows = 5
    private var bowHardLockUntil = 0L
    private var projectileGraceUntil = 0L
    private var pendingProjectileUntil = 0L
    private var actionLockUntil = 0L
    private var projectileKind = 0
    private val KIND_NONE = 0
    private val KIND_BOW = 2

    private var lastReactiveShotAt = 0L
    private val reactiveCdMs = 650L

    // Réserve d'arrows
    private var gameStartAt = 0L
    private val reserveTightMs = 10_000L
    private val earlyReserve = 3
    private val midReserve = 2

    // =====================  ROD  =====================
    private var lastRodUse = 0L
    private var rodCdCloseMsBase = 300L  // Plus agressif (était 340)
    private var rodCdFarMsBase = 420L    // Plus agressif (était 480)
    private var rodCdBias = 1.0f
    private val rodCdBiasMax = 1.25f

    private val rodBanMeleeDist = 3.5f  // Réduit (était 4.0f)

    private val rodCloseMin = 2.0f
    private val rodCloseMax = 3.4f
    private val rodMainMin = 3.0f
    private val rodMainMax = 6.8f
    private val rodInterceptMin = 5.8f
    private val rodInterceptMax = 7.2f
    private val rodMaxRangeHard = 7.2f

    // Fenêtre mid-range instantanée
    private val rodMidInstantMin = 5.5f
    private val rodMidInstantMax = 7.0f

    // Anti-spam rod
    private var rodAntiSpamUntil = 0L

    // Détection "éloignement -> retour"
    private var farSince = 0L
    private val farThreshold = 11.0f
    private var reentryRodGraceUntil = 0L

    // Heuristique hit/miss via hurtTime
    private var lastRodAttemptAt = 0L
    private var lastOppHurtTime = 0
    private var pendingRodCheck = false
    private var rodHits = 0
    private var rodMisses = 0

    // Fenêtres "melee focus" & stick avant
    private var meleeFocusUntil = 0L
    private var forwardStickUntil = 0L

    // Opponent rod usage
    private var lastOppRodSeenAt = 0L

    // Maintien minimal de la rod APRÈS cast
    private var rodHoldUntil = 0L

    // ==================  PARADE ÉPÉE  =================
    private val parryMinDist = 12.0f  // Réduit (était 15.0f)
    private val parryCloseCancelDist = 12.0f
    private val parryCooldownMs = 900L
    private val parryHoldMinMs = 650
    private val parryHoldMaxMs = 980
    private val parryStickMinMs = 900
    private val parryStickMaxMs = 1500

    private var lastSwordBlock = 0L
    private var holdBlockUntil = 0L
    private var parryFromBow = false
    private var parryExtendedUntil = 0L
    private var parryCloseLockUntil = 0L

    // Strafe & saut pendant parade
    private var parryStrafeDir = 1
    private var parryStrafeFlipAt = 0L
    private var lastParryJumpAt = 0L
    private val parryJumpCd = 580L

    // Anti-parade précoce
    private var allowParryAfter = 0L

    // ==================  MOUVEMENT  ===================
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f
    private var lastTacticalJumpAt = 0L
    private var lastGotHitAt = 0L
    private var tapping = false

    // Sauts de démarrage
    private var startupJumping = true

    // Close-range strafe state machine
    private var closeStrafeMode = 0
    private val MODE_BURST = 0
    private val MODE_HOLD_LEFT = 1
    private val MODE_HOLD_RIGHT = 2
    private var closeStrafeNextAt = 0L
    private var closeStrafeToggleAt = 0L

    // ====================  LIFECYCLE  ==================
    override fun onGameStart() {
        Mouse.startTracking()
        Movement.startSprinting()
        Movement.startForward()
        Mouse.rClickUp()

        if (kira.config?.kiraHit == true) {
            Mouse.startLeftAC()
        } else {
            Mouse.stopLeftAC()
        }

        startupJumping = true

        prevDistance = -1f
        lastStrafeSwitch = 0L
        strafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1

        shotsFired = 0
        bowHardLockUntil = 0L
        projectileGraceUntil = 0L
        pendingProjectileUntil = 0L
        actionLockUntil = 0L
        projectileKind = KIND_NONE

        openVolleyMax = RandomUtils.randomIntInRange(1, 2)
        openVolleyFired = 0
        openWindowUntil = System.currentTimeMillis() + 4500L
        openStartDelayUntil = System.currentTimeMillis() + RandomUtils.randomIntInRange(700, 1100)
        lastShotAt = 0L

        oppLastX = 0.0
        oppLastZ = 0.0
        stillFrames = 0
        bowSlowFrames = 0
        lastOppHurtTime = 0

        lastReactiveShotAt = 0L
        gameStartAt = System.currentTimeMillis()

        lastRodUse = 0L
        rodCdBias = 1.0f
        rodHits = 0
        rodMisses = 0
        pendingRodCheck = false
        lastRodAttemptAt = 0L
        lastOppRodSeenAt = 0L
        rodHoldUntil = 0L
        rodAntiSpamUntil = 0L

        lastTacticalJumpAt = 0L
        lastGotHitAt = 0L

        lastSwordBlock = 0L
        holdBlockUntil = 0L
        parryFromBow = false
        parryExtendedUntil = 0L
        parryCloseLockUntil = 0L
        parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
        parryStrafeFlipAt = 0L
        lastParryJumpAt = 0L

        forwardStickUntil = 0L
        meleeFocusUntil = 0L

        closeStrafeMode = MODE_BURST
        closeStrafeNextAt = 0L
        closeStrafeToggleAt = 0L

        allowParryAfter = gameStartAt + 2800L
    }

    override fun onGameEnd() {
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
        startupJumping = false
    }

    override fun onAttack() {
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 100)

        val now = System.currentTimeMillis()
        forwardStickUntil = now + RandomUtils.randomIntInRange(220, 280)
        meleeFocusUntil = now + RandomUtils.randomIntInRange(300, 340)
        TimeUtils.setTimeout({
            Movement.startForward()
            Movement.startSprinting()
        }, 80)

        if (combo >= 3) Movement.clearLeftRight()
    }

    // =====================  HELPERS  ==================
    private fun adjustedAimDistance(d: Float): Float = when {
        d in 15.0f..22.0f -> d * 0.84f
        d in 22.0f..30.0f -> d * 0.83f
        d in 9.0f..15.0f  -> d * 0.90f
        else              -> d
    }

    private fun chargeMsFor(distance: Float, opening: Boolean): Long {
        return if (opening) {
            RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
        } else {
            when {
                distance < 6.0f   -> RandomUtils.randomIntInRange(220, 320).toLong()
                distance < 10.0f  -> RandomUtils.randomIntInRange(320, 450).toLong()
                distance < 15.0f  -> RandomUtils.randomIntInRange(450, 650).toLong()
                distance < 25.0f  -> RandomUtils.randomIntInRange(550, 800).toLong()
                else              -> RandomUtils.randomIntInRange(fullDrawMsMin, fullDrawMsMax).toLong()
            }
        }
    }

    private fun arrowsLeft(): Int = maxArrows - shotsFired
    private fun reserveNeeded(now: Long): Int =
        if (now - gameStartAt < reserveTightMs) earlyReserve else midReserve

    // Rod cast fiable - instantané + anti-spam + retour épée
    private fun castRodNow(distanceNow: Float) {
        val nowClick = System.currentTimeMillis()

        Mouse.setUsingProjectile(true)
        if (Mouse.rClickDown) Mouse.rClickUp()
        Mouse.rClick(RandomUtils.randomIntInRange(70, 95))
        reentryRodGraceUntil = 0L

        // HOLD DE ROD APRÈS CAST
        val holdMs = when {
            distanceNow < 3.0f -> RandomUtils.randomIntInRange(118, 142)
            distanceNow < 4.8f -> RandomUtils.randomIntInRange(160, 190)
            distanceNow <= 6.2f -> RandomUtils.randomIntInRange(208, 232)
            else               -> RandomUtils.randomIntInRange(210, 235)
        }
        rodHoldUntil = nowClick + holdMs

        val settle = RandomUtils.randomIntInRange(200, 260)
        pendingProjectileUntil = nowClick + 80L
        actionLockUntil = nowClick + settle + 80
        projectileGraceUntil = actionLockUntil

        lastRodAttemptAt = nowClick
        pendingRodCheck = true
        lastOppHurtTime = opponent()?.hurtTime ?: 0

        // Sélection rod
        val held = mc.thePlayer?.heldItem?.unlocalizedName?.lowercase()
        if (held == null || !held.contains("rod")) {
            Inventory.setInvItem("rod")
        }

        // Retour à l'épée automatique après le hold
        TimeUtils.setTimeout({
            Inventory.setInvItem("sword")
            Mouse.setUsingProjectile(false)
        }, max(holdMs + 20, settle))

        lastRodUse = nowClick

        // Anti-SPAM dynamique
        val oppPassive = (nowClick - lastOppRodSeenAt) > 5000L
        val antiSpam = when {
            distanceNow < 3.0f -> if (oppPassive) RandomUtils.randomIntInRange(340, 420) else RandomUtils.randomIntInRange(260, 320)
            distanceNow <= 6.2f -> if (oppPassive) RandomUtils.randomIntInRange(520, 680) else RandomUtils.randomIntInRange(380, 520)
            else -> if (oppPassive) RandomUtils.randomIntInRange(520, 700) else RandomUtils.randomIntInRange(400, 560)
        }
        rodAntiSpamUntil = nowClick + antiSpam

        meleeFocusUntil = max(meleeFocusUntil, nowClick + RandomUtils.randomIntInRange(240, 360))
    }

    private fun updateRodAccuracyHeuristic(now: Long) {
        if (!pendingRodCheck) return
        val opp = opponent() ?: return

        val dt = now - lastRodAttemptAt
        if (dt in 80..420) {
            val ht = opp.hurtTime
            if (ht > 0 && ht != lastOppHurtTime) {
                rodHits++
                pendingRodCheck = false
                rodCdBias = max(0.85f, rodCdBias * 0.92f)
            }
        } else if (dt > 480) {
            rodMisses++
            pendingRodCheck = false
            if (rodMisses - rodHits >= 2) {
                rodCdBias = min(rodCdBiasMax, rodCdBias * 1.10f)
            }
        }
    }

    private fun opponentLikelyUsingRod(opp: net.minecraft.entity.EntityLivingBase): Boolean {
        val held = opp.heldItem
        return held != null && held.unlocalizedName.lowercase().contains("rod")
    }

    // ======================  TICK  =====================
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

        // Sauts de début : ACTIFS EN CONTINU jusqu'au PREMIER brandissage d'ARC
        if (startupJumping) {
            Movement.startJumping()
        } else {
            Movement.stopJumping()
        }

        val now = System.currentTimeMillis()
        val hbActive = now < hbActiveUntil
        val distance = EntityUtils.getDistanceNoY(p, opp)
        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        // Détecte "loin" -> "ré-entrée" (approche)
        if (distance > farThreshold) {
            if (farSince == 0L) farSince = now
        } else {
            if (farSince != 0L && (now - farSince) >= 500L && approaching) {
                reentryRodGraceUntil = now + 300L
            }
            farSince = 0L
        }

        // Suivi rod adverse
        if (opponentLikelyUsingRod(opp)) lastOppRodSeenAt = now

        // Immobile/slow + vitesse totale
        if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
        oppLastX = opp.posX; oppLastZ = opp.posZ

        if (p.hurtTime > 0) lastGotHitAt = now

        // Anti-bloc devant
        if (distance > 2.2f) {
            if (WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air && p.onGround) {
                Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
                lastTacticalJumpAt = now
            }
        }

        // KB/lag : colle l'avance
        if (prevDistance > 0f && distance - prevDistance > 0.6f) {
            forwardStickUntil = max(forwardStickUntil, now + 200)
        }

        val projectileActive =
            Mouse.isUsingProjectile() || now < projectileGraceUntil || now < pendingProjectileUntil || now < actionLockUntil

        // Avance / arrêt (avec stick avant)
        if (now < forwardStickUntil) {
            Movement.startForward()
        } else {
            if (distance < 0.75f || (distance < 2.4f && combo >= 2 && approaching)) {
                Movement.stopForward()
            } else {
                Movement.startForward()
            }
        }

        // Tenir l'épée si très proche
        if (distance < 1.5f &&
            p.heldItem != null &&
            !p.heldItem.unlocalizedName.lowercase().contains("sword") &&
            !projectileActive &&
            now >= rodHoldUntil) {
            Inventory.setInvItem("sword")
        }

        // Annuler l'arc si trop proche
        if (projectileActive && Mouse.rClickDown && projectileKind == KIND_BOW && distance < bowCancelCloseDist) {
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        // MAJ heuristique rod
        updateRodAccuracyHeuristic(now)

        // =================  PARADE ÉPÉE  ================
        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")
        val isStillNow = stillFrames >= stillFramesNeeded
        val oppHasBowNow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowLikely = oppHasBowNow && (isStillNow || bowSlowFrames >= bowSlowFramesNeeded)

        // Close cancel strict
        if (Mouse.rClickDown && distance < parryCloseCancelDist && !hbActive) {
            Mouse.rClickUp()
            parryFromBow = false
            parryExtendedUntil = 0L
            parryCloseLockUntil = now + 700L
        }

        if (holdingSword) {
            if (!Mouse.rClickDown) {
                val closeRange = distance < parryCloseCancelDist

                if (!closeRange &&
                    !startupJumping &&
                    now >= allowParryAfter &&
                    bowLikely &&
                    !projectileActive &&
                    WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                    now >= parryCloseLockUntil &&
                    (now - lastSwordBlock) > parryCooldownMs) {

                    val dur = RandomUtils.randomIntInRange(parryHoldMinMs, parryHoldMaxMs)
                    holdBlockUntil = now + dur
                    lastSwordBlock = now
                    parryFromBow = true

                    val extraStick =
                        if (distance > 15f) RandomUtils.randomIntInRange(900, 1200)
                        else RandomUtils.randomIntInRange(500, 800)

                    parryExtendedUntil = now + RandomUtils.randomIntInRange(parryStickMinMs, parryStickMaxMs) + extraStick
                    parryStrafeDir = if (RandomUtils.randomIntInRange(0, 1) == 1) 1 else -1
                    parryStrafeFlipAt = now + RandomUtils.randomIntInRange(260, 420)
                    Mouse.rClick(dur)
                }
            } else {
                // Parade en cours : petits sauts latéraux
                if (distance >= parryCloseCancelDist &&
                    p.onGround &&
                    now - lastParryJumpAt >= parryJumpCd &&
                    !projectileActive &&
                    now - lastGotHitAt > 260) {

                    if (RandomUtils.randomIntInRange(0, 1) == 1 || now >= parryStrafeFlipAt) {
                        parryStrafeDir = -parryStrafeDir
                        parryStrafeFlipAt = now + RandomUtils.randomIntInRange(260, 420)
                    }
                    Movement.singleJump(RandomUtils.randomIntInRange(140, 210))
                    lastParryJumpAt = now
                    Movement.startForward()
                    Movement.startSprinting()
                }

                val mustKeep = parryFromBow && now < parryExtendedUntil
                if (!mustKeep && now >= holdBlockUntil && !hbActive) {
                    Mouse.rClickUp()
                    parryFromBow = false
                    parryExtendedUntil = 0L
                }
            }
        } else {
            if (Mouse.rClickDown && !projectileActive && !hbActive) Mouse.rClickUp()
            parryFromBow = false
            parryExtendedUntil = 0L
        }

        // ========== JUMPS CONTEXTUELS ==========
        if (!Mouse.rClickDown && !projectileActive && (now - lastGotHitAt) > 260 && !startupJumping) {
            val facingAway = EntityUtils.entityFacingAway(p, opp)
            val oppVeryStill = (stillFrames >= 6)
            if (distance > 8.0f) {
                if (p.onGround && now - lastTacticalJumpAt >= 520) {
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                    lastTacticalJumpAt = now
                }
            } else if (distance in 4.5f..8.0f && (facingAway || oppVeryStill)) {
                if (p.onGround && now - lastTacticalJumpAt >= 720) {
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                    lastTacticalJumpAt = now
                }
            }
        }

        // =======================  ROD  ===========================
        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowLikelyNowClose = oppHasBow && (isStillNow || bowSlowFrames >= bowSlowFramesNeeded) && distance <= 10.0f
        val oppRodRecently = (now - lastOppRodSeenAt) <= 2500L
        val allowByAntiSpam = now >= rodAntiSpamUntil || now < reentryRodGraceUntil || oppRodRecently

        if ((!projectileActive || now < reentryRodGraceUntil) &&
            !Mouse.isRunningAway() &&
            !Mouse.isUsingPotion() &&
            (!Mouse.rClickDown || hbActive || now < reentryRodGraceUntil)) {

            if (distance > rodBanMeleeDist) {
                // 1) Cas spécial : immobile mid -> rod prioritaire
                if (isStillNow && distance in rodMidInstantMin..rodMidInstantMax && allowByAntiSpam) {
                    castRodNow(distance)
                    rodAntiSpamUntil = now + RandomUtils.randomIntInRange(300, 380)
                    prevDistance = distance
                    return
                }

                // 2) Anti slow-bow proche
                if (bowLikelyNowClose && distance <= rodMaxRangeHard) {
                    castRodNow(distance)
                    prevDistance = distance
                    return
                }

                if (distance <= rodMaxRangeHard) {
                    // 3) MID-RANGE instant (5.5–7.0)
                    if (distance in rodMidInstantMin..rodMidInstantMax && !projectileActive && allowByAntiSpam) {
                        castRodNow(distance)
                        prevDistance = distance
                        return
                    }

                    val cdClose = (rodCdCloseMsBase * rodCdBias).toLong()
                    val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
                    val cdCloseOK = (now - lastRodUse) >= cdClose || now < reentryRodGraceUntil
                    val cdFarOK = (now - lastRodUse) >= cdFar || now < reentryRodGraceUntil
                    val facingAway = EntityUtils.entityFacingAway(p, opp)

                    val meleeRange = distance < 3.1f
                    val allowRodByMeleePolicy = !(meleeRange && !oppRodRecently && now < meleeFocusUntil)

                    // Close
                    if (allowRodByMeleePolicy &&
                        distance in rodCloseMin..rodCloseMax &&
                        distance > rodBanMeleeDist &&
                        (p.hurtTime > 0 || approaching) &&
                        !facingAway &&
                        cdCloseOK &&
                        allowByAntiSpam) {
                        castRodNow(distance)
                        prevDistance = distance
                        return
                    }

                    // Main / réponse à rod adverse
                    if (allowRodByMeleePolicy && !facingAway && (cdFarOK || cdCloseOK) && allowByAntiSpam) {
                        if (oppRodRecently && distance > rodBanMeleeDist) {
                            castRodNow(distance)
                            prevDistance = distance
                            return
                        }
                        if (distance in rodMainMin..rodMainMax && distance > rodBanMeleeDist) {
                            castRodNow(distance)
                            prevDistance = distance
                            return
                        }
                    }

                    // Interception
                    if (allowRodByMeleePolicy &&
                        distance in rodInterceptMin..rodInterceptMax &&
                        !facingAway &&
                        (cdFarOK || cdCloseOK) &&
                        allowByAntiSpam) {
                        castRodNow(distance)
                        prevDistance = distance
                        return
                    }
                }
            }
        }

        // ========================  ARC  ==========================
        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            val reserve = reserveNeeded(now)
            val left = arrowsLeft()

            val denyBowMidByStill = isStillNow && distance in rodMidInstantMin..rodMidInstantMax
            val denyBowCloseByImmobile = bowLikelyNowClose

            // Ouverture
            if (!denyBowCloseByImmobile &&
                !denyBowMidByStill &&
                shotsFired < maxArrows &&
                openVolleyFired < openVolleyMax &&
                now < openWindowUntil &&
                now >= openStartDelayUntil &&
                distance >= max(openShotMinDist, bowMinUseDist) &&
                left > reserve &&
                (now - lastShotAt) >= RandomUtils.randomIntInRange(openSpacingMin.toInt(), openSpacingMax.toInt())) {

                val lock = chargeMsFor(distance, opening = true)
                startupJumping = false
                bowHardLockUntil = now + lock
                pendingProjectileUntil = now + 60L
                actionLockUntil = now + (lock + 120)
                projectileKind = KIND_BOW
                useBowImmediateFull {
                    shotsFired++
                    openVolleyFired++
                    lastShotAt = System.currentTimeMillis()
                }
                projectileGraceUntil = bowHardLockUntil + 120
                prevDistance = distance
                return
            }

            // Réactif si l'ennemi slow-bow/immobile
            val bowLikelyReact = oppHasBow && (stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)
            if (!denyBowCloseByImmobile &&
                !denyBowMidByStill &&
                shotsFired < maxArrows &&
                bowLikelyReact &&
                distance >= max(bowMinUseDist, 12.0f) &&
                now - lastReactiveShotAt >= reactiveCdMs &&
                WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                left > reserve) {

                val lock = chargeMsFor(distance, opening = false)
                startupJumping = false
                bowHardLockUntil = now + lock
                pendingProjectileUntil = now + 50L
                actionLockUntil = now + (lock + 100)
                projectileKind = KIND_BOW
                useBowImmediateFull {
                    shotsFired++
                    lastReactiveShotAt = System.currentTimeMillis()
                }
                projectileGraceUntil = bowHardLockUntil + 100
                prevDistance = distance
                return
            }

            // Opportuniste (dos / très loin)
            if (!denyBowCloseByImmobile &&
                !denyBowMidByStill &&
                shotsFired < maxArrows && left > reserve && distance >= bowMinUseDist) {
                val away = EntityUtils.entityFacingAway(p, opp)
                if ((away && distance in 3.5f..30f) ||
                    (!away && distance in 28.0f..33.0f)) {

                    val lock = chargeMsFor(distance, opening = false)
                    startupJumping = false
                    bowHardLockUntil = now + lock
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (lock + 120)
                    projectileKind = KIND_BOW
                    useBowImmediateFull { shotsFired++ }
                    projectileGraceUntil = bowHardLockUntil + 120
                    prevDistance = distance
                    return
                }
            }
        }

        // ==================  STRAFE / HANDLE  ===================
        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        val parryActive = Mouse.rClickDown && (now < holdBlockUntil || (parryFromBow && now < parryExtendedUntil))
        if (parryActive) {
            val w = if (distance > 6f) 7 else 9
            if (parryStrafeDir < 0) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        }

        if (EntityUtils.entityFacingAway(p, opp)) {
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
        } else if (!parryActive) {

            // ======= Close-range strafe logic (< 2.6 blocs) =======
            if (distance < 2.6f) {
                // Sélection/renouvèlement du mode
                if (now >= closeStrafeNextAt) {
                    val roll = RandomUtils.randomIntInRange(0, 99)
                    closeStrafeMode = when {
                        roll < 50 -> MODE_BURST              // 50% très rapide
                        roll < 75 -> MODE_HOLD_LEFT          // 25% maintien gauche
                        else     -> MODE_HOLD_RIGHT          // 25% maintien droit
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
                } else if (closeStrafeMode == MODE_BURST && now >= closeStrafeToggleAt) {
                    strafeDir = -strafeDir
                    closeStrafeToggleAt = now + RandomUtils.randomIntInRange(60, 110)
                }

                // Poids et mouvement
                val weightClose = 4
                if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                Movement.startForward()
                Movement.startSprinting()
                randomStrafe = false
            } else {
                // ======= Medium/long range =======
                if (distance < 6.5f && now - lastStrafeSwitch > RandomUtils.randomIntInRange(820, 1100)) {
                    strafeDir = -strafeDir
                    lastStrafeSwitch = now
                }
                val deltaDist = if (prevDistance > 0f) kotlin.math.abs(distance - prevDistance) else 999f
                if (distance in 1.8f..3.6f && deltaDist < 0.03f && now - lastStrafeSwitch > 260) {
                    strafeDir = -strafeDir
                    lastStrafeSwitch = now
                }
                val weight = if (distance < 4f) 7 else 5
                if (strafeDir < 0) movePriority[0] += weight else movePriority[1] += weight
                randomStrafe = (distance in 8.0f..15.0f)
            }
        }

        handle(clear, randomStrafe, movePriority)
        prevDistance = distance
    }
}
