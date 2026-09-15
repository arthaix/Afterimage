package ru.arthaix.keystone.irfar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketClientSettings;
import ru.arthaix.keystone.irfar.IrFar;

/** The render distance in a player's client settings, which vanilla 1.12 reads and ignores, sets their IrFar range. */
@Mixin(value = NetHandlerPlayServer.class, remap = false)
public abstract class MixinNetHandlerPlayServerFar {
    @Shadow
    public EntityPlayerMP field_147369_b;

    @Inject(method = "func_147352_a(Lnet/minecraft/network/play/client/CPacketClientSettings;)V", at = @At("HEAD"), require = 0)
    private void irfar$viewDistance(CPacketClientSettings packet, CallbackInfo ci) {
        if (this.field_147369_b != null) {
            IrFar.setViewChunks(this.field_147369_b.func_110124_au(), ((CPacketClientSettingsAccessor) packet).irfar$view());
        }
    }
}
