package ru.arthaix.keystone.vfcompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * Item icons of Immersive Vehicles content packs whose pack ID has a character outside [A-Za-z0-9_.-] (for example
 * "miszkolights&amp;signs") showed as the missing texture when VintageFix's dynamic resources were on.
 *
 * Immersive Vehicles registers every pack item as "mts:&lt;pack id&gt;.&lt;name&gt;" and serves its item model from the domain
 * "mts_packs" as {"parent":"mts:item/basic","textures":{"layer0":"&lt;pack id&gt;:textures/items/..."}}. VintageFix does
 * not let vanilla walk the models for their textures; it reads each model's JSON and picks the texture names out with
 * a pattern that allows only [A-Za-z0-9_.-] before the colon, so a pack ID with any other character never matches, the
 * icon is never put into the atlas, and the baked item model gets the missing sprite ("Texture ... was not discovered
 * during texture pass").
 *
 * Before the block atlas is stitched, the model JSON of every such item is read through the resource manager, and the
 * textures it names in that pack's namespace are registered with the atlas. The images come from Immersive Vehicles'
 * own pack resources, exactly as for a pack with an ordinary ID. Packs VintageFix handles are not touched, and without
 * VintageFix the registration only repeats what the model loader does anyway.
 */
public final class PackIconSprites {
    private static volatile int registered;

    /** Client, TextureStitchEvent.Pre of every atlas. */
    @SubscribeEvent
    public void onStitch(TextureStitchEvent.Pre event) {
        if (event.getMap() != Minecraft.func_71410_x().func_147117_R()) {
            // atlases of other mods
            return;
        }
        int count = 0;
        List<String> failed = new ArrayList<String>();
        try {
            for (ResourceLocation item : ForgeRegistries.ITEMS.getKeys()) {
                String pack = PackIconNames.packOf(item.func_110624_b(), item.func_110623_a());
                if (pack == null || PackIconNames.vintageFixReads(pack)) {
                    continue;
                }
                String json = read(new ResourceLocation(PackIconNames.MODEL_DOMAIN, PackIconNames.modelPath(item.func_110623_a())));
                if (json == null) {
                    if (failed.size() < 3) {
                        failed.add(item.toString());
                    }
                    continue;
                }
                for (String texture : PackIconNames.texturesIn(json, pack)) {
                    event.getMap().func_174942_a(new ResourceLocation(pack, texture));
                    count++;
                }
            }
        } catch (Throwable t) {
            System.out.println("[keystone] pack item icons: " + t);
        }
        registered = count;
        if (count > 0 || !failed.isEmpty()) {
            System.out.println("[keystone] registered " + count + " item icon textures of Immersive Vehicles packs with unusual IDs"
                + (failed.isEmpty() ? "" : "; no model for " + failed));
        }
    }

    private static String read(ResourceLocation location) {
        try {
            IResource resource = Minecraft.func_71410_x().func_110442_L().func_110536_a(location);
            try (InputStream in = resource.func_110527_b()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bytes.write(buf, 0, n);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                resource.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static int registered() {
        return registered;
    }
}
