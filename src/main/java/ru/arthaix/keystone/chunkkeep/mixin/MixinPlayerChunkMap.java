package ru.arthaix.keystone.chunkkeep.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.arthaix.keystone.chunkkeep.Config;
import ru.arthaix.keystone.chunkkeep.HeapGuard;

/**
 * 1. Keeps a player registered on chunk entries that left view-distance but are still within Config.KEEP_RADIUS.
 *    "Kept" = entry where the player is registered but the chunk is outside the vanilla view square. Vanilla's own
 *    bookkeeping is untouched for everything inside the view square.
 * 2. Deferred sends: PlayerChunkMapEntry.addPlayer sends the chunk packet at once when the chunk was already sent to
 *    someone, and building a packet for a dense LittleTiles / Chisels & Bits chunk serializes every tile entity. A
 *    teleport or login therefore built up to 169 such packets inside one tick (200-420 ms ticks). Once
 *    Config.ENTER_BUDGET_NANOS of such sends is spent in a tick, the player is not added to further entries yet; the
 *    pending chunks are added (and sent) on following ticks within the same budget, nearest first. A pending chunk that
 *    leaves the view is simply forgotten: the player was never registered on it, so it is neither removed nor kept.
 *    Entries are looked up again by position when the add happens, so a pending chunk never refers to a dropped entry.
 */
@Mixin(value = PlayerChunkMap.class, remap = false)
public abstract class MixinPlayerChunkMap {
    @Shadow
    @Final
    private WorldServer field_72701_a;

    @Shadow
    private int field_72698_e;

    /** getOrCreateEntry */
    @Shadow
    private PlayerChunkMapEntry func_187302_c(int x, int z) {
        throw new AssertionError();
    }

    @Unique private Map<EntityPlayerMP, Set<PlayerChunkMapEntry>> chunkkeep$kept;
    @Unique private Map<EntityPlayerMP, LinkedHashSet<Long>> chunkkeep$pending;
    @Unique private int chunkkeep$ticks;
    @Unique private int chunkkeep$enterTick = Integer.MIN_VALUE;
    @Unique private long chunkkeep$enterSpent;

    @Unique
    private Set<PlayerChunkMapEntry> chunkkeep$setFor(EntityPlayerMP player) {
        if (this.chunkkeep$kept == null) {
            this.chunkkeep$kept = new IdentityHashMap<EntityPlayerMP, Set<PlayerChunkMapEntry>>();
        }
        Set<PlayerChunkMapEntry> set = this.chunkkeep$kept.get(player);
        if (set == null) {
            set = Collections.newSetFromMap(new IdentityHashMap<PlayerChunkMapEntry, Boolean>());
            this.chunkkeep$kept.put(player, set);
        }
        return set;
    }

    @Unique
    private static int chunkkeep$distance(PlayerChunkMapEntry entry, EntityPlayerMP player) {
        ChunkPos pos = entry.func_187264_a();
        int dx = Math.abs(pos.field_77276_a - (((int) player.field_70165_t) >> 4));
        int dz = Math.abs(pos.field_77275_b - (((int) player.field_70161_v) >> 4));
        return Math.max(dx, dz);
    }

    @Unique
    private static long chunkkeep$key(PlayerChunkMapEntry entry) {
        ChunkPos pos = entry.func_187264_a();
        return ChunkPos.func_77272_a(pos.field_77276_a, pos.field_77275_b);
    }

    /** Registers the player on the entry now, or defers it when this tick's budget for immediate sends is spent. */
    @Unique
    private void chunkkeep$addOrDefer(PlayerChunkMapEntry entry, EntityPlayerMP player) {
        if (Config.ENTER_BUDGET_NANOS <= 0L || !entry.func_187274_e()) {
            // nothing is sent now for an entry not yet sent to anyone: the budgeted tick sends it
            entry.func_187276_a(player);
            return;
        }
        int tick = this.field_72701_a.func_73046_m().func_71259_af();
        if (tick != this.chunkkeep$enterTick) {
            this.chunkkeep$enterTick = tick;
            this.chunkkeep$enterSpent = 0L;
        }
        if (this.chunkkeep$enterSpent < Config.ENTER_BUDGET_NANOS) {
            long start = System.nanoTime();
            entry.func_187276_a(player);
            this.chunkkeep$enterSpent += System.nanoTime() - start;
            return;
        }
        if (this.chunkkeep$pending == null) {
            this.chunkkeep$pending = new IdentityHashMap<EntityPlayerMP, LinkedHashSet<Long>>();
        }
        LinkedHashSet<Long> set = this.chunkkeep$pending.get(player);
        if (set == null) {
            set = new LinkedHashSet<Long>();
            this.chunkkeep$pending.put(player, set);
        }
        set.add(chunkkeep$key(entry));
    }

    /** updateMovingPlayer: chunk leaving the view square -> keep it if within radius. */
    @Redirect(method = "func_72685_d(Lnet/minecraft/entity/player/EntityPlayerMP;)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/server/management/PlayerChunkMapEntry;func_187277_b(Lnet/minecraft/entity/player/EntityPlayerMP;)V"))
    private void chunkkeep$leaveView(PlayerChunkMapEntry entry, EntityPlayerMP player) {
        Map<EntityPlayerMP, LinkedHashSet<Long>> pending = this.chunkkeep$pending;
        if (pending != null) {
            LinkedHashSet<Long> set = pending.get(player);
            if (set != null && set.remove(chunkkeep$key(entry))) {
                // never registered on it: nothing to remove, and it must not be marked as kept
                return;
            }
        }
        if (chunkkeep$distance(entry, player) <= Config.KEEP_RADIUS) {
            chunkkeep$setFor(player).add(entry);
        } else {
            entry.func_187277_b(player);
        }
    }

    /** updateMovingPlayer: chunk entering the view square -> if we kept it, the player is still registered. */
    @Redirect(method = "func_72685_d(Lnet/minecraft/entity/player/EntityPlayerMP;)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/server/management/PlayerChunkMapEntry;func_187276_a(Lnet/minecraft/entity/player/EntityPlayerMP;)V"))
    private void chunkkeep$enterView(PlayerChunkMapEntry entry, EntityPlayerMP player) {
        Map<EntityPlayerMP, Set<PlayerChunkMapEntry>> kept = this.chunkkeep$kept;
        if (kept != null) {
            Set<PlayerChunkMapEntry> set = kept.get(player);
            if (set != null && set.remove(entry)) {
                return;
            }
        }
        chunkkeep$addOrDefer(entry, player);
    }

    /** addPlayer (login, dimension change): the whole view square at once. */
    @Redirect(method = "func_72683_a(Lnet/minecraft/entity/player/EntityPlayerMP;)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/server/management/PlayerChunkMapEntry;func_187276_a(Lnet/minecraft/entity/player/EntityPlayerMP;)V"))
    private void chunkkeep$loginView(PlayerChunkMapEntry entry, EntityPlayerMP player) {
        chunkkeep$addOrDefer(entry, player);
    }

    /** removePlayer (logout / dimension change): release everything we kept and forget pending adds for that player. */
    @Inject(method = "func_72695_c(Lnet/minecraft/entity/player/EntityPlayerMP;)V", at = @At("HEAD"))
    private void chunkkeep$onRemovePlayer(EntityPlayerMP player, CallbackInfo ci) {
        if (this.chunkkeep$pending != null) {
            this.chunkkeep$pending.remove(player);
        }
        Map<EntityPlayerMP, Set<PlayerChunkMapEntry>> kept = this.chunkkeep$kept;
        if (kept == null) {
            return;
        }
        Set<PlayerChunkMapEntry> set = kept.remove(player);
        if (set != null) {
            for (PlayerChunkMapEntry entry : set) {
                entry.func_187277_b(player);
            }
            set.clear();
        }
    }

    /** tick: pending adds within the budget, nearest chunks first. */
    @Inject(method = "func_72693_b()V", at = @At("HEAD"))
    private void chunkkeep$addPending(CallbackInfo ci) {
        Map<EntityPlayerMP, LinkedHashSet<Long>> pending = this.chunkkeep$pending;
        if (pending == null || pending.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + Config.ENTER_BUDGET_NANOS;
        boolean addedOne = false;
        for (Iterator<Map.Entry<EntityPlayerMP, LinkedHashSet<Long>>> pit = pending.entrySet().iterator(); pit.hasNext();) {
            Map.Entry<EntityPlayerMP, LinkedHashSet<Long>> pe = pit.next();
            EntityPlayerMP player = pe.getKey();
            LinkedHashSet<Long> set = pe.getValue();
            final int px = ((int) player.field_71131_d) >> 4;
            final int pz = ((int) player.field_71132_e) >> 4;
            List<Long> order = new ArrayList<Long>(set);
            order.sort((a, b) -> Integer.compare(chunkkeep$ring(a, px, pz), chunkkeep$ring(b, px, pz)));
            for (Long key : order) {
                int cx = (int) (long) key;
                int cz = (int) (key >>> 32);
                if (Math.abs(cx - px) > this.field_72698_e || Math.abs(cz - pz) > this.field_72698_e) {
                    set.remove(key);
                    continue;
                }
                if (addedOne && System.nanoTime() > deadline) {
                    return;
                }
                PlayerChunkMapEntry entry = this.func_187302_c(cx, cz);
                if (!entry.func_187275_d(player)) {
                    entry.func_187276_a(player);
                }
                set.remove(key);
                addedOne = true;
            }
            if (set.isEmpty()) {
                pit.remove();
            }
        }
    }

    @Unique
    private static int chunkkeep$ring(long key, int px, int pz) {
        return Math.max(Math.abs((int) key - px), Math.abs((int) (key >>> 32) - pz));
    }

    /** tick: periodic sweep by distance, plus the heap guard. */
    @Inject(method = "func_72693_b()V", at = @At("HEAD"))
    private void chunkkeep$sweep(CallbackInfo ci) {
        Map<EntityPlayerMP, Set<PlayerChunkMapEntry>> kept = this.chunkkeep$kept;
        if (kept == null || kept.isEmpty() || ++this.chunkkeep$ticks < Config.SWEEP_TICKS) {
            return;
        }
        this.chunkkeep$ticks = 0;
        boolean heapPressure = HeapGuard.pressure();
        for (Map.Entry<EntityPlayerMP, Set<PlayerChunkMapEntry>> e : kept.entrySet()) {
            EntityPlayerMP player = e.getKey();
            Set<PlayerChunkMapEntry> set = e.getValue();
            if (set.isEmpty()) {
                continue;
            }
            List<PlayerChunkMapEntry> release = new ArrayList<PlayerChunkMapEntry>();
            for (PlayerChunkMapEntry entry : set) {
                if (chunkkeep$distance(entry, player) > Config.KEEP_RADIUS) {
                    release.add(entry);
                }
            }
            if (heapPressure) {
                // Release the farthest half of what remains.
                List<PlayerChunkMapEntry> rest = new ArrayList<PlayerChunkMapEntry>(set);
                rest.removeAll(release);
                final EntityPlayerMP p = player;
                Collections.sort(rest, (a, b) -> chunkkeep$distance(b, p) - chunkkeep$distance(a, p));
                release.addAll(rest.subList(0, rest.size() / 2));
            }
            for (PlayerChunkMapEntry entry : release) {
                set.remove(entry);
                entry.func_187277_b(player);
            }
        }
        for (Iterator<Set<PlayerChunkMapEntry>> it = kept.values().iterator(); it.hasNext();) {
            if (it.next().isEmpty()) {
                it.remove();
            }
        }
    }
}
