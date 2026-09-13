package ru.arthaix.keystone.chunkkeep.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketKeepAlive;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep-alive round trips over 3 s are logged with the player's chunk send backlog: a player disconnected with
 * "Timed out" left no trace of what delayed their reply.
 */
@Mixin(value = NetHandlerPlayServer.class, remap = false)
public abstract class MixinNetHandlerPlayServerKeepAlive {
    @Shadow
    public EntityPlayerMP field_147369_b;

    /** keepAliveTime */
    @Shadow
    private long field_194402_f;

    /** keepAlivePending */
    @Shadow
    private boolean field_194403_g;

    /** keepAliveKey */
    @Shadow
    private long field_194404_h;

    @Inject(method = "func_147353_a(Lnet/minecraft/network/play/client/CPacketKeepAlive;)V", at = @At("HEAD"), require = 0)
    private void chunkkeep$logSlowKeepAlive(CPacketKeepAlive packet, CallbackInfo ci) {
        if (this.field_194403_g && packet.func_149460_c() == this.field_194404_h) {
            long ms = System.currentTimeMillis() - this.field_194402_f;
            if (ms > 3000L) {
                System.out.println("[chunkkeep] keep-alive of " + (this.field_147369_b == null ? "?" : this.field_147369_b.func_70005_c_())
                    + " answered after " + ms + " ms (" + ru.arthaix.keystone.chunkkeep.SendBacklog.stats() + ")");
            }
        }
    }
}
