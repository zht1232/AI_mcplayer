package dev.mcai.partner.mixin.ui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractSliderButton.class)
public interface SliderAccessor {
    @Accessor("value") double mcai$value();
}
