package dev.mcai.partner.test.mixin;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(FramerateLimitTracker.class)
public abstract class HeadlessFpsMixin {
    /** Visibility and simulation speed are independent: a visible soak still runs at normal ticks in the background. */
    private static boolean wildling$activeTest() {
        if ("true".equalsIgnoreCase(System.getenv("WILDLING_HEADLESS"))
                || "true".equalsIgnoreCase(System.getenv("WILDLING_TEST_ACTIVE"))) return true;
        String minutes = System.getenv("WILDLING_SOAK_MINUTES");
        try { return minutes != null && Integer.parseInt(minutes) > 0; }
        catch (NumberFormatException ignored) { return false; }
    }
    @Inject(method="getFramerateLimit",at=@At("HEAD"),cancellable=true)
    private void wildling$headlessRate(CallbackInfoReturnable<Integer> result) {
        if (wildling$activeTest()) result.setReturnValue(30);
    }
    @Inject(method="isHeavilyThrottled",at=@At("HEAD"),cancellable=true)
    private void wildling$keepNormalTicks(CallbackInfoReturnable<Boolean> result) {
        if (wildling$activeTest()) result.setReturnValue(false);
    }
}
