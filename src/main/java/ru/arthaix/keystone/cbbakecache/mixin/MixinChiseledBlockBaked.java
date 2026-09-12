package ru.arthaix.keystone.cbbakecache.mixin;

import mod.chiselsandbits.chiseledblock.data.VoxelBlob;
import mod.chiselsandbits.chiseledblock.data.VoxelBlobStateReference;
import mod.chiselsandbits.helpers.IStateRef;
import mod.chiselsandbits.render.chiseledblock.ChiselLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.arthaix.keystone.cbbakecache.FilteredBlobCache;

/**
 * Replaces the "get a fresh blob copy, then filter it in place" pairs inside
 * ChiseledBlockBaked with lookups into FilteredBlobCache. The constructor does
 * it for the block's own blob, processX/Y/ZFaces do it for each neighbour.
 */
@Mixin(targets = "mod.chiselsandbits.render.chiseledblock.ChiseledBlockBaked", remap = false)
public class MixinChiseledBlockBaked {
    private static final String CTOR = "<init>(ILmod/chiselsandbits/render/chiseledblock/ChiselLayer;Lmod/chiselsandbits/chiseledblock/data/VoxelBlobStateReference;Lmod/chiselsandbits/render/chiseledblock/ModelRenderState;Lnet/minecraft/client/renderer/vertex/VertexFormat;)V";
    private static final String FACES_X = "processXFaces(Lmod/chiselsandbits/chiseledblock/data/VoxelBlob;Lmod/chiselsandbits/chiseledblock/data/VoxelBlob$VisibleFace;Lmod/chiselsandbits/render/chiseledblock/ModelRenderState;Ljava/util/ArrayList;)V";
    private static final String FACES_Y = "processYFaces(Lmod/chiselsandbits/chiseledblock/data/VoxelBlob;Lmod/chiselsandbits/chiseledblock/data/VoxelBlob$VisibleFace;Lmod/chiselsandbits/render/chiseledblock/ModelRenderState;Ljava/util/ArrayList;)V";
    private static final String FACES_Z = "processZFaces(Lmod/chiselsandbits/chiseledblock/data/VoxelBlob;Lmod/chiselsandbits/chiseledblock/data/VoxelBlob$VisibleFace;Lmod/chiselsandbits/render/chiseledblock/ModelRenderState;Ljava/util/ArrayList;)V";

    @Shadow(remap = false)
    private ChiselLayer myLayer;

    /** Own blob in the constructor: cached filtered blob, null when empty for this layer. */
    @Redirect(method = CTOR, remap = false,
              at = @At(value = "INVOKE", remap = false,
                       target = "Lmod/chiselsandbits/chiseledblock/data/VoxelBlobStateReference;getVoxelBlob()Lmod/chiselsandbits/chiseledblock/data/VoxelBlob;"))
    private VoxelBlob cbbakecache$ownBlob(VoxelBlobStateReference ref) {
        return FilteredBlobCache.get(ref, this.myLayer);
    }

    /** Neighbour blobs in the face passes: cached filtered blob, null when empty. */
    @Redirect(method = {FACES_X, FACES_Y, FACES_Z}, remap = false,
              at = @At(value = "INVOKE", remap = false,
                       target = "Lmod/chiselsandbits/helpers/IStateRef;getVoxelBlob()Lmod/chiselsandbits/chiseledblock/data/VoxelBlob;"))
    private VoxelBlob cbbakecache$neighbourBlob(IStateRef ref) {
        return FilteredBlobCache.get(ref, this.myLayer);
    }

    /** The blob is already filtered by the cache, so "anything left" is just non-null. */
    @Redirect(method = {CTOR, FACES_X, FACES_Y, FACES_Z}, remap = false,
              at = @At(value = "INVOKE", remap = false,
                       target = "Lmod/chiselsandbits/render/chiseledblock/ChiselLayer;filter(Lmod/chiselsandbits/chiseledblock/data/VoxelBlob;)Z"))
    private boolean cbbakecache$alreadyFiltered(ChiselLayer layer, VoxelBlob blob) {
        return blob != null;
    }
}
