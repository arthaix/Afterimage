package ru.arthaix.keystone.teunloadbatch.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.BufferBuilder;

@Mixin(value = BufferBuilder.class, remap = false)
public interface BufferBuilderAccessor {
    @Accessor("field_179001_a")
    ByteBuffer teunloadbatch$byteBuffer();
}
