package ru.arthaix.keystone.teunloadbatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.tileentity.TileEntity;
import ru.arthaix.keystone.teunloadbatch.TagCache;
import ru.arthaix.keystone.teunloadbatch.TagCacheHolder;

/** Holds a tile entity's cached update tag bytes (TagCache) and voids them on every vanilla change point. */
@Mixin(value = TileEntity.class, remap = false)
public abstract class MixinTileEntityTags implements TagCacheHolder {
    @Unique
    private int teunloadbatch$tagVersion;

    @Unique
    private byte[] teunloadbatch$tagBody;

    @Unique
    private int teunloadbatch$tagBodyVersion;

    @Override
    public int teunloadbatch$tagVersion() {
        return this.teunloadbatch$tagVersion;
    }

    @Override
    public byte[] teunloadbatch$tagBody() {
        return this.teunloadbatch$tagBody;
    }

    @Override
    public int teunloadbatch$tagBodyVersion() {
        return this.teunloadbatch$tagBodyVersion;
    }

    @Override
    public void teunloadbatch$setTagBody(byte[] body, int version) {
        this.teunloadbatch$tagBody = body;
        this.teunloadbatch$tagBodyVersion = version;
    }

    @Override
    public byte[] teunloadbatch$bumpTagVersion() {
        this.teunloadbatch$tagVersion++;
        byte[] body = this.teunloadbatch$tagBody;
        this.teunloadbatch$tagBody = null;
        return body;
    }

    @Inject(method = "func_70296_d()V", at = @At("HEAD"), require = 0)
    private void teunloadbatch$markDirty(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }

    @Inject(method = "func_145839_a(Lnet/minecraft/nbt/NBTTagCompound;)V", at = @At("RETURN"), require = 0)
    private void teunloadbatch$read(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }

    @Inject(method = "func_174878_a(Lnet/minecraft/util/math/BlockPos;)V", at = @At("HEAD"), require = 0)
    private void teunloadbatch$setPos(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }

    @Inject(method = "func_145834_a(Lnet/minecraft/world/World;)V", at = @At("HEAD"), require = 0)
    private void teunloadbatch$setWorld(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }

    @Inject(method = "func_190201_b(Lnet/minecraft/world/World;)V", at = @At("HEAD"), require = 0)
    private void teunloadbatch$setWorldCreate(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }

    @Inject(method = "func_145843_s()V", at = @At("HEAD"), require = 0)
    private void teunloadbatch$invalidate(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }

    @Inject(method = "func_145829_t()V", at = @At("HEAD"), require = 0)
    private void teunloadbatch$validate(CallbackInfo ci) {
        TagCache.bump((TileEntity) (Object) this);
    }
}
