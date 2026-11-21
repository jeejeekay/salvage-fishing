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
 * Optional: {@code "hook"} (only bobbers of this entity type, e.g. a mod's lava bobber), {@code "direct": true}
 * (catches go straight to the inventory instead of flying out, for liquids that would burn them).
 * If {@code <loot_table>/<dimension namespace>/<dimension path>} exists it is used in that dimension instead.
 * Themes: {@code water}, {@code lava}.
 */
public final class DivePools {
    private static final Logger LOG = LogUtils.getLogger();

    public record Pool(@Nullable TagKey<Fluid> tag, @Nullable Fluid fluid, ResourceLocation table, String theme,
                       @Nullable ResourceLocation hook, boolean direct) {
        boolean matches(FluidState state, FishingHook entity) {
            if (state.isEmpty()) return false;
            if (hook != null && !hook.equals(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()))) return false;
            return tag != null ? state.is(tag) : state.getType().isSame(fluid);
        }
    }

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
                    out.add(new Pool(tag, fluid, new ResourceLocation(GsonHelper.getAsString(o, "loot_table")),
                            GsonHelper.getAsString(o, "theme", "water"), hook, GsonHelper.getAsBoolean(o, "direct", false)));
                } catch (RuntimeException ex) {
                    LOG.error("Bad dive pool {}: {}", id, ex.getMessage());
                }
            });
            pools = List.copyOf(out);
        }
    }
}
