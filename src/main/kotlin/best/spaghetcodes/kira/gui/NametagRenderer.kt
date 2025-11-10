package best.spaghetcodes.kira.gui

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.entity.RenderManager
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.scoreboard.ScorePlayerTeam
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import org.lwjgl.opengl.GL11

object NametagRenderer {

    @SubscribeEvent
    fun onRenderLivingSpecial(event: RenderLivingEvent.Specials.Pre<EntityLivingBase>) {
        val mc: Minecraft? = Minecraft.getMinecraft()
        if (mc == null) {
            return
        }

        val renderManager: RenderManager? = mc.renderManager
        if (renderManager == null) {
            return
        }

        val fontRenderer = mc.fontRendererObj ?: return
        val viewer: EntityPlayer = mc.thePlayer ?: return
        val entityPlayer = event.entity as? EntityPlayer ?: return
        val world = entityPlayer.worldObj ?: return
        val scoreboard = world.scoreboard ?: return
        val team = entityPlayer.team ?: scoreboard.getPlayersTeam(entityPlayer.commandSenderName)

        if (!entityPlayer.isEntityAlive) {
            return
        }

        if (entityPlayer.isInvisible) {
            return
        }

        if (entityPlayer.getDistanceSqToEntity(viewer) > 64.0 * 64.0) {
            return
        }

        if (entityPlayer === viewer && mc.gameSettings.thirdPersonView == 0) {
            return
        }

        val name = if (team != null) {
            ScorePlayerTeam.formatPlayerName(team, entityPlayer.commandSenderName)
        } else {
            entityPlayer.displayName.formattedText
        }
        event.isCanceled = true

        val x = event.x
        val y = event.y
        val z = event.z

        GlStateManager.pushMatrix()
        try {
            GlStateManager.translate(x, y + entityPlayer.height + 0.5f, z)
            GlStateManager.rotate(-renderManager.playerViewY, 0f, 1f, 0f)
            GlStateManager.rotate(renderManager.playerViewX, 1f, 0f, 0f)
            GlStateManager.scale(-0.025f, -0.025f, 0.025f)

            GlStateManager.disableLighting()
            GlStateManager.depthMask(false)
            GlStateManager.disableDepth()
            GlStateManager.enableBlend()
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0) // SRC_ALPHA, ONE_MINUS_SRC_ALPHA

            val tessellator = Tessellator.getInstance()
            val buffer = tessellator.worldRenderer
            val textWidth = fontRenderer.getStringWidth(name).toFloat()
            val halfWidth = textWidth / 2f

            GlStateManager.disableTexture2D()
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR)
            buffer.pos((-halfWidth - 1f).toDouble(), -1.0, 0.0).color(0f, 0f, 0f, 0.25f).endVertex()
            buffer.pos((-halfWidth - 1f).toDouble(), (fontRenderer.FONT_HEIGHT.toFloat() + 1f).toDouble(), 0.0).color(0f, 0f, 0f, 0.25f).endVertex()
            buffer.pos((halfWidth + 1f).toDouble(), (fontRenderer.FONT_HEIGHT.toFloat() + 1f).toDouble(), 0.0).color(0f, 0f, 0f, 0.25f).endVertex()
            buffer.pos((halfWidth + 1f).toDouble(), -1.0, 0.0).color(0f, 0f, 0f, 0.25f).endVertex()
            tessellator.draw()
            GlStateManager.enableTexture2D()

            fontRenderer.drawString(name, -textWidth / 2f, 0f, 553648127, false)
            GlStateManager.enableDepth()
            GlStateManager.depthMask(true)
        } catch (t: Throwable) {
            System.out.println("[Feather::Nametags] " + t)
        } finally {
            GlStateManager.disableBlend()
            GlStateManager.enableTexture2D()
            GlStateManager.enableLighting()
            GlStateManager.enableDepth()
            GlStateManager.depthMask(true)
            GlStateManager.popMatrix()
        }
    }
}
