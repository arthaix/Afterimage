package ru.arthaix.keystone.teunloadbatch.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import ru.arthaix.keystone.teunloadbatch.ClientThread;

/**
 * Chunk.getTileEntity(pos, mode) (func_177424_a) called on a client world from a thread other than the client thread
 * only reads the chunk's tile entity map: no creation, no removal of invalid entries. A missing tile entity that was
 * asked for with IMMEDIATE is created on the client thread instead (ClientThread).
 */
@Mixin(value = Chunk.class, remap = false)
public abstract class MixinChunkTileAccess {
    @Shadow
    @Final
    private World field_76637_e;

    @Shadow
    @Final
    private Map<BlockPos, TileEntity> field_150816_i;

    @Inject(method = "func_177424_a", at = @At("HEAD"), cancellable = true)
    private void teunloadbatch$readOnlyOffThread(BlockPos pos, Chunk.EnumCreateEntityType mode, CallbackInfoReturnable<TileEntity> cir) {
        if (!this.field_76637_e.field_72995_K || ClientThread.isClientThread()) {
            return;
        }
        TileEntity te = this.field_150816_i.get(pos);
        if (te != null && te.func_145837_r()) {
            te = null;
        }
        if (te == null && mode == Chunk.EnumCreateEntityType.IMMEDIATE) {
            ClientThread.createLater(this.field_76637_e, pos);
        }
        cir.setReturnValue(te);
    }
}
