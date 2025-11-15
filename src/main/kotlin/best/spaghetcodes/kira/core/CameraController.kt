package best.spaghetcodes.kira.core

import best.spaghetcodes.kira.kira
import best.spaghetcodes.kira.mixins.EntityRendererAccessor
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent

object CameraController {
    private val mc: Minecraft = Minecraft.getMinecraft()

    private var storedThirdPersonView: Int? = null
    private var storedSmoothCamera: Boolean? = null
    private var storedDistances: DistanceSnapshot? = null

    private const val CINEMATIC_DISTANCE = 12.0
    private const val PITCH_OFFSET = 12f
    private const val VERTICAL_OFFSET = 0.6f

    private data class DistanceSnapshot(
        val distance: Double,
        val temp: Double
    )

    private fun accessor(): EntityRendererAccessor? = mc.entityRenderer as? EntityRendererAccessor

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val config = kira.config ?: return
        val renderer = accessor() ?: return

        if (config.cinematicCamera) {
            applyCinematicSettings(renderer)
        } else {
            restore(renderer)
        }
    }

    private fun applyCinematicSettings(renderer: EntityRendererAccessor) {
        if (storedThirdPersonView == null) {
            storedThirdPersonView = mc.gameSettings.thirdPersonView
            storedSmoothCamera = mc.gameSettings.smoothCamera
            storedDistances = DistanceSnapshot(
                renderer.getThirdPersonDistance(),
                renderer.getThirdPersonDistanceTemp()
            )
        }

        mc.gameSettings.thirdPersonView = 1
        mc.gameSettings.smoothCamera = true

        renderer.setThirdPersonDistance(CINEMATIC_DISTANCE)
        renderer.setThirdPersonDistanceTemp(CINEMATIC_DISTANCE)
    }

    private fun restore(renderer: EntityRendererAccessor) {
        storedThirdPersonView?.let { mc.gameSettings.thirdPersonView = it }
        storedSmoothCamera?.let { mc.gameSettings.smoothCamera = it }
        storedDistances?.let {
            renderer.setThirdPersonDistance(it.distance)
            renderer.setThirdPersonDistanceTemp(it.temp)
        }

        storedThirdPersonView = null
        storedSmoothCamera = null
        storedDistances = null
    }

    fun adjustRotation(angle: Float, axisX: Float, axisY: Float, axisZ: Float): Float {
        if (!isActive() || mc.gameSettings.thirdPersonView <= 0) return angle
        return if (axisX == 1.0f && axisY == 0.0f && axisZ == 0.0f) angle - PITCH_OFFSET else angle
    }

    fun verticalOffset(): Float = if (isActive()) VERTICAL_OFFSET else 0f

    fun isActive(): Boolean = kira.config?.cinematicCamera == true
}
