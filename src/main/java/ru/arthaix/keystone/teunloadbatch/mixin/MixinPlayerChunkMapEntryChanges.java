package ru.arthaix.keystone.teunloadbatch.mixin;

import java.util.Arrays;

import net.minecraft.server.management.PlayerChunkMapEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.teunloadbatch.EditStats;

/**
 * PlayerChunkMapEntry.blockChanged without the quadratic duplicate check.
 *
 * Forge removed vanilla's limit of 64 recorded block changes per chunk per tick but kept the loop that compares a new
 * change with every change recorded so far, so a tick that changes n blocks of one chunk (a WorldEdit edit) costs about
 * n²/2 comparisons: 16% of the server time of large edits. Up to EditStats.LINEAR_CHANGES recorded changes the original
 * code runs unchanged. Past that, a 65536-bit set of the recorded positions answers the check, and the change is
 * recorded exactly as Forge does it (section bit, same order, same duplicates skipped, same array growth).
 *
 * The set is rebuilt from the array whenever the count differs from the one it last left, so anything else changing the
 * entry's changes (sending them resets the count to 0) only costs a rebuild; it is dropped when the count is 0.
 */
@Mixin(value = PlayerChunkMapEntry.class, remap = false)
public abstract class MixinPlayerChunkMapEntryChanges {
    /** sentToPlayers */
    @Shadow private boolean field_187290_j;
    /** changes */
    @Shadow private int field_187287_g;
    /** changedSectionFilter */
    @Shadow private int field_187288_h;
    /** changedBlocks */
    @Shadow private short[] field_187285_e;

    @Unique private long[] teunloadbatch$recorded;
    @Unique private int teunloadbatch$recordedCount;

    @Inject(method = "func_187265_a(III)V", at = @At("HEAD"), cancellable = true)
    private void teunloadbatch$blockChanged(int x, int y, int z, CallbackInfo ci) {
        int n = this.field_187287_g;
        if (n < EditStats.LINEAR_CHANGES || !this.field_187290_j || !EditStats.DEDUP_CHANGES) {
            if (n == 0) {
                this.teunloadbatch$recorded = null;
            }
            return;
        }
        short[] changed = this.field_187285_e;
        long[] seen = this.teunloadbatch$recorded;
        if (seen == null || this.teunloadbatch$recordedCount != n) {
            if (seen == null) {
                seen = new long[1024];
                EditStats.changeSets++;
            } else {
                Arrays.fill(seen, 0L);
            }
            for (int i = 0; i < n; i++) {
                int k = changed[i] & 0xFFFF;
                seen[k >>> 6] |= 1L << k;
            }
            this.teunloadbatch$recorded = seen;
        }
        ci.cancel();
        // n > 0, so Forge's entryChanged call for the first change does not apply
        this.field_187288_h |= 1 << (y >> 4);
        short s = (short) (x << 12 | z << 8 | y);
        int k = s & 0xFFFF;
        long bit = 1L << k;
        if ((seen[k >>> 6] & bit) != 0L) {
            this.teunloadbatch$recordedCount = n;
            EditStats.changesDeduped++;
            return;
        }
        seen[k >>> 6] |= bit;
        if (n == changed.length) {
            changed = Arrays.copyOf(changed, changed.length << 1);
            this.field_187285_e = changed;
        }
        changed[n] = s;
        this.field_187287_g = n + 1;
        this.teunloadbatch$recordedCount = n + 1;
    }
}
