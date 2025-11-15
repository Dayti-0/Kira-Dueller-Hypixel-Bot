package best.spaghetcodes.kira.mixins;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {
    @Accessor("thirdPersonDistance")
    float getThirdPersonDistance();

    @Accessor("thirdPersonDistance")
    void setThirdPersonDistance(float distance);

    @Accessor("thirdPersonDistanceTemp")
    float getThirdPersonDistanceTemp();

    @Accessor("thirdPersonDistanceTemp")
    void setThirdPersonDistanceTemp(float distance);
}
