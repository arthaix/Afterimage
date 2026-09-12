package ru.arthaix.keystone.teunloadbatch.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import ru.arthaix.keystone.teunloadbatch.ChunkTagsDecoded;

/** SPacketChunkData.readPacketData (client network thread) hands the decoded tile entity tags to ChunkTagsDecoded. */
@Mixin(value = SPacketChunkData.class, remap = false)
public abstract class MixinSPacketChunkDataDecode {
    @Shadow
    private List<NBTTagCompound> field_189557_e;

    @Inject(method = "func_148837_a(Lnet/minecraft/network/PacketBuffer;)V", at = @At("RETURN"), require = 0)
    private void teunloadbatch$decoded(PacketBuffer buf, CallbackInfo ci) {
        ChunkTagsDecoded.fire(this.field_189557_e);
    }
}
