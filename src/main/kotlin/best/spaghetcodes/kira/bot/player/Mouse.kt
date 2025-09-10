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

    private var leftAC = false
    var rClickDown = false

    private var tracking = false

    private var _usingProjectile = false
    private var _usingPotion = false
    private var _runningAway = false

    private var leftClickDur = 0

    private var lastLeftClick = 0L

    private var runningRotations: FloatArray? = null

    private var splashAim = 0.0

    // ---- Bow ballistic compensation (pitch) ----
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
        return when {
            // offset nul jusqu'à ~30 blocs
            distance < 30f -> 0.0f
            // augmentation progressive avec la distance
            distance < 35f -> 1.0f
            distance < 40f -> 2.0f
            distance < 50f -> 3.0f
            distance < 60f -> 4.0f
            else -> 5.0f
        }
    }
    // --------------------------------------------

    fun leftClick() {
        if (
            kira.bot?.toggled() == true &&
            kira.config?.enableHits == true &&
            kira.mc.thePlayer != null &&
            !kira.mc.thePlayer.isUsingItem
        ) {
            kira.mc.thePlayer.swingItem()
            KeyBinding.setKeyBindState(kira.mc.gameSettings.keyBindAttack.keyCode, true)
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
        if (kira.bot?.toggled() == true && kira.config?.enableHits == true) {
            val minCps = kira.config?.minCPS ?: 0
            val maxCps = kira.config?.maxCPS ?: 0
            if (minCps > 0 && maxCps > 0) {
                leftAC = true
            }
        }
    }

    fun stopLeftAC() {
        leftAC = false
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
        if (kira.bot?.toggled() == true && kira.config?.enableHits == true && leftAC) {
            if (!kira.mc.thePlayer.isUsingItem) {
                val minCPS = kira.config?.minCPS ?: 10
                val maxCPS = kira.config?.maxCPS ?: 14

                if (minCPS <= 0 || maxCPS <= 0) return

                if (System.currentTimeMillis() >= lastLeftClick + (1000 / RandomUtils.randomIntInRange(minCPS, maxCPS))) {
                    leftClick()
                    lastLeftClick = System.currentTimeMillis()
                }
            }
        }
    }

    private fun rClickDown() {
        if (kira.bot?.toggled() == true) {
            rClickDown = true
            val key = kira.mc.gameSettings.keyBindUseItem.keyCode
            KeyBinding.setKeyBindState(key, true)
            KeyBinding.onTick(key)
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
            if (rClickDown &&
                kira.mc.thePlayer?.heldItem?.unlocalizedName?.lowercase()?.contains("bow") == true
            ) {
                // ne jamais avancer/reculer pendant la visée
                Movement.stopForward()
                Movement.stopBackward()
            }
            if (_runningAway) _usingProjectile = false
            var rotations = EntityUtils.getRotations(kira.mc.thePlayer, kira.bot?.opponent(), false)

            if (rotations != null) {
                val distance = EntityUtils.getDistanceNoY(kira.mc.thePlayer, kira.bot?.opponent()!!)
                val closeCombat = distance <= 3.5f

                if (_runningAway) {
                    if (runningRotations == null) {
                        runningRotations = rotations
                        runningRotations!![0] += 180 + RandomUtils.randomDoubleInRange(-5.0, 5.0).toFloat()
                    }
                    rotations = runningRotations!!
                }

                if (_usingPotion) {
                    if (splashAim == 0.0) splashAim = RandomUtils.randomDoubleInRange(80.0, 90.0)
                    rotations[1] = splashAim.toFloat()
                } else if (
                    _usingProjectile &&
                    kira.mc.thePlayer?.heldItem?.unlocalizedName?.lowercase()?.contains("pearl") == true
                ) {
                    rotations[1] -= distance * 0.8f
                }

                val lookRand = (kira.config?.lookRand ?: 0).toDouble()
                val minDist = (kira.config?.lookRandMinDistance ?: 5).toFloat()
                val maxDist = (kira.config?.lookRandMaxDistance ?: 15).toFloat()
                val scale = if (maxDist <= minDist) 1f
                else ((distance - minDist) / (maxDist - minDist)).coerceIn(0f, 1f)
                val isBowAiming = rClickDown &&
                    kira.mc.thePlayer?.heldItem?.unlocalizedName?.lowercase()?.contains("bow") == true
                var randYaw = if (isBowAiming) 0.0
                else RandomUtils.randomDoubleInRange(-lookRand, lookRand) * scale
                var randPitch = if (isBowAiming) 0.0
                else RandomUtils.randomDoubleInRange(-lookRand, lookRand) * scale
                if (closeCombat) {
                    randYaw = 0.0
                    randPitch = 0.0
                }
                var dyaw = ((rotations[0] - kira.mc.thePlayer.rotationYaw) + randYaw).toFloat()
                var dpitch = ((rotations[1] - kira.mc.thePlayer.rotationPitch) + randPitch).toFloat()

                if (abs(dyaw) < 0.5f && abs(dpitch) < 0.5f) {
                    randYaw = 0.0
                    randPitch = 0.0
                    dyaw = ((rotations[0] - kira.mc.thePlayer.rotationYaw) + randYaw).toFloat()
                    dpitch = ((rotations[1] - kira.mc.thePlayer.rotationPitch) + randPitch).toFloat()
                }

                // Compensation de pitch quand l'arc est bandé
                val bowOffset = if (isBowAiming) bowPitchComp(bowDistanceToOpponent()) else 0f
                dpitch -= bowOffset

                val factor = when (distance) {
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
