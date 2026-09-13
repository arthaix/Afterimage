package ru.arthaix.keystone.chunkkeep.mixin;

import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.chunkkeep.Config;
import ru.arthaix.keystone.chunkkeep.SendBacklog;

/**
 * Time budget for chunk sending in PlayerChunkMap.tick().
 *
 * Vanilla budgets chunk loading (50 ms) but sends up to 81 chunk packets per tick with no time limit. Building a
 * packet for a dense LittleTiles / Chisels & Bits chunk serializes and compresses every tile entity, so flying over the
 * city produced runs of 300-700 ms ticks and "Can't keep up" of several seconds. Once the budget of a tick is spent,
 * sendToPlayers() reports "not sent", which is vanilla's own path for a chunk that is not ready: the entry stays in
 * pendingSendToPlayers and goes out on a following tick. At least one chunk is always sent per tick.
 */
@Mixin(value = PlayerChunkMap.class, remap = false)
public abstract class MixinPlayerChunkMapSend {
    @Unique private long chunkkeep$sendDeadline;
    @Unique private boolean chunkkeep$sentOne;

    @Inject(method = "func_72693_b()V", at = @At("HEAD"))
    private void chunkkeep$startTick(CallbackInfo ci) {
        this.chunkkeep$sendDeadline = System.nanoTime() + Config.SEND_BUDGET_NANOS;
        this.chunkkeep$sentOne = false;
    }

    /** tick() cleared its dirty set: entries whose whole-chunk re-send was postponed go back on it. */
    @Inject(method = "func_72693_b()V", at = @At("RETURN"))
    private void chunkkeep$readdDeferred(CallbackInfo ci) {
        SendBacklog.readdDeferred(((PlayerChunkMapAccessor) this).chunkkeep$dirtyEntries());
    }

    /** Both sendToPlayers() calls in tick(): after chunk load and in the pending-send loop. */
    @Redirect(method = "func_72693_b()V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/server/management/PlayerChunkMapEntry;func_187272_b()Z"))
    private boolean chunkkeep$sendBudgeted(PlayerChunkMapEntry entry) {
        if (this.chunkkeep$sentOne && Config.SEND_BUDGET_NANOS > 0 && System.nanoTime() > this.chunkkeep$sendDeadline) {
            return false;
        }
        if (SendBacklog.full(((PlayerChunkMapEntryAccessor) entry).chunkkeep$players())) {
            // the player's connection still has more than the backlog limit waiting to be encoded
            return false;
        }
        boolean sent = entry.func_187272_b();
        if (sent) {
            this.chunkkeep$sentOne = true;
        }
        return sent;
    }
}
