package dev.mcai.partner.mixin.ui;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Hud.class)
public interface HudAccessor {
    @Accessor("overlayMessageString") Component mcai$actionBar();
    @Accessor("overlayMessageTime") int mcai$actionBarTicks();
    @Accessor("title") Component mcai$title();
    @Accessor("subtitle") Component mcai$subtitle();
    @Accessor("titleTime") int mcai$titleTicks();
}
