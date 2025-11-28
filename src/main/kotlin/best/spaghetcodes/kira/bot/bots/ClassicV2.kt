package best.spaghetcodes.kira.bot.bots

import best.spaghetcodes.kira.bot.BotBase
import best.spaghetcodes.kira.bot.Session
import best.spaghetcodes.kira.bot.features.Bow
import best.spaghetcodes.kira.bot.features.MovePriority
import best.spaghetcodes.kira.bot.features.Rod
import best.spaghetcodes.kira.bot.player.Combat
import best.spaghetcodes.kira.bot.player.Inventory
import best.spaghetcodes.kira.bot.player.Mouse
import best.spaghetcodes.kira.bot.player.Movement
import best.spaghetcodes.kira.bot.tuning.ClassicV2Tuner
import best.spaghetcodes.kira.bot.tuning.MistakeSummary
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.*
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.util.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ClassicV2 : BotBase("/play duels_classic_duel"), Bow, Rod, MovePriority {

    override fun getName(): String = "ClassicV2"

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
    private var fullDrawMsMin = 820
    private var fullDrawMsMax = 980
    private var bowCancelCloseDist = 8.0f
    private var bowMinUseDist = 9.0f
    private var bowAimPitchBias = 0.0f

    private var openVolleyMax = 1
    private var openVolleyFired = 0
    private var openWindowUntil = 0L
    private var openStartDelayUntil = 0L
    private var lastShotAt = 0L
    private var openSpacingMin = 650L
    private var openSpacingMax = 900L
    private var openShotMinDist = 9.0f

    private var stillFrameThreshold = 0.0125
    private var stillFramesNeeded = 10
    private var bowSlowThreshold = 0.06
    private var bowSlowFramesNeeded = 3
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
    private var reactiveCdMs = 650L

    private var gameStartAt = 0L
    private var reserveTightMs = 10_000L
    private var earlyReserve = 3
    private var midReserve = 2
    private var postBowNoRodUntil = 0L

    // =====================  ROD  =====================
    private var lastRodUse = 0L
    private var rodCdCloseMsBase = 340L
    private var rodCdFarMsBase = 480L
    private var rodCdBias = 1.0f
    private var rodCdBiasMax = 1.25f

    private var rodBanMeleeDist = 4.0f

    private var rodCloseMin = 2.0f
    private var rodCloseMax = 3.4f
    private var rodMainMin = 3.0f
    private var rodMainMax = 6.8f
    private var rodInterceptMin = 5.8f
    private var rodInterceptMax = 7.2f
    private var rodMaxRangeHard = 7.2f

    private var rodMidInstantMin = 5.5f
    private var rodMidInstantMax = 7.0f

    private var rodAntiSpamClosePassiveMin = 340
    private var rodAntiSpamClosePassiveMax = 420
    private var rodAntiSpamMidPassiveMin = 520
    private var rodAntiSpamMidPassiveMax = 680
    private var rodAntiSpamFarPassiveMin = 520
    private var rodAntiSpamFarPassiveMax = 700
    private var rodAntiSpamCloseActiveMin = 260
    private var rodAntiSpamCloseActiveMax = 320
    private var rodAntiSpamMidActiveMin = 380
    private var rodAntiSpamMidActiveMax = 520
    private var rodAntiSpamFarActiveMin = 400
    private var rodAntiSpamFarActiveMax = 560
    private var rodAntiSpamUntil = 0L

    private var farSince = 0L
    private var farThreshold = 11.0f
    private var reentryRodGraceMs = 300L
    private var reentryRodGraceUntil = 0L

    private var lastRodAttemptAt = 0L
    private var lastOppHurtTime = 0
    private var pendingRodCheck = false
    private var rodHits = 0
    private var rodMisses = 0

    private var forwardStickMinMs = 220
    private var forwardStickMaxMs = 280
    private var meleeFocusMinMs = 300
    private var meleeFocusMaxMs = 340
    private var meleeFocusUntil = 0L
    private var forwardStickUntil = 0L
    private var lastOppRodSeenAt = 0L
    private var rodHoldCloseMinMs = 118
    private var rodHoldCloseMaxMs = 142
    private var rodHoldMidMinMs = 208
    private var rodHoldMidMaxMs = 232
    private var rodHoldUntil = 0L

    // ==================  PARADE ÉPÉE  =================
    private val parryMinDist = 15.0f
    private var parryCloseCancelDist = 15.0f
    private var parryCooldownMs = 900L
    private var parryHoldMinMs = 650
    private var parryHoldMaxMs = 980
    private var parryStickMinMs = 900
    private var parryStickMaxMs = 1500
    private var lastSwordBlock = 0L
    private var holdBlockUntil = 0L
    private var parryFromBow = false
    private var parryExtendedUntil = 0L
    private var parryCloseLockUntil = 0L
    private var parryStrafeDir = 1
    private var parryStrafeFlipAt = 0L
    private var lastParryJumpAt = 0L
    private var parryJumpCd = 580L
    private var allowParryDelayMs = 2800L
    private var allowParryAfter = 0L

    // ========= UTILITAIRES =========
    private fun isUsingItemSafe(p: EntityPlayer?): Boolean {
        return p?.let { it.isUsingItem || it.itemInUseCount > 0 } == true
    }

    // ==================  MOUVEMENT  ===================
    private var strafeDir = 1
    private var lastStrafeSwitch = 0L
    private var prevDistance = -1f
    private var lastTacticalJumpAt = 0L
    private var lastGotHitAt = 0L
    private var tapping = false

    private var strafeBiasDir = 0
    private var strafeBiasStickUntil = 0L

    private var startupJumping = false
    private var humanSwingSeriesDone = false
    private var humanSwingSeriesActiveUntil = 0L
    private var wasInHumanZone = false
    private val humanSwingZoneMin = 14.0f
    private val humanSwingZoneMax = 20.0f

    private var closeStrafeMode = 0
    private val MODE_BURST = 0
    private val MODE_HOLD_LEFT = 1
    private val MODE_HOLD_RIGHT = 2
    private var closeBurstWindowMinMs = 280
    private var closeBurstWindowMaxMs = 420
    private var closeBurstFlipMinMs = 60
    private var closeBurstFlipMaxMs = 110
    private var closeHoldWindowMinMs = 220
    private var closeHoldWindowMaxMs = 340
    private var closeStrafeNextAt = 0L
    private var closeStrafeToggleAt = 0L

    // ===== Jump (anti-jump zone + cadence) =====
    private var antiJumpZoneDist = 8.0f
    private var startupJumpDelayMs = 300
    private var continuousJumpMinIntervalMs = 220
    private var continuousJumping = false
    private var lastJumpAt = 0L
    
    // Logique anti-jump intelligente
    private var hasReachedCombatZone = false           // Flag : on a atteint la zone de combat
    private var lastTimeLeftCombatZone = 0L            // Timestamp quand on sort de la zone
    private val combatZoneExitDelayMs = 3000L          // Délai de 3 secondes avant de pouvoir sauter

    // ========= SAFE START GATE (anti-crash ouverture) =========
    private var startGateActive = false
    private var startGateReady = false
    private var startGateOkTicks = 0
    private var startGateStartAt = 0L
    private val START_TIMEOUT_MS = 1500L
    private val START_SETTLE_TICKS = 3
    private val START_GROUND_TICKS = 2

    private fun scoreboardLooksReady(): Boolean {
        val w = mc.theWorld ?: return false
        val sb = w.scoreboard ?: return false
        return sb.getObjectiveInDisplaySlot(1) != null || sb.getObjectiveInDisplaySlot(0) != null
    }

    private fun tryAdvanceStartGate(now: Long) {
        if (!startGateActive || startGateReady) return
        if (now - startGateStartAt > START_TIMEOUT_MS) {
            startGateReady = true
            startGateActive = false
            performSafeStart(now, soft = true)
            return
        }
        val sbOk = scoreboardLooksReady()
        val p = mc.thePlayer
        val onGroundOk = p != null && p.onGround
        if (sbOk && onGroundOk) {
            startGateOkTicks++
            if (startGateOkTicks >= (START_SETTLE_TICKS + START_GROUND_TICKS)) {
                startGateReady = true
                startGateActive = false
                performSafeStart(now, soft = false)
            }
        } else {
            startGateOkTicks = 0
        }
    }

    // ⬇⬇⬇ FIX: on ne type plus le paramètre avec EntityPlayerSP
    private fun isBowAiming(now: Long): Boolean {
        val p = mc.thePlayer ?: return false
        val holdingBowNow = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("bow")
        return (projectileKind == KIND_BOW) ||
               (holdingBowNow && Mouse.rClickDown) ||
               (projectileKind == KIND_BOW && now < bowHardLockUntil)
    }

    private fun performSafeStart(now: Long, soft: Boolean) {
        Mouse.startTracking()
        if (soft) {
            // When we time out waiting for the scoreboard we ease into the start instead of hard sprinting.
            Movement.stopSprinting()
            Movement.startForward()
        } else {
            Movement.startSprinting()
            Movement.startForward()
        }
        Mouse.rClickUp()

        if (kira.config?.kiraHit == true) Mouse.startLeftAC() else Mouse.stopLeftAC()

        startupJumping = true
        continuousJumping = false
        hasReachedCombatZone = false
        lastTimeLeftCombatZone = 0L
        lastJumpAt = 0L
        gameStartAt = now

        val delay = startupJumpDelayMs
        TimeUtils.setTimeout({
            val p = mc.thePlayer ?: return@setTimeout
            val opp = opponent() ?: return@setTimeout
            val dist = EntityUtils.getDistanceNoY(p, opp)
            val aiming = isBowAiming(System.currentTimeMillis())
            if (!aiming && dist > antiJumpZoneDist && (System.currentTimeMillis() - lastJumpAt >= continuousJumpMinIntervalMs)) {
                recordCloseJump(dist, aiming)
                Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                lastJumpAt = System.currentTimeMillis()
            }
        }, delay)
    }

    // ====================  LIFECYCLE  ==================
    override fun onGameStart() {
        val params = if (kira.isTunerEnabled) {
            try {
                ClassicV2Tuner.pickParams()
            } catch (_: Throwable) {
                ClassicV2Tuner.defaults()
            }
        } else {
            ClassicV2Tuner.defaults()
        }
        applyParams(params)

        Mouse.stopLeftAC()
        Mouse.rClickUp()
        Movement.clearAll()
        startupJumping = false
        continuousJumping = false
        lastJumpAt = 0L

        openVolleyFired = 0
        openWindowUntil = System.currentTimeMillis() + 4500L
        openStartDelayUntil = System.currentTimeMillis() + RandomUtils.randomIntInRange(700, 1100)
        lastShotAt = 0L

        oppLastX = 0.0; oppLastZ = 0.0
        stillFrames = 0; bowSlowFrames = 0
        lastOppHurtTime = 0
        lastReactiveShotAt = 0L
        postBowNoRodUntil = 0L

        lastRodUse = 0L
        rodCdBias = 1.0f
        rodHits = 0; rodMisses = 0
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
        strafeBiasDir = 0
        strafeBiasStickUntil = 0L

        humanSwingSeriesDone = false
        humanSwingSeriesActiveUntil = 0L
        wasInHumanZone = false

        closeStrafeMode = MODE_BURST
        closeStrafeNextAt = 0L
        closeStrafeToggleAt = 0L

        allowParryAfter = 0L

        startGateActive = true
        startGateReady = false
        startGateOkTicks = 0
        startGateStartAt = System.currentTimeMillis()
    }

    override fun onGameEnd() {
        Mouse.stopLeftAC()
        val i = TimeUtils.setInterval(Mouse::stopLeftAC, 100, 100)
        val win = when {
            Session.wins > Session.losses -> true
            Session.losses > Session.wins -> false
            else -> false
        }
        val mistakes = if (kira.isTunerEnabled) ClassicV2Tuner.takeAndResetMistakes(rodHits, rodMisses, shotsFired) else MistakeSummary.ZERO
        if (kira.isTunerEnabled) {
            ClassicV2Tuner.report(win, mistakes)
        }
        TimeUtils.setTimeout({
            i?.cancel()
            Mouse.stopTracking()
            Movement.clearAll()
            Combat.stopRandomStrafe()
        }, RandomUtils.randomIntInRange(200, 400))
        startupJumping = false
        continuousJumping = false
        hasReachedCombatZone = false
        lastTimeLeftCombatZone = 0L
        lastJumpAt = 0L
        startGateActive = false
        startGateReady = false
    }

    // ========= Injection des params =========
    private fun applyParams(p: ClassicV2Tuner.ClassicParams) {
        fullDrawMsMin = p.fullDrawMsMin
        fullDrawMsMax = p.fullDrawMsMax
        bowCancelCloseDist = p.bowCancelCloseDist
        bowMinUseDist = p.bowMinUseDist
        openVolleyMax = p.openVolleyMax
        openSpacingMin = p.openSpacingMin
        openSpacingMax = p.openSpacingMax
        openShotMinDist = p.openShotMinDist
        reactiveCdMs = p.reactiveCdMs

        stillFrameThreshold = p.stillFrameThreshold
        stillFramesNeeded = p.stillFramesNeeded
        bowSlowThreshold = p.bowSlowThreshold
        bowSlowFramesNeeded = p.bowSlowFramesNeeded

        reserveTightMs = p.reserveTightMs
        earlyReserve = p.earlyReserve
        midReserve = p.midReserve

        rodCdCloseMsBase = p.rodCdCloseMsBase
        rodCdFarMsBase = p.rodCdFarMsBase
        rodCdBiasMax = p.rodCdBiasMax
        rodBanMeleeDist = p.rodBanMeleeDist
        rodCloseMin = p.rodCloseMin
        rodCloseMax = p.rodCloseMax
        rodMainMin = p.rodMainMin
        rodMainMax = p.rodMainMax
        rodInterceptMin = p.rodInterceptMin
        rodInterceptMax = p.rodInterceptMax
        rodMaxRangeHard = p.rodMaxRangeHard
        rodMidInstantMin = p.rodMidInstantMin
        rodMidInstantMax = p.rodMidInstantMax
        farThreshold = p.farThreshold
        reentryRodGraceMs = p.reentryRodGraceMs

        rodHoldCloseMinMs = p.rodHoldCloseMinMs
        rodHoldCloseMaxMs = p.rodHoldCloseMaxMs
        rodHoldMidMinMs = p.rodHoldMidMinMs
        rodHoldMidMaxMs = p.rodHoldMidMaxMs

        rodAntiSpamClosePassiveMin = p.rodAntiSpamClosePassiveMin
        rodAntiSpamClosePassiveMax = p.rodAntiSpamClosePassiveMax
        rodAntiSpamMidPassiveMin = p.rodAntiSpamMidPassiveMin
        rodAntiSpamMidPassiveMax = p.rodAntiSpamMidPassiveMax
        rodAntiSpamFarPassiveMin = p.rodAntiSpamFarPassiveMin
        rodAntiSpamFarPassiveMax = p.rodAntiSpamFarPassiveMax

        rodAntiSpamCloseActiveMin = p.rodAntiSpamCloseActiveMin
        rodAntiSpamCloseActiveMax = p.rodAntiSpamCloseActiveMax
        rodAntiSpamMidActiveMin = p.rodAntiSpamMidActiveMin
        rodAntiSpamMidActiveMax = p.rodAntiSpamMidActiveMax
        rodAntiSpamFarActiveMin = p.rodAntiSpamFarActiveMin
        rodAntiSpamFarActiveMax = p.rodAntiSpamFarActiveMax

        parryCloseCancelDist = p.parryCloseCancelDist
        parryCooldownMs = p.parryCooldownMs
        parryHoldMinMs = p.parryHoldMinMs
        parryHoldMaxMs = p.parryHoldMaxMs
        parryStickMinMs = p.parryStickMinMs
        parryStickMaxMs = p.parryStickMaxMs
        parryJumpCd = p.parryJumpCd
        allowParryDelayMs = p.allowParryDelayMs

        closeBurstWindowMinMs = p.closeBurstWindowMinMs
        closeBurstWindowMaxMs = p.closeBurstWindowMaxMs
        closeBurstFlipMinMs = p.closeBurstFlipMinMs
        closeBurstFlipMaxMs = p.closeBurstFlipMaxMs
        closeHoldWindowMinMs = p.closeHoldWindowMinMs
        closeHoldWindowMaxMs = p.closeHoldWindowMaxMs

        forwardStickMinMs = p.forwardStickMinMs
        forwardStickMaxMs = p.forwardStickMaxMs
        meleeFocusMinMs = p.meleeFocusMinMs
        meleeFocusMaxMs = p.meleeFocusMaxMs

        antiJumpZoneDist = p.antiJumpZoneDist
        startupJumpDelayMs = p.startupJumpDelayMs
        continuousJumpMinIntervalMs = p.continuousJumpMinIntervalMs

        bowAimPitchBias = p.bowAimPitchBias
        Mouse.setBowPitchBias(bowAimPitchBias)
    }

    override fun onAttack() {
        Combat.wTap(100)
        tapping = true
        TimeUtils.setTimeout({ tapping = false }, 100)
        val now = System.currentTimeMillis()
        forwardStickUntil = now + RandomUtils.randomIntInRange(forwardStickMinMs, forwardStickMaxMs)
        meleeFocusUntil = now + RandomUtils.randomIntInRange(meleeFocusMinMs, meleeFocusMaxMs)
        TimeUtils.setTimeout({
            Movement.startForward()
            Movement.startSprinting()
        }, 80)
        if (combo >= 3) Movement.clearLeftRight()
    }

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
    private fun reserveNeeded(now: Long): Int = if (now - gameStartAt < reserveTightMs) earlyReserve else midReserve

    private fun triggerHumanSwingSeries() {
        if (humanSwingSeriesDone) return
        humanSwingSeriesDone = true
        val swings = RandomUtils.randomIntInRange(2, 4)
        var delay = 0
        repeat(swings) {
            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                mc.thePlayer?.swingItem()
            }, delay)
            delay += RandomUtils.randomIntInRange(110, 170)
        }
        humanSwingSeriesActiveUntil = System.currentTimeMillis() + delay + 60
    }

    private fun castRodNow(distanceNow: Float) {
        fun doClick() {
            val nowClick = System.currentTimeMillis()

            if (kira.isTunerEnabled) {
                val distanceMargin = 0.2f
                if (distanceNow < rodBanMeleeDist - distanceMargin || distanceNow > rodMaxRangeHard + distanceMargin) {
                    ClassicV2Tuner.noteRodMistake()
                }

                val cdClose = (rodCdCloseMsBase * rodCdBias).toLong()
                val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
                val requiredCd = if (distanceNow <= rodCloseMax) cdClose else cdFar
                val cdReady = (nowClick - lastRodUse) >= requiredCd || nowClick < reentryRodGraceUntil
                if (!cdReady) {
                    ClassicV2Tuner.noteRodMistake()
                }
            }

            Mouse.setUsingProjectile(true)
            if (Mouse.rClickDown) Mouse.rClickUp()
            Mouse.rClick(RandomUtils.randomIntInRange(70, 95))
            reentryRodGraceUntil = 0L

            val holdMs = when {
                distanceNow < 3.0f  -> RandomUtils.randomIntInRange(rodHoldCloseMinMs, rodHoldCloseMaxMs)
                distanceNow < 4.8f  -> RandomUtils.randomIntInRange(160, 190)
                distanceNow <= 6.2f -> RandomUtils.randomIntInRange(rodHoldMidMinMs, rodHoldMidMaxMs)
                else                -> RandomUtils.randomIntInRange(210, 235)
            }
            rodHoldUntil = nowClick + holdMs

            val settle = RandomUtils.randomIntInRange(200, 260)
            pendingProjectileUntil = nowClick + 80L
            actionLockUntil = nowClick + settle + 80
            projectileGraceUntil = actionLockUntil

            lastRodAttemptAt = nowClick
            pendingRodCheck = true
            lastOppHurtTime = opponent()?.hurtTime ?: 0

            TimeUtils.setTimeout({
                Inventory.setInvItem("sword")
                Mouse.setUsingProjectile(false)
            }, max(holdMs + 20, settle))

            lastRodUse = nowClick

            val oppPassive = (nowClick - lastOppRodSeenAt) > 5000L
            val antiSpam = when {
                distanceNow < 3.0f -> if (oppPassive)
                    RandomUtils.randomIntInRange(rodAntiSpamClosePassiveMin, rodAntiSpamClosePassiveMax)
                else
                    RandomUtils.randomIntInRange(rodAntiSpamCloseActiveMin, rodAntiSpamCloseActiveMax)
                distanceNow <= 6.2f -> if (oppPassive)
                    RandomUtils.randomIntInRange(rodAntiSpamMidPassiveMin, rodAntiSpamMidPassiveMax)
                else
                    RandomUtils.randomIntInRange(rodAntiSpamMidActiveMin, rodAntiSpamMidActiveMax)
                else -> if (oppPassive)
                    RandomUtils.randomIntInRange(rodAntiSpamFarPassiveMin, rodAntiSpamFarPassiveMax)
                else
                    RandomUtils.randomIntInRange(rodAntiSpamFarActiveMin, rodAntiSpamFarActiveMax)
            }
            rodAntiSpamUntil = nowClick + antiSpam
            meleeFocusUntil = max(meleeFocusUntil, nowClick + RandomUtils.randomIntInRange(240, 360))
        }

        if (Mouse.rClickDown && projectileKind == KIND_BOW) {
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        val held = mc.thePlayer?.heldItem?.unlocalizedName?.lowercase()
        if (held == null || !held.contains("rod")) {
            Inventory.setInvItem("rod")
        }
        doClick()
    }

    private fun updateRodAccuracyHeuristic(now: Long) {
        if (!pendingRodCheck) return
        val opp = opponent() ?: return
        val dt = now - lastRodAttemptAt
        if (dt in 80..420) {
            val ht = opp.hurtTime
            if (ht > 0 && ht != lastOppHurtTime) {
                rodHits++; pendingRodCheck = false
                rodCdBias = max(0.85f, rodCdBias * 0.92f)
            }
        } else if (dt > 480) {
            rodMisses++; pendingRodCheck = false
            if (rodMisses - rodHits >= 2) rodCdBias = min(rodCdBiasMax, rodCdBias * 1.10f)
        }
    }

    private fun opponentLikelyUsingRod(opp: net.minecraft.entity.EntityLivingBase): Boolean {
        val held = opp.heldItem
        return held != null && held.unlocalizedName.lowercase().contains("rod")
    }

    private fun maintainContinuousJump(now: Long, distanceToOpp: Float) {
        if (!startGateReady) return
        val aiming = isBowAiming(now)
        if (aiming) {
            continuousJumping = false
            Movement.stopJumping()
            return
        }
        
        // Tracker si on a atteint la zone de combat (≤8 blocks)
        if (distanceToOpp <= antiJumpZoneDist) {
            hasReachedCombatZone = true
            lastTimeLeftCombatZone = 0L  // Reset le timer
        }
        
        // Si on est hors zone ET qu'on a déjà été en combat
        if (hasReachedCombatZone && distanceToOpp > antiJumpZoneDist) {
            if (lastTimeLeftCombatZone == 0L) {
                lastTimeLeftCombatZone = now  // Démarrer le timer
            }
        }
        
        // Logique de saut intelligente
        val shouldJump: Boolean = if (!hasReachedCombatZone) {
            // DÉBUT DE PARTIE : Sauter continuellement si > antiJumpZoneDist
            distanceToOpp > antiJumpZoneDist
        } else {
            // MILIEU DE PARTIE : Seulement si > antiJumpZoneDist depuis 3+ secondes
            distanceToOpp > antiJumpZoneDist && 
            lastTimeLeftCombatZone != 0L && 
            (now - lastTimeLeftCombatZone) >= combatZoneExitDelayMs
        }
        
        continuousJumping = shouldJump
        if (shouldJump) {
            Movement.startForward()
            Movement.startSprinting()
            Movement.startJumping()
        } else {
            Movement.stopJumping()
        }
    }

    override fun onTick() {
        val now = System.currentTimeMillis()

        tryAdvanceStartGate(now)
        if (!startGateReady) return

        val p = mc.thePlayer ?: return
        val opp = opponent() ?: return

        if (!p.isSprinting) Movement.startSprinting()
        Mouse.startTracking()
        if (kira.config?.kiraHit == true) Mouse.startLeftAC() else Mouse.stopLeftAC()

        val hbActive = now < hbActiveUntil
        val distance = EntityUtils.getDistanceNoY(p, opp)

        maintainContinuousJump(now, distance)

        val approaching = (prevDistance > 0f) && (prevDistance - distance >= 0.15f)

        if (distance > farThreshold) {
            if (farSince == 0L) farSince = now
        } else {
            if (farSince != 0L && (now - farSince) >= 500L && approaching) {
                reentryRodGraceUntil = now + reentryRodGraceMs
            }
            farSince = 0L
        }

        val inHumanZone = distance in humanSwingZoneMin..humanSwingZoneMax
        if (!humanSwingSeriesDone && inHumanZone && !wasInHumanZone && now >= humanSwingSeriesActiveUntil) {
            triggerHumanSwingSeries()
        }
        wasInHumanZone = inHumanZone

        if (opponentLikelyUsingRod(opp)) lastOppRodSeenAt = now

        if (oppLastX == 0.0 && oppLastZ == 0.0) { oppLastX = opp.posX; oppLastZ = opp.posZ }
        val dx = abs(opp.posX - oppLastX)
        val dz = abs(opp.posZ - oppLastZ)
        if (dx < stillFrameThreshold && dz < stillFrameThreshold) stillFrames++ else stillFrames = 0
        val frameSpeed = dx + dz
        if (frameSpeed < bowSlowThreshold) bowSlowFrames++ else bowSlowFrames = 0
        oppLastX = opp.posX; oppLastZ = opp.posZ

        if (p.hurtTime > 0) lastGotHitAt = now

        val aiming = isBowAiming(now)
        if (!aiming &&
            distance > max(antiJumpZoneDist, 2.2f) &&
            WorldUtils.blockInFront(p, 2f, 0.5f) != Blocks.air &&
            p.onGround &&
            now - lastJumpAt >= continuousJumpMinIntervalMs) {
            recordCloseJump(distance, aiming)
            Movement.singleJump(RandomUtils.randomIntInRange(150, 240))
            lastJumpAt = now
            lastTacticalJumpAt = now
        }

        if (prevDistance > 0f && distance - prevDistance > 0.6f) {
            forwardStickUntil = max(forwardStickUntil, now + 200)
        }

        val projectileActive =
            Mouse.isUsingProjectile() || now < projectileGraceUntil || now < pendingProjectileUntil || now < actionLockUntil

        if (now < forwardStickUntil) {
            Movement.startForward()
        } else {
            if (distance < 0.75f || (distance < 2.4f && combo >= 2 && approaching)) {
                Movement.stopForward()
            } else {
                Movement.startForward()
            }
        }

        if (distance < 1.5f &&
            p.heldItem != null &&
            !p.heldItem.unlocalizedName.lowercase().contains("sword") &&
            !projectileActive &&
            now >= rodHoldUntil) {
            Inventory.setInvItem("sword")
        }

        if (projectileActive && Mouse.rClickDown && projectileKind == KIND_BOW && distance < bowCancelCloseDist) {
            if (kira.isTunerEnabled) {
                ClassicV2Tuner.noteBowMistake()
            }
            Mouse.rClickUp()
            bowHardLockUntil = 0L
            projectileGraceUntil = 0L
            pendingProjectileUntil = 0L
            actionLockUntil = 0L
            projectileKind = KIND_NONE
        }

        updateRodAccuracyHeuristic(now)

        val holdingSword = p.heldItem != null && p.heldItem.unlocalizedName.lowercase().contains("sword")
        val isStillNow = stillFrames >= stillFramesNeeded
        val oppHasBowNow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val oppBowDrawn = oppHasBowNow && isUsingItemSafe(opp)
        val bowLikely = oppBowDrawn || (oppHasBowNow && (isStillNow || bowSlowFrames >= bowSlowFramesNeeded))

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
                if (distance >= parryCloseCancelDist &&
                    p.onGround &&
                    now - lastParryJumpAt >= parryJumpCd &&
                    !projectileActive &&
                    now - lastGotHitAt > 260 &&
                    now - lastJumpAt >= continuousJumpMinIntervalMs) {

                    if (RandomUtils.randomIntInRange(0, 1) == 1 || now >= parryStrafeFlipAt) {
                        parryStrafeDir = -parryStrafeDir
                        parryStrafeFlipAt = now + RandomUtils.randomIntInRange(260, 420)
                    }
                    recordCloseJump(distance, aiming)
                    Movement.singleJump(RandomUtils.randomIntInRange(140, 210))
                    lastParryJumpAt = now
                    lastJumpAt = now
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

        if (!Mouse.rClickDown && !projectileActive && (now - lastGotHitAt) > 260 && !continuousJumping) {
            val facingAway = EntityUtils.entityFacingAway(p, opp)
            val oppVeryStill = (stillFrames >= 6)
            val farJumpThreshold = antiJumpZoneDist + 2.0f
            if (distance >= farJumpThreshold) {
                if (p.onGround && now - lastTacticalJumpAt >= 520 && now - lastJumpAt >= continuousJumpMinIntervalMs) {
                    recordCloseJump(distance, aiming)
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                    lastJumpAt = now
                    lastTacticalJumpAt = now
                }
            } else if (distance > antiJumpZoneDist && distance < farJumpThreshold && (facingAway || oppVeryStill)) {
                if (p.onGround && now - lastTacticalJumpAt >= 720 && now - lastJumpAt >= continuousJumpMinIntervalMs) {
                    recordCloseJump(distance, aiming)
                    Movement.singleJump(RandomUtils.randomIntInRange(150, 230))
                    lastJumpAt = now
                    lastTacticalJumpAt = now
                }
            }
        }

        val oppHasBow = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
        val bowLikelyNowClose = (oppBowDrawn || (oppHasBow && (isStillNow || bowSlowFrames >= bowSlowFramesNeeded))) && distance <= 10.0f
        val oppRodRecently = (now - lastOppRodSeenAt) <= 2500L
        val allowByAntiSpam = now >= rodAntiSpamUntil || now < reentryRodGraceUntil || oppRodRecently

        if ((!projectileActive || now < reentryRodGraceUntil) &&
            !Mouse.isRunningAway() &&
            !Mouse.isUsingPotion() &&
            (!Mouse.rClickDown || hbActive || now < reentryRodGraceUntil)) {

            if (distance <= rodBanMeleeDist) {
                // ban rod en mêlée
            } else {
                if (isStillNow && distance in rodMidInstantMin..rodMidInstantMax && allowByAntiSpam) {
                    castRodNow(distance)
                    rodAntiSpamUntil = now + RandomUtils.randomIntInRange(300, 380)
                    prevDistance = distance
                    return
                }

                if (bowLikelyNowClose && distance <= rodMaxRangeHard) {
                    castRodNow(distance)
                    prevDistance = distance
                    postBowNoRodUntil = now + 320
                    return
                }

                if (distance <= rodMaxRangeHard) {
                    if (distance in rodMidInstantMin..rodMidInstantMax && !projectileActive && allowByAntiSpam) {
                        castRodNow(distance); prevDistance = distance; return
                    }

                    val cdClose = (rodCdCloseMsBase * rodCdBias).toLong()
                    val cdFar = (rodCdFarMsBase * rodCdBias).toLong()
                    val cdCloseOK = (now - lastRodUse) >= cdClose || now < reentryRodGraceUntil
                    val cdFarOK = (now - lastRodUse) >= cdFar || now < reentryRodGraceUntil
                    val facingAway = EntityUtils.entityFacingAway(p, opp)

                    val meleeRange = distance < 3.1f
                    val allowRodByMeleePolicy = !(meleeRange && !oppRodRecently && now < meleeFocusUntil)

                    if (allowRodByMeleePolicy &&
                        distance in rodCloseMin..rodCloseMax &&
                        distance > rodBanMeleeDist &&
                        (p.hurtTime > 0 || approaching) &&
                        !facingAway &&
                        cdCloseOK &&
                        allowByAntiSpam) {
                        castRodNow(distance); prevDistance = distance; return
                    }

                    if (allowRodByMeleePolicy && !facingAway && (cdFarOK || cdCloseOK) && allowByAntiSpam) {
                        if (oppRodRecently && distance > rodBanMeleeDist) {
                            castRodNow(distance); prevDistance = distance; return
                        }
                        if (distance in rodMainMin..rodMainMax && distance > rodBanMeleeDist) {
                            castRodNow(distance); prevDistance = distance; return
                        }
                    }

                    if (allowRodByMeleePolicy &&
                        distance in rodInterceptMin..rodInterceptMax &&
                        !facingAway &&
                        (cdFarOK || cdCloseOK) &&
                        allowByAntiSpam) {
                        castRodNow(distance); prevDistance = distance; return
                    }
                }
            }
        }

        if (!projectileActive && !Mouse.isRunningAway() && !Mouse.isUsingPotion() && !Mouse.rClickDown) {
            val reserve = reserveNeeded(now)
            val left = arrowsLeft()
            val denyBowMidByStill = isStillNow && distance in rodMidInstantMin..rodMidInstantMax
            val denyBowCloseByImmobilen = bowLikelyNowClose

            if (!denyBowCloseByImmobilen &&
                !denyBowMidByStill &&
                shotsFired < maxArrows &&
                openVolleyFired < openVolleyMax &&
                now < openWindowUntil &&
                now >= openStartDelayUntil &&
                distance >= max(openShotMinDist, bowMinUseDist) &&
                left > reserve &&
                (now - lastShotAt) >= RandomUtils.randomIntInRange(openSpacingMin.toInt(), openSpacingMax.toInt())) {

                val lock = chargeMsFor(distance, opening = true)

                if (kira.isTunerEnabled && distance < (bowMinUseDist - 1f)) {
                    ClassicV2Tuner.noteBowMistake()
                }

                Movement.stopJumping()
                continuousJumping = false

                startupJumping = false
                bowHardLockUntil = now + lock
                pendingProjectileUntil = now + 60L
                actionLockUntil = now + (lock + 120)
                projectileKind = KIND_BOW
                useBowImmediateFull {
                    shotsFired++; openVolleyFired++; lastShotAt = System.currentTimeMillis()
                }
                projectileGraceUntil = bowHardLockUntil + 120
                postBowNoRodUntil = now + lock + 380L
                prevDistance = distance
                return
            }

            val oppHasBowReact = opp.heldItem != null && opp.heldItem.unlocalizedName.lowercase().contains("bow")
            val bowLikelyReact = (oppBowDrawn || (oppHasBowReact && (stillFrames >= stillFramesNeeded || bowSlowFrames >= bowSlowFramesNeeded)))
            if (!denyBowCloseByImmobilen &&
                !denyBowMidByStill &&
                shotsFired < maxArrows &&
                bowLikelyReact &&
                distance >= max(bowMinUseDist, 12.0f) &&
                now - lastReactiveShotAt >= reactiveCdMs &&
                WorldUtils.blockInFront(p, distance, 0.5f) == Blocks.air &&
                left > reserve) {

                val lock = chargeMsFor(distance, opening = false)

                if (kira.isTunerEnabled && distance < (bowMinUseDist - 1f)) {
                    ClassicV2Tuner.noteBowMistake()
                }

                Movement.stopJumping()
                continuousJumping = false

                startupJumping = false
                bowHardLockUntil = now + lock
                pendingProjectileUntil = now + 50L
                actionLockUntil = now + (lock + 100)
                projectileKind = KIND_BOW
                useBowImmediateFull { shotsFired++; lastReactiveShotAt = System.currentTimeMillis() }
                projectileGraceUntil = bowHardLockUntil + 100
                postBowNoRodUntil = now + lock + 320L
                prevDistance = distance
                return
            }

            if (!denyBowCloseByImmobilen && !denyBowMidByStill && shotsFired < maxArrows && left > reserve && distance >= bowMinUseDist) {
                val away = EntityUtils.entityFacingAway(p, opp)
                if ((away && distance in 3.5f..30f) || (!away && distance in 28.0f..33.0f)) {

                    val lock = chargeMsFor(distance, opening = false)

                    if (kira.isTunerEnabled && distance < (bowMinUseDist - 1f)) {
                        ClassicV2Tuner.noteBowMistake()
                    }

                    Movement.stopJumping()
                    continuousJumping = false

                    startupJumping = false
                    bowHardLockUntil = now + lock
                    pendingProjectileUntil = now + 60L
                    actionLockUntil = now + (lock + 120)
                    projectileKind = KIND_BOW
                    useBowImmediateFull { shotsFired++ }
                    projectileGraceUntil = bowHardLockUntil + 120
                    postBowNoRodUntil = now + lock + 320L
                    prevDistance = distance
                    return
                }
            }
        }

        val movePriority = arrayListOf(0, 0)
        var clear = false
        var randomStrafe = false

        val parryActive = Mouse.rClickDown && (now < holdBlockUntil || (parryFromBow && now < parryExtendedUntil))
        if (parryActive) {
            val w = if (distance > 6f) 7 else 9
            if (parryStrafeDir < 0) movePriority[0] += w else movePriority[1] += w
            randomStrafe = false
        }

        if (!parryActive && now < strafeBiasStickUntil && strafeBiasDir != 0) {
            val w = if (distance > 6f) 6 else 7
            if (strafeBiasDir < 0) movePriority[0] += w else movePriority[1] += w
        }

        if (EntityUtils.entityFacingAway(p, opp)) {
            if (WorldUtils.leftOrRightToPoint(p, Vec3(0.0, 0.0, 0.0))) movePriority[0] += 4 else movePriority[1] += 4
        } else if (!parryActive) {

            if (distance < 2.6f) {
                if (now >= closeStrafeNextAt) {
                    val roll = RandomUtils.randomIntInRange(0, 99)
                    closeStrafeMode = when {
                        roll < 50 -> MODE_BURST
                        roll < 75 -> MODE_HOLD_LEFT
                        else     -> MODE_HOLD_RIGHT
                    }
                    closeStrafeNextAt = now + when (closeStrafeMode) {
                        MODE_BURST -> RandomUtils.randomIntInRange(closeBurstWindowMinMs, closeBurstWindowMaxMs).toLong()
                        else       -> RandomUtils.randomIntInRange(closeHoldWindowMinMs, closeHoldWindowMaxMs).toLong()
                    }
                    if (closeStrafeMode == MODE_BURST) {
                        closeStrafeToggleAt = now + RandomUtils.randomIntInRange(closeBurstFlipMinMs, closeBurstFlipMaxMs)
                    } else {
                        strafeDir = if (closeStrafeMode == MODE_HOLD_LEFT) -1 else 1
                    }
                } else if (closeStrafeMode == MODE_BURST && now >= closeStrafeToggleAt) {
                    strafeDir = -strafeDir
                    closeStrafeToggleAt = now + RandomUtils.randomIntInRange(closeBurstFlipMinMs, closeBurstFlipMaxMs)
                }

                val weightClose = 4
                if (strafeDir < 0) movePriority[0] += weightClose else movePriority[1] += weightClose
                Movement.startForward()
                Movement.startSprinting()
                randomStrafe = false
            } else {
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

        if (allowParryAfter == 0L) allowParryAfter = gameStartAt + allowParryDelayMs
    }

    private fun recordCloseJump(distance: Float, aiming: Boolean) {
        if (kira.isTunerEnabled) {
            ClassicV2Tuner.noteCloseJump(distance, aiming)
        }
    }
}
