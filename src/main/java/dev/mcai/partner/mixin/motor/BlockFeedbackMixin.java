package dev.mcai.partner.mixin.motor;

import dev.mcai.partner.PartnerClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only authoritative server updates can confirm a mining result. */
@Mixin(ClientPacketListener.class)
public abstract class BlockFeedbackMixin {
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void partner$block(ClientboundBlockUpdatePacket packet, CallbackInfo info) {
        PartnerClient client = PartnerClient.instance();
        if (client != null) client.serverBlockUpdate(packet.getPos(), packet.getBlockState());
    }
    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void partner$blocks(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo info) {
        PartnerClient client = PartnerClient.instance();
        if (client != null) packet.runUpdates(client::serverBlockUpdate);
    }
}
