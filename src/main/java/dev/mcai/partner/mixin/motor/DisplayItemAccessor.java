package dev.mcai.partner.mixin.motor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Display.ItemDisplay.class)
public interface DisplayItemAccessor {
    @Invoker("getItemStack") ItemStack wildling$item();
    @Invoker("setItemStack") void wildling$setItem(ItemStack item);
}
