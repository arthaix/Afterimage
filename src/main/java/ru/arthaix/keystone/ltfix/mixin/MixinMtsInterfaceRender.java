package ru.arthaix.keystone.ltfix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import ru.arthaix.keystone.ltfix.AsyncTextures;

/** Immersive Vehicles: textures it has not loaded yet are decoded off the client thread (AsyncTextures). */
@Mixin(targets = "mcinterface1122.InterfaceRender", remap = false)
public abstract class MixinMtsInterfaceRender {
    @Redirect(method = "bindTexture(Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;func_110577_a(Lnet/minecraft/util/ResourceLocation;)V"))
    private static void ltfix$asyncBind(TextureManager tm, ResourceLocation rl) {
        AsyncTextures.bind(tm, rl);
    }
}
