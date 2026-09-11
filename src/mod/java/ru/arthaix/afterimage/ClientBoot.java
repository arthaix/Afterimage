package ru.arthaix.afterimage;

import java.io.File;

import net.minecraftforge.common.MinecraftForge;

/** Client-only start-up, called from the mod class on the client side only, so a dedicated server never loads it. */
public final class ClientBoot {
    private ClientBoot() {
    }

    public static void init(File gameDir) {
        Capture.init(gameDir);
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
    }
}
