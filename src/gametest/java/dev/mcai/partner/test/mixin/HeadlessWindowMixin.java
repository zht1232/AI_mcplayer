package dev.mcai.partner.test.mixin;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Test-only invisible graphics context; the production mod never changes window visibility. */
@Mixin(Minecraft.class)
public abstract class HeadlessWindowMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V"))
    private void wildling$showOnlyInteractive(long window) {
        if (!"true".equalsIgnoreCase(System.getenv("WILDLING_HEADLESS"))) GLFW.glfwShowWindow(window);
        else System.out.println("WILDLING_HEADLESS: hidden client graphics context, no desktop window shown");
    }
}
