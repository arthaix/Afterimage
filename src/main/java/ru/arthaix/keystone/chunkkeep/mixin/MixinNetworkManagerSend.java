package ru.arthaix.keystone.chunkkeep.mixin;

import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.chunkkeep.SendBacklog;

/** NetworkManager.sendPacket: chunk packets are counted towards the connection's backlog (SendBacklog). */
@Mixin(value = NetworkManager.class, remap = false)
public abstract class MixinNetworkManagerSend {
    @Inject(method = "func_179290_a(Lnet/minecraft/network/Packet;)V", at = @At("HEAD"))
    private void chunkkeep$countChunkPacket(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof SPacketChunkData) {
            SendBacklog.queued(((NetworkManagerAccessor) this).chunkkeep$channel(), (SPacketChunkData) packet);
        }
    }
}
