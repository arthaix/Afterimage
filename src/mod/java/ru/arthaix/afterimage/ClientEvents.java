package ru.arthaix.afterimage;

import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Client Forge events. Registered on the client only; these event classes do not exist on a dedicated server. */
public final class ClientEvents {
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
