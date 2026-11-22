package IGoByJake.salvagefishing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which liquids start a dive, and what's down there: {@code data/<ns>/salvagefishing_pools/<name>.json}
 * <pre>{ "fluid": "#minecraft:water", "loot_table": "minecraft:gameplay/fishing", "theme": "water" }</pre>
 * Floor treasure: one item rests in the middle of the floor, rolled from {@code "floor_loot_table"} if the pool names
 * one ({@code "minecraft:empty"} for none), else from {@code <pool namespace>:floor/<pool name>} (e.g.
 * {@code salvagefishing:floor/water}) if a pack adds it, else from {@code salvagefishing:floor/default} (a gold block).
 * Salvage Fishing ships only the default, so packs add per-pool tables without overriding any of its files.
 * Optional: {@code "hook"} (only bobbers of this entity type, e.g. a mod's lava bobber), {@code "direct": true}
 * (catches go straight to the inventory instead of flying out, for liquids that would burn them).
 * If {@code <loot_table>/<dimension namespace>/<dimension path>} exists it is used in that dimension instead.
 * Themes: {@code water}, {@code lava}. Any theme's colours can be set with {@code "colors"}: an object with any of
 * {@code sky, top, mid, bottom, foam, light, deep, bubble} as {@code "#RRGGBB"} or {@code "#AARRGGBB"} (top/mid/bottom
 * are the water from surface to floor; foam/light/deep are the waves; the rock is always a dark silhouette).
 */
public final class DivePools {
    private static final Logger LOG = LogUtils.getLogger();

    public record Pool(@Nullable TagKey<Fluid> tag, @Nullable Fluid fluid, ResourceLocation table, String theme,
                       @Nullable ResourceLocation hook, boolean direct, int[] colors, ResourceLocation floorTable, boolean floorNamed) {
        boolean matches(FluidState state, FishingHook entity) {
            if (state.isEmpty()) return false;
            if (hook != null && !hook.equals(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()))) return false;
            return tag != null ? state.is(tag) : state.getType().isSame(fluid);
        }
    }

    /** The colour keys, in the order they're sent to the client. */
    public static final String[] COLOR_KEYS = {"sky", "top", "mid", "bottom", "foam", "light", "deep", "bubble"};

    private static List<Pool> pools = List.of();

    private DivePools() {
    }

    /** The pool for a bobber sitting in this fluid, or null. Pools with a hook type win over general ones. */
    @Nullable
    public static Pool find(FluidState state, FishingHook hook) {
        Pool any = null;
        for (Pool p : pools) {
            if (!p.matches(state, hook)) continue;
            if (p.hook() != null) return p;
            if (any == null) any = p;
        }
        return any;
    }

    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Listener());
    }

    private static final class Listener extends SimpleJsonResourceReloadListener {
        Listener() {
            super(new com.google.gson.Gson(), "salvagefishing_pools");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            List<Pool> out = new ArrayList<>();
            files.forEach((id, json) -> {
                try {
                    JsonObject o = json.getAsJsonObject();
                    String f = GsonHelper.getAsString(o, "fluid");
                    TagKey<Fluid> tag = null;
                    Fluid fluid = null;
                    if (f.startsWith("#")) tag = TagKey.create(Registries.FLUID, new ResourceLocation(f.substring(1)));
                    else fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(f));
                    if (tag == null && fluid == null) return;
                    ResourceLocation hook = o.has("hook") ? new ResourceLocation(GsonHelper.getAsString(o, "hook")) : null;
                    int[] colors = new int[COLOR_KEYS.length]; // 0 = theme's own
                    if (o.has("colors")) {
                        JsonObject c = GsonHelper.getAsJsonObject(o, "colors");
                        for (int i = 0; i < COLOR_KEYS.length; i++) {
                            if (!c.has(COLOR_KEYS[i])) continue;
                            String hex = GsonHelper.getAsString(c, COLOR_KEYS[i]).replace("#", "");
                            long v = Long.parseLong(hex, 16);
                            colors[i] = (int) (hex.length() <= 6 ? v | 0xFF000000L : v);
                        }
                    }
                    ResourceLocation floor = o.has("floor_loot_table") ? new ResourceLocation(GsonHelper.getAsString(o, "floor_loot_table"))
                            : new ResourceLocation(id.getNamespace(), "floor/" + id.getPath());
                    out.add(new Pool(tag, fluid, new ResourceLocation(GsonHelper.getAsString(o, "loot_table")),
                            GsonHelper.getAsString(o, "theme", "water"), hook, GsonHelper.getAsBoolean(o, "direct", false), colors, floor, o.has("floor_loot_table")));
                } catch (RuntimeException ex) {
                    LOG.error("Bad dive pool {}: {}", id, ex.getMessage());
                }
            });
            pools = List.copyOf(out);
        }
    }
}
