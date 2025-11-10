package best.spaghetcodes.kira.mixins;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mixin(LayerCape.class)
public abstract class MixinLayerCape {

    @Shadow
    @Final
    private RenderPlayer playerRenderer;

    @Unique
    private static final Logger KIRA$LOGGER = LogManager.getLogger("Kira");

    @Unique
    private static final Set<UUID> KIRA$FAILED_CAPES = new HashSet<>();

    @Overwrite
    public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
                              float ageInTicks, float netHeadYaw, float headPitch, float scale) {
        if (player == null || !player.hasPlayerInfo() || player.isInvisible() || !player.isWearing(EnumPlayerModelParts.CAPE)) {
            return;
        }

        ResourceLocation capeLocation = player.getLocationCape();
        if (capeLocation == null) {
            return;
        }

        RenderPlayer renderer = this.playerRenderer;
        if (renderer == null) {
            return;
        }

        ModelPlayer model = renderer.getMainModel();
        if (model == null) {
            return;
        }

        boolean matrixPushed = false;

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            renderer.bindTexture(capeLocation);
            GlStateManager.pushMatrix();
            matrixPushed = true;

            GlStateManager.translate(0.0F, 0.0F, 0.125F);

            double chasingX = player.prevChasingPosX + (player.chasingPosX - player.prevChasingPosX) * (double) partialTicks;
            double chasingY = player.prevChasingPosY + (player.chasingPosY - player.prevChasingPosY) * (double) partialTicks;
            double chasingZ = player.prevChasingPosZ + (player.chasingPosZ - player.prevChasingPosZ) * (double) partialTicks;
            double interpX = player.prevPosX + (player.posX - player.prevPosX) * (double) partialTicks;
            double interpY = player.prevPosY + (player.posY - player.prevPosY) * (double) partialTicks;
            double interpZ = player.prevPosZ + (player.posZ - player.prevPosZ) * (double) partialTicks;

            double deltaX = chasingX - interpX;
            double deltaY = chasingY - interpY;
            double deltaZ = chasingZ - interpZ;

            float bodyYaw = player.prevRenderYawOffset + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;
            double yawSin = MathHelper.sin(bodyYaw * (float) Math.PI / 180.0F);
            double yawCos = -MathHelper.cos(bodyYaw * (float) Math.PI / 180.0F);

            float capePitch = (float) deltaY * 10.0F;
            capePitch = MathHelper.clamp_float(capePitch, -6.0F, 32.0F);

            float swayForward = (float) (deltaX * yawSin + deltaZ * yawCos) * 100.0F;
            float swaySideways = (float) (deltaX * yawCos - deltaZ * yawSin) * 100.0F;

            if (swayForward < 0.0F) {
                swayForward = 0.0F;
            }

            float cameraYaw = player.prevCameraYaw + (player.cameraYaw - player.prevCameraYaw) * partialTicks;
            float walkAmount = player.prevDistanceWalkedModified + (player.distanceWalkedModified - player.prevDistanceWalkedModified) * partialTicks;

            capePitch += MathHelper.sin(walkAmount * 6.0F) * 32.0F * cameraYaw;

            if (player.isSneaking()) {
                capePitch += 25.0F;
            }

            GlStateManager.rotate(6.0F + swayForward / 2.0F + capePitch, 1.0F, 0.0F, 0.0F);
            GlStateManager.rotate(swaySideways / 2.0F, 0.0F, 0.0F, 1.0F);
            GlStateManager.rotate(-swaySideways / 2.0F, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(180.0F, 0.0F, 1.0F, 0.0F);

            GlStateManager.disableCull();
            model.renderCape(0.0625F);
        } catch (Exception ex) {
            UUID uuid = player.getUniqueID();
            if (uuid == null || KIRA$FAILED_CAPES.add(uuid)) {
                KIRA$LOGGER.warn("Failed to render cape for {}", player.getName(), ex);
            }
        } finally {
            if (matrixPushed) {
                GlStateManager.popMatrix();
            }

            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.disableBlend();
            GlStateManager.enableCull();
            GlStateManager.enableDepth();
        }
    }
}

