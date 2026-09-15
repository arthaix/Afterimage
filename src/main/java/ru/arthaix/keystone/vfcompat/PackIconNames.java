package ru.arthaix.keystone.vfcompat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Names behind PackIconSprites, plain strings without Minecraft classes (unit-tested). */
public final class PackIconNames {
    /** Mod id Immersive Vehicles registers its pack items under */
    public static final String ITEM_DOMAIN = "mts";
    /** Resource domain Immersive Vehicles serves the pack item models from */
    public static final String MODEL_DOMAIN = "mts_packs";
    /** VintageFix's namespace class for texture names in model JSON */
    private static final Pattern PLAIN_NAMESPACE = Pattern.compile("[A-Za-z0-9_\\-.]+");
    /** "namespace:path" string values, any namespace without quotes, colons or whitespace */
    private static final Pattern TEXTURE_VALUE = Pattern.compile("\"([^\":\\s]+):([^\"\\s]+)\"");

    private PackIconNames() {
    }

    /** Item "mts:&lt;pack&gt;.&lt;name&gt;" -&gt; "&lt;pack&gt;", anything else -&gt; null. */
    public static String packOf(String itemDomain, String itemPath) {
        if (!ITEM_DOMAIN.equals(itemDomain)) {
            return null;
        }
        int dot = itemPath.indexOf('.');
        return dot > 0 ? itemPath.substring(0, dot) : null;
    }

    /** Whether VintageFix finds texture names in this namespace by itself. */
    public static boolean vintageFixReads(String namespace) {
        return PLAIN_NAMESPACE.matcher(namespace).matches();
    }

    /** Path (in MODEL_DOMAIN) of the item model Immersive Vehicles serves for a pack item. */
    public static String modelPath(String itemPath) {
        return "models/item/" + itemPath + ".json";
    }

    /** Texture paths in the given namespace among the string values of a model JSON, in order, without repeats. */
    public static List<String> texturesIn(String json, String namespace) {
        Set<String> seen = new LinkedHashSet<String>();
        List<String> out = new ArrayList<String>();
        Matcher m = TEXTURE_VALUE.matcher(json);
        while (m.find()) {
            if (namespace.equals(m.group(1)) && seen.add(m.group(2))) {
                out.add(m.group(2));
            }
        }
        return out;
    }
}
