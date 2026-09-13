package ru.arthaix.keystone.chunkkeep.mixin;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeModContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.chunkkeep.SendBacklog;

/**
 * PlayerChunkMapEntry.update: a chunk with more than ForgeModContainer.clumpingThreshold (64) changed blocks in a tick is
 * re-sent whole, tile entities included. A large edit (an import or its undo of tens of thousands of blocks) changes
 * the same chunks every tick for half a minute, so each of them went out again every tick: at 33 MB per chunk of an
 * imported model that was gigabytes queued to the player and a "Timed out" disconnect. A whole-chunk re-send now
 * happens at most every -Dchunkkeep.fullResendMs (1500) per chunk and never while the player's send backlog is full;
 * meanwhile the changes keep accumulating and the entry is put back on the dirty list at the end of the tick.
 */
@Mixin(value = PlayerChunkMapEntry.class, remap = false)
public abstract class MixinPlayerChunkMapEntryUpdate {
    private static final long FULL_RESEND_NANOS = Long.getLong("chunkkeep.fullResendMs", 1500L) * 1_000_000L;

    /** sentToPlayers */
    @Shadow
    private boolean field_187290_j;

    /** chunk */
    @Shadow
    private Chunk field_187286_f;

    /** changes */
    @Shadow
    private int field_187287_g;

    /** players */
    @Shadow
    @Final
    private List<EntityPlayerMP> field_187283_c;

    @Unique
    private long chunkkeep$lastFullSend;

    @Inject(method = "func_187280_d()V", at = @At("HEAD"), cancellable = true)
    private void chunkkeep$paceFullResend(CallbackInfo ci) {
        if (!this.field_187290_j || this.field_187286_f == null || this.field_187287_g < ForgeModContainer.clumpingThreshold) {
            return;
        }
        long now = System.nanoTime();
        if (now - this.chunkkeep$lastFullSend < FULL_RESEND_NANOS || SendBacklog.full(this.field_187283_c)) {
            SendBacklog.deferDirty((PlayerChunkMapEntry) (Object) this);
            ci.cancel();
            return;
        }
        this.chunkkeep$lastFullSend = now;
    }
}
