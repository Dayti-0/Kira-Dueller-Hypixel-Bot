package best.spaghetcodes.kira.bot.player

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.utils.EntityUtils
import best.spaghetcodes.kira.utils.RandomUtils
import best.spaghetcodes.kira.utils.TimeUtils
import net.minecraft.client.settings.KeyBinding
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import kotlin.math.abs

object Mouse {

    private const val LEFT_HOLD_TICKS_MIN = 1    // ticks to hold attack key
    private const val LEFT_HOLD_TICKS_MAX = 3
    private const val JITTER_MS_MIN = -20       // ms jitter on click delay
    private const val JITTER_MS_MAX = 20
    private const val MIN_DELAY_MS = 5          // enforce a small positive delay

    private var leftAC = false
    var rClickDown = false

    private var tracking = false

    private var _usingProjectile = false
    private var _usingPotion = false
    private var _runningAway = false

    private var leftClickDur = 0
    /** Scheduled time for the next left click, in nanoseconds */
    private var nextLeftClickNs = 0L
    private var lastCPS = 0

    private var runningRotations: FloatArray? = null

    private var splashAim = 0.0

    // ---- Bow ballistic compensation (pitch) ----
    private var bowPitchBias = 0f

    fun setBowPitchBias(bias: Float) {
        bowPitchBias = bias
    }

    private fun bowDistanceToOpponent(): Float {
        val p = kira.mc.thePlayer ?: return 0f
        val opp = kira.bot?.opponent() ?: return 0f
        return EntityUtils.getDistanceNoY(p, opp)
    }

    /**
     * Décalage vertical en degrés à appliquer quand l'arc est bandé.
     * Adouci à courte portée (tests terrain) ; full draw 1.8.9.
     * (pitch positif = on regarde vers le bas, donc on SOUSTRAIT l'offset)
     *
     * Ajustement: mid-range 15–25 blocs un peu **moins haut**.
     */
    private fun bowPitchComp(distance: Float): Float {
        val base = when {
            distance < 30f -> 0.0f
            distance < 32f -> 0.4f
            distance < 35f -> 0.8f
            distance < 38f -> 1.2f
            distance < 40f -> 1.6f
            distance < 45f -> 2.0f
            distance < 50f -> 2.5f
            distance < 55f -> 3.1f
            distance < 60f -> 3.7f
            distance < 65f -> 4.4f
            distance < 70f -> 5.0f
            distance < 75f -> 5.3f
            distance < 80f -> 5.5f
            else           -> 5.6f
        }
        return base + bowPitchBias
    }
    // --------------------------------------------

    fun leftClick() {
        if (kira.bot?.toggled() == true &&
            kira.config?.kiraHit == true &&
            kira.mc.thePlayer != null &&
            !kira.mc.thePlayer.isUsingItem) {
            kira.mc.thePlayer.swingItem()
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindAttack.keyCode, true)
            leftClickDur = RandomUtils.randomIntInRange(LEFT_HOLD_TICKS_MIN, LEFT_HOLD_TICKS_MAX)
            if (kira.mc.objectMouseOver != null && kira.mc.objectMouseOver.entityHit != null) {
                kira.mc.playerController.attackEntity(kira.mc.thePlayer, kira.mc.objectMouseOver.entityHit)
            }
        }
    }

    fun rClick(duration: Int) {
        if (kira.bot?.toggled() == true) {
            if (!rClickDown) {
                rClickDown()
                TimeUtils.setTimeout(this::rClickUp, duration)
            }
        }
    }

    fun startLeftAC() {
        if (kira.bot?.toggled() == true && kira.config?.kiraHit == true) {
            leftAC = true
        }
    }

    fun stopLeftAC() {
        leftAC = false
        nextLeftClickNs = 0L
        lastCPS = 0
    }

    fun currentCPS(): Int {
        return lastCPS
    }

    fun startTracking() {
        tracking = true
    }

    fun stopTracking() {
        tracking = false
    }

    fun setUsingProjectile(proj: Boolean) {
        _usingProjectile = proj
    }

    fun isUsingProjectile(): Boolean {
        return _usingProjectile
    }

    fun setUsingPotion(potion: Boolean) {
        _usingPotion = potion
        if (!_usingPotion) {
            splashAim = 0.0
        }
    }

    fun isUsingPotion(): Boolean {
        return _usingPotion
    }

    fun setRunningAway(runningAway: Boolean) {
        _runningAway = runningAway
        runningRotations = null
    }

    fun isRunningAway(): Boolean {
        return _runningAway
    }

    private fun leftACFunc() {
        if (kira.bot?.toggled() == true && leftAC && kira.config?.kiraHit == true) {
            val player = kira.mc.thePlayer
            val target = kira.bot?.opponent()
            val maxDist = (kira.config?.maxDistanceAttack ?: 4).toFloat()

            if (player != null && target != null &&
                EntityUtils.getDistanceNoY(player, target) <= maxDist &&
                !player.isUsingItem
            ) {
                val lo = (kira.config?.minCPS ?: 10).coerceAtLeast(1)
                val hi = (kira.config?.maxCPS ?: 14).coerceAtLeast(lo)
                if (nextLeftClickNs == 0L) {
                    scheduleNextClick(lo, hi)
                }
                if (System.nanoTime() >= nextLeftClickNs) {
                    leftClick()
                    scheduleNextClick(lo, hi)
                }
            }
        }
    }

    private fun scheduleNextClick(minCPS: Int, maxCPS: Int) {
        // Smoothly vary CPS to keep a natural rhythm without full stops
        val cps = if (lastCPS == 0) {
            RandomUtils.randomIntInRange(minCPS, maxCPS)
        } else {
            (lastCPS + RandomUtils.randomIntInRange(-1, 1)).coerceIn(minCPS, maxCPS)
        }
        lastCPS = cps
        val baseDelay = 1000 / cps
        val jitter = RandomUtils.randomIntInRange(JITTER_MS_MIN, JITTER_MS_MAX)
        // ensure delay remains positive even with negative jitter
        val delayMs = (baseDelay + jitter).coerceAtLeast(MIN_DELAY_MS)
        // Use monotonic time to avoid issues if system clock changes
        nextLeftClickNs = System.nanoTime() + delayMs * 1_000_000L
    }

    private fun rClickDown() {
        if (kira.bot?.toggled() == true) {
            rClickDown = true
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindUseItem.keyCode, true)
        }
    }

    fun rClickUp() {
        if (kira.bot?.toggled() == true) {
            rClickDown = false
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindUseItem.keyCode, false)
        }
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onTick(event: TickEvent.ClientTickEvent) {
        if (kira.mc.thePlayer != null && kira.bot?.toggled() == true) {
            if (leftAC) leftACFunc()
            if (leftClickDur > 0) {
                leftClickDur--
            } else {
                KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindAttack.keyCode, false)
            }
        }
        if (kira.mc.thePlayer != null && kira.bot?.toggled() == true && tracking && kira.bot?.opponent() != null) {
            if (_runningAway) _usingProjectile = false
            var rotations = EntityUtils.getRotations(kira.mc.thePlayer, kira.bot?.opponent(), false)

            if (rotations != null) {
                if (_runningAway) {
                    if (runningRotations == null) {
                        runningRotations = rotations
                        runningRotations!![0] += 180 + RandomUtils.randomDoubleInRange(-5.0, 5.0).toFloat()
                    }
                    rotations = runningRotations!!
                }

                if (_usingPotion) {
                    if (splashAim == 0.0) splashAim = RandomUtils.randomDoubleInRange(-2.0, 2.0)
                    rotations[1] = splashAim.toFloat()
                }

                val lookRand = (kira.config?.lookRand ?: 0).toDouble()
                var dyaw = ((rotations[0] - kira.mc.thePlayer.rotationYaw) + RandomUtils.randomDoubleInRange(-lookRand, lookRand)).toFloat()
                var dpitch = ((rotations[1] - kira.mc.thePlayer.rotationPitch) + RandomUtils.randomDoubleInRange(-lookRand, lookRand)).toFloat()

                // Compensation de pitch quand l'arc est bandé
                val bowOffset =
                    if (rClickDown &&
                        kira.mc.thePlayer?.heldItem?.unlocalizedName?.lowercase()?.contains("bow") == true
                    ) bowPitchComp(bowDistanceToOpponent())
                    else 0f
                dpitch -= bowOffset

                val factor = when (EntityUtils.getDistanceNoY(kira.mc.thePlayer, kira.bot?.opponent()!!)) {
                    in 0f..10f -> 1.0f
                    in 10f..20f -> 0.6f
                    in 20f..30f -> 0.4f
                    else -> 0.2f
                }

                val maxRotH = (kira.config?.lookSpeedHorizontal ?: 10).toFloat() * factor
                val maxRotV = (kira.config?.lookSpeedVertical ?: 5).toFloat() * factor

                if (abs(dyaw) > maxRotH) dyaw = if (dyaw > 0) maxRotH else -maxRotH
                if (abs(dpitch) > maxRotV) dpitch = if (dpitch > 0) maxRotV else -maxRotV

                kira.mc.thePlayer.rotationYaw += dyaw
                kira.mc.thePlayer.rotationPitch += dpitch
            }
        }
    }
}
