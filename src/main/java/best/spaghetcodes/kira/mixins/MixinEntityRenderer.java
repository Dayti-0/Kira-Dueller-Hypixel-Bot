package best.spaghetcodes.kira.mixins;

import best.spaghetcodes.kira.core.CameraController;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    @Redirect(method = "orientCamera", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;rotate(FFFF)V"))
    private void kira$redirectRotate(float angle, float x, float y, float z) {
        GlStateManager.rotate(CameraController.INSTANCE.adjustRotation(angle, x, y, z), x, y, z);
    }

    @Inject(method = "orientCamera", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V", ordinal = 0, shift = At.Shift.AFTER))
    private void kira$afterTranslate(float partialTicks, CallbackInfo ci) {
        CameraController.CameraOffset offset = CameraController.INSTANCE.cameraOffset();
        if (offset != null) {
            GlStateManager.translate(offset.getX(), offset.getY(), offset.getZ());
        }
    }
}
