package ru.arthaix.keystone.irfar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.play.client.CPacketClientSettings;

@Mixin(value = CPacketClientSettings.class, remap = false)
public interface CPacketClientSettingsAccessor {
    /** view: render distance in chunks */
    @Accessor("field_149528_b")
    int irfar$view();
}
