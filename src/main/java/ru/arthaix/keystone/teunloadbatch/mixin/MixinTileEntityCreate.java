package ru.arthaix.keystone.teunloadbatch.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.tileentity.TileEntity;
import ru.arthaix.keystone.teunloadbatch.TeConstructors;

/** TileEntity.create (func_190200_a): tile entity classes are instantiated through cached constructors. */
@Mixin(value = TileEntity.class, remap = false)
public abstract class MixinTileEntityCreate {
    @Redirect(method = "func_190200_a", at = @At(value = "INVOKE", target = "Ljava/lang/Class;newInstance()Ljava/lang/Object;"))
    private static Object teunloadbatch$cachedConstructor(Class<?> type) throws InstantiationException, IllegalAccessException {
        return TeConstructors.newInstance(type);
    }
}
