package dev.mcai.partner.mixin.motor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Display.BlockDisplay.class)
public interface DisplayBlockAccessor {
    @Invoker("getBlockState") BlockState wildling$block();
}
