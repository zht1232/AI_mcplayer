package dev.mcai.partner.mixin.ui;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerTabOverlay.class)
public interface TabOverlayAccessor {
    @Accessor("header") Component mcai$header();
    @Accessor("footer") Component mcai$footer();
}
