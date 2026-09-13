package ru.arthaix.keystone.chunkkeep.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = NetworkManager.class, remap = false)
public interface NetworkManagerAccessor {
    @Accessor(value = "field_150746_k", remap = false)
    Channel chunkkeep$channel();
}
