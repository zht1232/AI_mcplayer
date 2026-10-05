package dev.mcai.partner.test.mixin;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(FramerateLimitTracker.class)
public abstract class HeadlessFpsMixin {
    @Inject(method="getFramerateLimit",at=@At("HEAD"),cancellable=true)
    private void wildling$headlessRate(CallbackInfoReturnable<Integer> result) {
        if ("true".equalsIgnoreCase(System.getenv("WILDLING_HEADLESS"))) result.setReturnValue(30);
    }
    @Inject(method="isHeavilyThrottled",at=@At("HEAD"),cancellable=true)
    private void wildling$keepNormalTicks(CallbackInfoReturnable<Boolean> result) {
        if ("true".equalsIgnoreCase(System.getenv("WILDLING_HEADLESS"))) result.setReturnValue(false);
    }
}
