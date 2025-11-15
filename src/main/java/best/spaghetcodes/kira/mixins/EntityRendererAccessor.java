package best.spaghetcodes.kira.mixins;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {
    @Accessor("thirdPersonDistance")
    double getThirdPersonDistance();

    @Accessor("thirdPersonDistance")
    void setThirdPersonDistance(double distance);

    @Accessor("thirdPersonDistanceTemp")
    double getThirdPersonDistanceTemp();

    @Accessor("thirdPersonDistanceTemp")
    void setThirdPersonDistanceTemp(double distance);
}
