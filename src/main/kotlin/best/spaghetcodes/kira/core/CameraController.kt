package best.spaghetcodes.kira.core

import best.spaghetcodes.kira.kira
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EntityRenderer
import net.minecraftforge.client.event.RenderGameOverlayEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.relauncher.ReflectionHelper
import org.apache.logging.log4j.LogManager
import java.lang.reflect.Field

object CameraController {
    private val mc: Minecraft = Minecraft.getMinecraft()

    private val logger = LogManager.getLogger("Kira")

    private var storedThirdPersonView: Int? = null
    private var storedDistances: DistanceSnapshot? = null

    private const val CINEMATIC_DISTANCE = 12f
    private const val TOP_DOWN_PITCH = 90f
    private const val VERTICAL_OFFSET = 0.6f

    private data class DistanceSnapshot(
        val distance: Float,
        val temp: Float
    )

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val config = kira.config ?: return
        val renderer = mc.entityRenderer ?: return

        if (config.cinematicCamera) {
            applyCinematicSettings(renderer)
        } else {
            restore(renderer)
        }
    }

    private fun applyCinematicSettings(renderer: EntityRenderer) {
        if (storedThirdPersonView == null) {
            storedThirdPersonView = mc.gameSettings.thirdPersonView
            storedDistances = DistanceAccess.snapshot(renderer)
        }

        mc.gameSettings.thirdPersonView = 2

        DistanceAccess.apply(renderer, CINEMATIC_DISTANCE, CINEMATIC_DISTANCE)
    }

    private fun restore(renderer: EntityRenderer) {
        storedThirdPersonView?.let { mc.gameSettings.thirdPersonView = it }
        storedDistances?.let {
            DistanceAccess.apply(renderer, it.distance, it.temp)
        }

        storedThirdPersonView = null
        storedDistances = null
    }

    fun adjustRotation(angle: Float, axisX: Float, axisY: Float, axisZ: Float): Float {
        if (!isActive() || mc.gameSettings.thirdPersonView <= 0) return angle
        return if (axisX == 1.0f && axisY == 0.0f && axisZ == 0.0f) TOP_DOWN_PITCH else angle
    }

    fun verticalOffset(): Float = if (isActive()) VERTICAL_OFFSET else 0f

    fun isActive(): Boolean = kira.config?.cinematicCamera == true

    @SubscribeEvent
    fun onRenderOverlay(event: RenderGameOverlayEvent.Pre) {
        if (!isActive()) return
        if (event.type == RenderGameOverlayEvent.ElementType.CROSSHAIRS || event.type == RenderGameOverlayEvent.ElementType.HOTBAR) {
            event.isCanceled = true
        }
    }

    private object DistanceAccess {
        private val distanceField = resolve("thirdPersonDistance", "field_78490_B", "q")
        private val tempField = resolve("thirdPersonDistanceTemp", "field_78491_C", "r")

        fun snapshot(renderer: EntityRenderer): DistanceSnapshot? {
            val distance = read(distanceField, renderer)
            val temp = read(tempField, renderer)
            return if (distance != null && temp != null) DistanceSnapshot(distance, temp) else null
        }

        fun apply(renderer: EntityRenderer, distance: Float, temp: Float) {
            write(distanceField, renderer, distance)
            write(tempField, renderer, temp)
        }

        private fun resolve(vararg names: String): Field? = try {
            ReflectionHelper.findField(EntityRenderer::class.java, *names).apply { isAccessible = true }
        } catch (throwable: ReflectionHelper.UnableToFindFieldException) {
            logger.error("Failed to resolve cinematic camera field {}", names.joinToString(", "), throwable)
            null
        }

        private fun read(field: Field?, renderer: EntityRenderer): Float? = field?.let {
            runCatching { it.getFloat(renderer) }.getOrElse { error ->
                logger.error("Failed to read cinematic camera distance from {}", it.name, error)
                null
            }
        }

        private fun write(field: Field?, renderer: EntityRenderer, value: Float) {
            field ?: return
            runCatching { field.setFloat(renderer, value) }.onFailure { error ->
                logger.error("Failed to update cinematic camera distance for {}", field.name, error)
            }
        }
    }
}
