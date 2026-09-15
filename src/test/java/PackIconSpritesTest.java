import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.arthaix.keystone.vfcompat.PackIconNames;

/**
 * PackIconNames against the item model Immersive Vehicles 22.5 generates, and against the pattern VintageFix 0.6.2 uses to
 * find texture names in model JSON: packs VintageFix reads are skipped, packs it cannot read get their icon.
 */
public class PackIconSpritesTest {
    private static final Pattern VINTAGEFIX = Pattern.compile("\"(?:([A-Za-z0-9_\\-.]+):|)([A-za-z0-9_\\-./]+)\"");

    private static String model(String texture) {
        return "{\"parent\":\"mts:item/basic\",\"textures\":{\"layer0\": \"" + texture + "\"}}";
    }

    private static boolean vintageFixFinds(String json, String texture) {
        Matcher m = VINTAGEFIX.matcher(json);
        while (m.find()) {
            if ((m.group(1) + ":" + m.group(2)).equals(texture)) {
                return true;
            }
        }
        return false;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    public static void main(String[] args) {
        check("miszkolights&signs".equals(PackIconNames.packOf("mts", "miszkolights&signs.stop")), "pack id");
        check("miszko".equals(PackIconNames.packOf("mts", "miszko.aimassist")), "plain pack id");
        check(PackIconNames.packOf("minecraft", "stone") == null, "not an mts item");
        check(PackIconNames.packOf("mts", "wrench") == null, "mts core item without a pack");
        check(PackIconNames.modelPath("miszkolights&signs.stop").equals("models/item/miszkolights&signs.stop.json"), "model path");

        String odd = "miszkolights&signs:textures/items/poles/stop";
        String plain = "miszko:textures/items/parts/aimassist";
        check(!vintageFixFinds(model(odd), odd), "VintageFix's pattern must miss the & pack (the bug)");
        check(vintageFixFinds(model(plain), plain), "VintageFix's pattern must find a plain pack");
        check(!PackIconNames.vintageFixReads("miszkolights&signs"), "& pack is handled here");
        check(PackIconNames.vintageFixReads("miszko"), "plain pack is left to VintageFix");

        List<String> found = PackIconNames.texturesIn(model(odd), "miszkolights&signs");
        check(found.equals(Arrays.asList("textures/items/poles/stop")), "icon of the & pack: " + found);
        List<String> none = PackIconNames.texturesIn(model(odd), "miszko");
        check(none.isEmpty(), "other namespaces are not taken: " + none);
        String twice = "{\"textures\":{\"layer0\":\"a&b:textures/items/x\",\"layer1\":\"a&b:textures/items/x\",\"particle\":\"a&b:textures/items/y\"}}";
        List<String> dedup = PackIconNames.texturesIn(twice, "a&b");
        check(dedup.equals(Arrays.asList("textures/items/x", "textures/items/y")), "repeats collapsed: " + dedup);
        System.out.println("PackIconNames: pack IDs, model path, VintageFix gap and icon names as expected");
    }
}
