package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.core.Config
import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

// FAST_FORWARD : demi-cercle lissé (humain) toutes les 7s, easing quintic (plus doux)
// SUMO : 45° -> 1er saut sans rotation -> dès le 2e saut rotation à chaque saut (lissée en vol, easing sine)

object LobbyMovement {

    // === commun ===
    private var tickYawChange = 0f
    private var desiredPitch: Float? = null
    private var intervals: ArrayList<Timer?> = ArrayList()
    private var activeMovementType: Config.LobbyMovementType? = null

    private fun canActivateAndRunAnyMovement(): Boolean {
        return kira.mc.thePlayer != null &&
                kira.bot?.toggled() == true &&
                kira.config?.lobbyMovement == true
    }

    fun startMovement(typeToStart: Config.LobbyMovementType) {
        if (!canActivateAndRunAnyMovement()) {
            if (activeMovementType != null) stop()
            return
        }
        if (activeMovementType == typeToStart) {
            // bonus pour FAST_FORWARD : s’assurer que ça saute si on est au sol
            if (typeToStart == Config.LobbyMovementType.FAST_FORWARD) {
                if (kira.mc.thePlayer?.onGround == true && !Movement.jumping()) {
                    Movement.startJumping()
                }
            }
            return
        }
        stop()
        activeMovementType = typeToStart
        internalStartMovementLogic(activeMovementType!!)
    }

    private fun internalStartMovementLogic(type: Config.LobbyMovementType) {
        when (type) {
            Config.LobbyMovementType.FAST_FORWARD -> runForwardPreGameInternal()
            Config.LobbyMovementType.SUMO -> sumoInternal()
        }
    }

    // =======================
    // FAST_FORWARD (demi-cercle lissé)
    // =======================
    private const val FF_ARC_JITTER_DEG = 6f             // léger aléa sur l’angle total (±)
    // Durée plus longue pour un pivot plus doux
    private const val FF_ARC_DURATION_MIN_TICKS = 26     // ~1.3s si 20 TPS
    private const val FF_ARC_DURATION_MAX_TICKS = 40     // ~2.0s
    private const val FF_STRAFE_ENABLE = true
    private const val FF_STRAFE_PORTION_MIN = 55         // % de la durée d’arc passée à strafe
    private const val FF_STRAFE_PORTION_MAX = 75

    // état FAST_FORWARD
    private var ffTurnRight = true                       // on alterne le côté à chaque arc
    private var ffStrafeTicksLeft = 0
    private var ffStrafeRight = true

    private fun runForwardPreGameInternal() {
        Movement.startForward()
        Movement.startSprinting()
        Movement.startJumping()
        tickYawChange = 0f
        desiredPitch = 0f

        // Remplace l’ancien “snap 180°” par un DEMI-CERCLE lissé, même intervalle 7000/7000
        intervals.add(TimeUtils.setInterval(fun() {
            val p = kira.mc.thePlayer ?: return@setInterval
            p.rotationPitch = 0f

            // alterner droite/gauche
            ffTurnRight = !ffTurnRight

            // angle total ~ ±180° + petit jitter
            val total = (if (ffTurnRight) 180f else -180f) +
                    RandomUtils.randomDoubleInRange(-FF_ARC_JITTER_DEG.toDouble(), FF_ARC_JITTER_DEG.toDouble()).toFloat()

            // durée lissée (ticks)
            val dur = RandomUtils.randomIntInRange(FF_ARC_DURATION_MIN_TICKS, FF_ARC_DURATION_MAX_TICKS)

            // lancer la rotation lissée avec EASING "quintic" (plus doux que le sine)
            startYawPlan(total, dur, YawEase.QUINT)

            // tap-strafe léger pour donner un vrai rayon à l’arc
            if (FF_STRAFE_ENABLE) {
                val portion = RandomUtils.randomIntInRange(FF_STRAFE_PORTION_MIN, FF_STRAFE_PORTION_MAX)
                ffStrafeTicksLeft = (dur * portion) / 100
                ffStrafeRight = ffTurnRight
            }
        }, 7000, 7000))  // mêmes valeurs qu'avant : initialDelay=7000ms, period=7000ms
    }

    // =======================
    //         SUMO
    // =======================

    // —— Réglages cercle ——
    private const val SUMO_INITIAL_ANGLE_DEG = 45f     // pivot initial (toujours)
    private const val SUMO_STEP_ANGLE_DEG = 60f        // angle appliqué à CHAQUE saut à partir du 2e
    private const val SUMO_JUMP_DELAY_MIN = 80         // ms
    private const val SUMO_JUMP_DELAY_MAX = 150        // ms

    // —— Réglages lissage ——
    private const val INITIAL_SMOOTH_MIN_TICKS = 4     // durée du pivot initial (ticks)
    private const val INITIAL_SMOOTH_MAX_TICKS = 7
    private const val AIR_SMOOTH_MIN_TICKS = 4         // durée des rotations en vol (SUMO) (min/max clamp)
    private const val AIR_SMOOTH_MAX_TICKS = 14

    // — État Sumo —
    private var sumoInitialTurnRight = true
    private var sumoCircleTurnRight = false
    private var sumoJumpCounter = 0
    private var sumoWasOnGround = false

    // — Mesure temps de vol —
    private var ticksAirborneCurrent = 0
    private var lastAirTicks = 8

    // =============== LISSAGE COMMUN ===============
    // Easing au choix (privé, n'impacte pas les autres fichiers)
    private enum class YawEase { SINE, CUBIC, QUINT }

    private var yawPlanActive = false
    private var yawPlanTotalAngle = 0f
    private var yawPlanTicksTotal = 0
    private var yawPlanTickIndex = 0
    private var yawPlanLastEase = 0f
    private var yawPlanEase = YawEase.SINE
    private var queuedYawAngle: Float? = null // Sumo : angle à démarrer au décollage

    // Easing functions
    private fun easeInOutSine(t: Float): Float {
        return (-(cos(PI * t) - 1.0) / 2.0).toFloat()
    }
    private fun easeInOutCubic(t: Float): Float {
        return if (t < 0.5f) 4f * t * t * t
        else {
            val a = -2f * t + 2f
            1f - (a * a * a) / 2f
        }
    }
    private fun easeInOutQuint(t: Float): Float {
        return if (t < 0.5f) 16f * t * t * t * t * t
        else {
            val a = -2f * t + 2f
            1f - (a * a * a * a * a) / 2f
        }
    }

    // Démarrer une rotation lissée (signature d'origine : SINE par défaut)
    private fun startYawPlan(totalAngleDeg: Float, durationTicks: Int) {
        startYawPlan(totalAngleDeg, durationTicks, YawEase.SINE)
    }
    // Surcharge interne pour choisir l'easing (utilisée par FAST_FORWARD)
    private fun startYawPlan(totalAngleDeg: Float, durationTicks: Int, ease: YawEase) {
        val dur = max(1, durationTicks)
        yawPlanActive = true
        yawPlanTotalAngle = totalAngleDeg
        yawPlanTicksTotal = dur
        yawPlanTickIndex = 0
        yawPlanLastEase = 0f
        yawPlanEase = ease
    }

    // Appliquer un tick de la rotation lissée
    private fun tickYawPlan() {
        if (!yawPlanActive) return
        val p = kira.mc.thePlayer ?: return

        yawPlanTickIndex++
        val t = min(1f, yawPlanTickIndex.toFloat() / yawPlanTicksTotal.toFloat())
        val e = when (yawPlanEase) {
            YawEase.SINE  -> easeInOutSine(t)   // SUMO
            YawEase.CUBIC -> easeInOutCubic(t)
            YawEase.QUINT -> easeInOutQuint(t)  // FAST_FORWARD (plus doux)
        }
        val step = yawPlanTotalAngle * (e - yawPlanLastEase)

        p.rotationYaw += step
        yawPlanLastEase = e

        if (yawPlanTickIndex >= yawPlanTicksTotal) {
            yawPlanActive = false
            // sécurité flottants : terminer exactement l'angle
            val done = yawPlanTotalAngle * e
            val correction = yawPlanTotalAngle - done
            p.rotationYaw += correction
        }
    }

    private fun sumoInternal() {
        val player = kira.mc.thePlayer ?: return

        // Pitch “vivant” mais discret
        desiredPitch = RandomUtils.randomDoubleInRange(-3.0, 6.0).toFloat()

        // 1) pivot initial ±45° (easing sine, 4..7 ticks)
        sumoInitialTurnRight = RandomUtils.randomBool()
        val initialDelta = if (sumoInitialTurnRight) SUMO_INITIAL_ANGLE_DEG else -SUMO_INITIAL_ANGLE_DEG
        startYawPlan(initialDelta, RandomUtils.randomIntInRange(INITIAL_SMOOTH_MIN_TICKS, INITIAL_SMOOTH_MAX_TICKS))

        // 2) sens du cercle = opposé au pivot initial
        sumoCircleTurnRight = !sumoInitialTurnRight

        // 3) démarrage
        Movement.startForward()
        Movement.startSprinting()
        tickYawChange = 0f
        sumoJumpCounter = 0
        sumoWasOnGround = player.onGround
        ticksAirborneCurrent = 0
        lastAirTicks = 8
        queuedYawAngle = null

        // 4) 1er saut immédiat (SANS rotation)
        if (player.onGround) {
            Movement.singleJump(RandomUtils.randomIntInRange(SUMO_JUMP_DELAY_MIN, SUMO_JUMP_DELAY_MAX))
            sumoJumpCounter = 1
        }
    }

    private fun sumoTick() {
        val p = kira.mc.thePlayer ?: return

        // maintenir la marche/sprint
        if (!Movement.forward()) Movement.startForward()
        if (!Movement.sprinting()) Movement.startSprinting()

        val onGround = p.onGround
        val justLanded = onGround && !sumoWasOnGround
        val justTookOff = !onGround && sumoWasOnGround

        // Décollage : si une rotation est en attente, la démarrer MAINTENANT et l'étaler sur la durée estimée du vol
        if (justTookOff) {
            ticksAirborneCurrent = 0
            queuedYawAngle?.let { angle ->
                // SUMO : durées clampées via lastAirTicks (4..14) et easing SINE par défaut
                startYawPlan(angle, lastAirTicks)
                queuedYawAngle = null
            }
        }

        // En l’air : compter les ticks de vol
        if (!onGround) {
            ticksAirborneCurrent++
        }

        // Atterrissage : relancer un saut et préparer la rotation du prochain saut
        if (justLanded) {
            lastAirTicks = min(max(ticksAirborneCurrent, AIR_SMOOTH_MIN_TICKS), AIR_SMOOTH_MAX_TICKS)
            Movement.singleJump(RandomUtils.randomIntInRange(SUMO_JUMP_DELAY_MIN, SUMO_JUMP_DELAY_MAX))
            sumoJumpCounter++

            if (sumoJumpCounter >= 2) {
                val angle = if (sumoCircleTurnRight) SUMO_STEP_ANGLE_DEG else -SUMO_STEP_ANGLE_DEG
                queuedYawAngle = angle // on attend le décollage pour démarrer le lissage
            }
            ticksAirborneCurrent = 0
        }

        // Appliquer la rotation lissée planifiée (si en cours)
        tickYawPlan()

        sumoWasOnGround = onGround
    }

    fun stop() {
        Movement.clearAll()
        intervals.forEach { it?.cancel() }
        intervals.clear()
        tickYawChange = 0f
        desiredPitch = null
        activeMovementType = null

        // reset Sumo
        sumoJumpCounter = 0
        sumoInitialTurnRight = true
        sumoCircleTurnRight = false
        sumoWasOnGround = false
        ticksAirborneCurrent = 0
        lastAirTicks = 8
        queuedYawAngle = null

        // reset FF
        ffStrafeTicksLeft = 0

        // reset yaw plan
        yawPlanActive = false
        yawPlanTotalAngle = 0f
        yawPlanTicksTotal = 0
        yawPlanTickIndex = 0
        yawPlanLastEase = 0f
        yawPlanEase = YawEase.SINE
    }

    private fun maintainMovement() {
        when (activeMovementType) {
            Config.LobbyMovementType.FAST_FORWARD -> {
                if (!Movement.forward()) Movement.startForward()
                if (!Movement.sprinting()) Movement.startSprinting()
                // tap-strafe pendant l'arc si activé
                if (FF_STRAFE_ENABLE && ffStrafeTicksLeft > 0) {
                    if (ffStrafeRight) Movement.startRight() else Movement.startLeft()
                    ffStrafeTicksLeft--
                    if (ffStrafeTicksLeft == 0) {
                        if (ffStrafeRight) Movement.stopRight() else Movement.stopLeft()
                    }
                }
            }
            Config.LobbyMovementType.SUMO -> {
                if (!Movement.forward()) Movement.startForward()
                if (!Movement.sprinting()) Movement.startSprinting()
            }
            else -> {}
        }
    }

    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (!canActivateAndRunAnyMovement()) {
            if (activeMovementType != null) stop()
            return
        }

        // entretien commun
        maintainMovement()

        // lissage de yaw (pour tous les modes)
        tickYawPlan()

        desiredPitch?.let { kira.mc.thePlayer!!.rotationPitch = it }
        kira.mc.thePlayer!!.rotationYaw += tickYawChange

        // boucle sumo
        if (activeMovementType == Config.LobbyMovementType.SUMO) {
            sumoTick()
        }
    }
}