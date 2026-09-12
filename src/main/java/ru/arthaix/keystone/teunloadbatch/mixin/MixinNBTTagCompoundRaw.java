package ru.arthaix.keystone.teunloadbatch.mixin;

import java.io.DataOutput;
import java.io.IOException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.nbt.NBTTagCompound;
import ru.arthaix.keystone.teunloadbatch.RawCompound;

/**
 * A compound made by TagCache for a chunk packet carries the serialized body of a tile entity's update tag and writes
 * those bytes instead of its (empty) entries, so the network encoder puts exactly the bytes of the original tag on the wire.
 */
@Mixin(value = NBTTagCompound.class, remap = false)
public abstract class MixinNBTTagCompoundRaw implements RawCompound {
    @Unique
    private byte[] teunloadbatch$raw;

    @Shadow
    abstract void func_74734_a(DataOutput output) throws IOException;

    @Inject(method = "func_74734_a(Ljava/io/DataOutput;)V", at = @At("HEAD"), cancellable = true)
    private void teunloadbatch$writeRaw(DataOutput output, CallbackInfo ci) throws IOException {
        byte[] raw = this.teunloadbatch$raw;
        if (raw != null) {
            output.write(raw);
            ci.cancel();
        }
    }

    @Override
    public void teunloadbatch$setRaw(byte[] body) {
        this.teunloadbatch$raw = body;
    }

    @Override
    public void teunloadbatch$writeBody(DataOutput out) throws IOException {
        this.func_74734_a(out);
    }
}
