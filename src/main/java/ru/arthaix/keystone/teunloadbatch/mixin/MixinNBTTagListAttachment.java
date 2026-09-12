package ru.arthaix.keystone.teunloadbatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.nbt.NBTTagList;
import ru.arthaix.keystone.teunloadbatch.NbtAttachment;

@Mixin(value = NBTTagList.class, remap = false)
public abstract class MixinNBTTagListAttachment implements NbtAttachment {
    @Unique
    private volatile Object teunloadbatch$attachment;

    @Override
    public Object teunloadbatch$peekAttachment() {
        return this.teunloadbatch$attachment;
    }

    @Override
    public synchronized Object teunloadbatch$takeAttachment() {
        Object a = this.teunloadbatch$attachment;
        this.teunloadbatch$attachment = null;
        return a;
    }

    @Override
    public synchronized void teunloadbatch$setAttachment(Object attachment) {
        this.teunloadbatch$attachment = attachment;
    }
}
