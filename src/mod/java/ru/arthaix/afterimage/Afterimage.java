package ru.arthaix.afterimage;

import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Afterimage (city imprint), phase 0: capture + verify + measure section geometry.
 * Client only, read-only. Chat: /afterimage, /afterimage csv, /afterimage off, /afterimage on.
 * Compiled against the Forge dev jar and only calls Capture through plain Java types.
 */
@Mod(modid = Afterimage.MODID, name = "Afterimage", version = Afterimage.VERSION,
     dependencies = "required-after:mixinbooter@[10.0,)", acceptableRemoteVersions = "*", clientSideOnly = true)
public class Afterimage {
    public static final String MODID = "afterimage";
    public static final String VERSION = "0.3.2";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Capture.init(event.getModConfigurationDirectory().getParentFile());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Capture.tick();
        }
    }

    @SubscribeEvent
    public void onFog(EntityViewRenderEvent.RenderFogEvent event) {
        Far.onFog(event.getEntity(), event.getState(), event.getFogMode(), event.getFarPlaneDistance());
    }

    @SubscribeEvent
    public void onChat(ClientChatEvent event) {
        if (Capture.onChat(event.getMessage())) {
            event.setCanceled(true);
        }
    }
}
