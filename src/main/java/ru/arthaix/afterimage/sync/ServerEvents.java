package ru.arthaix.afterimage.sync;

import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Forge events of the server part (dedicated and integrated server). Uses no client classes. */
public final class ServerEvents {
    private int ticks;

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World w = event.getWorld();
        if (!w.field_72995_K && ChangeTracker.dimension(w) == 0) {
            ChangeTracker.start(DimensionManager.getCurrentSaveRootDirectory());
        }
    }

    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (!event.getWorld().field_72995_K) {
            ChangeTracker.save();
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            Net.sendHello((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            Net.sendHello((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ChangeTracker.tickStart();
            return;
        }
        if (++this.ticks % 20 != 0) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.func_71218_a(0) == null) {
            return;
        }
        long now = server.func_71218_a(0).func_82737_E();
        if (this.ticks % 1200 == 0) {
            ChangeTracker.prune(now);
        }
        Map<Integer, long[]> pending = ChangeTracker.drainPending();
        if (pending.isEmpty()) {
            return;
        }
        for (EntityPlayerMP p : server.func_184103_al().func_181057_v()) {
            long[] pairs = pending.get(p.field_71093_bK);
            if (pairs != null && Net.hasAfterimage(p)) {
                Net.sendChanges(p, p.field_71093_bK, pairs, false, now);
            }
        }
    }
}
