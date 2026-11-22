package IGoByJake.salvagefishing;

import IGoByJake.salvagefishing.net.DiveNetwork;
import IGoByJake.salvagefishing.net.EndDivePacket;
import IGoByJake.salvagefishing.net.StartDivePacket;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemFishedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The server half of a dive. When a player's bobber settles in a liquid that has a {@link DivePools pool}, the
 * shaft is filled from that pool's loot table and sent to the player. The client plays it and reports back what
 * the hook touched; the server checks the report and hands out the catch the way vanilla fishing does
 * (ItemFishedEvent, the fishing advancement trigger, XP, a fish stat, one point of rod wear).
 *
 * <h2>Filling the shaft</h2>
 * The loot table (with every loot modifier, as normal fishing) is rolled {@link #SAMPLES} times with a stand-in
 * bobber. Each item's share of those rolls is its weight. At depth fraction d a weight w becomes
 * w^(1 - 1.6 d): normal at the top, all equal a bit past halfway, rare things favoured at the bottom.
 *
 * <h2>The catch</h2>
 * The snagged stack is passed through the loot modifiers once more under the table id {@code salvagefishing:catch},
 * this time with the real bobber and its angler, so modifiers that act on the angler (a mod putting a fish
 * straight into the player's bucket, say) still work.
 */
public final class DiveServer {
    public static final ResourceLocation CATCH_ID = new ResourceLocation(SalvageFishing.MODID, "catch");
    /** The floor treasure for pools without their own floor table. */
    static final ResourceLocation DEFAULT_FLOOR = new ResourceLocation(SalvageFishing.MODID, "floor/default");
    static final int SAMPLES = 200;
    /** Catches are at least this far apart in depth (each is 24 tall). */
    static final int MIN_SPACING = 32;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, int[]> PENDING = new HashMap<>(); // bobber id, ticks settled
    private static int nextId = 1;
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    record Entry(ItemStack stack, float x, float y) {
    }

    static final class Session {
        final int id;
        final FishingHook hook;
        final DivePools.Pool pool;
        final List<Entry> entries;
        final long startTick;

        Session(int id, FishingHook hook, DivePools.Pool pool, List<Entry> entries, long startTick) {
            this.id = id;
            this.hook = hook;
            this.pool = pool;
            this.entries = entries;
            this.startTick = startTick;
        }
    }

    private DiveServer() {
    }

    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        UUID id = player.getUUID();
        Session s = SESSIONS.get(id);
        if (s != null) {
            long max = (long) ((DiveCourse.DEPTH / DiveCourse.SPEED + 40) * 20);
            String why = s.hook.isRemoved() ? "the bobber was removed"
                    : player.fishing != s.hook ? "the player's bobber changed"
                    : player.level().getGameTime() - s.startTick > max ? "timed out" : null;
            if (why != null) end(player, s, true, why);
            return;
        }
        FishingHook hook = player.fishing;
        if (hook == null || hook.isRemoved() || hook instanceof SampleHook) {
            PENDING.remove(id);
            return;
        }
        FluidState fluid = hook.level().getFluidState(hook.blockPosition());
        DivePools.Pool pool = DivePools.find(fluid, hook);
        if (pool == null) {
            PENDING.remove(id);
            return;
        }
        // Wait two ticks in the liquid: some mods swap the bobber for another as it lands.
        int[] p = PENDING.computeIfAbsent(id, k -> new int[]{hook.getId(), 0});
        if (p[0] != hook.getId()) {
            p[0] = hook.getId();
            p[1] = 0;
        }
        if (++p[1] < 2) return;
        PENDING.remove(id);
        start(player, hook, pool);
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Session s = SESSIONS.remove(event.getEntity().getUUID());
        if (s != null && !s.hook.isRemoved()) s.hook.discard();
        PENDING.remove(event.getEntity().getUUID());
    }

    private static ItemStack rodOf(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.canPerformAction(ToolActions.FISHING_ROD_CAST)) return main;
        ItemStack off = player.getOffhandItem();
        return off.canPerformAction(ToolActions.FISHING_ROD_CAST) ? off : ItemStack.EMPTY;
    }

    private static InteractionHand handOf(ServerPlayer player) {
        return player.getMainHandItem().canPerformAction(ToolActions.FISHING_ROD_CAST) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    private static LootTable tableFor(ServerLevel level, ResourceLocation base) {
        ResourceLocation dim = level.dimension().location();
        ResourceLocation own = new ResourceLocation(base.getNamespace(), base.getPath() + "/" + dim.getNamespace() + "/" + dim.getPath());
        LootTable t = level.getServer().getLootData().getLootTable(own);
        return t != LootTable.EMPTY ? t : level.getServer().getLootData().getLootTable(base);
    }

    private static int luck(ServerPlayer player, ItemStack rod) {
        return EnchantmentHelper.getFishingLuckBonus(rod) + (int) player.getLuck();
    }

    private static void start(ServerPlayer player, FishingHook hook, DivePools.Pool pool) {
        ServerLevel level = player.serverLevel();
        ItemStack rod = rodOf(player);
        if (rod.isEmpty()) return;
        long seed = level.random.nextLong();
        Random r = new Random(seed ^ 0x5DEECE66DL);
        DiveCourse course = new DiveCourse(seed);

        // What's in this water: roll the table many times with a stand-in bobber.
        LootTable table = tableFor(level, pool.table());
        SampleHook sample = new SampleHook(hook);
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, hook.position())
                .withParameter(LootContextParams.TOOL, rod)
                .withParameter(LootContextParams.THIS_ENTITY, sample)
                .withLuck(luck(player, rod))
                .create(LootContextParamSets.FISHING);
        Map<Item, Group> groups = new LinkedHashMap<>();
        int total = 0;
        for (int i = 0; i < SAMPLES; i++) {
            for (ItemStack st : table.getRandomItems(params)) {
                if (st.isEmpty()) continue;
                Group g = groups.computeIfAbsent(st.getItem(), k -> new Group());
                g.count++;
                total++;
                if (g.stacks.size() < 8) g.stacks.add(st.copy());
            }
        }
        if (groups.isEmpty()) return; // nothing down there: leave it as normal fishing
        List<Group> list = new ArrayList<>(groups.values());

        // Fish (anything in #minecraft:fishes) swim back and forth in the open water. Everything else (junk,
        // treasure, loot boxes) rests on the rock shelves, one or two to a shelf.
        List<Group> fish = new ArrayList<>(), others = new ArrayList<>();
        for (Group g : list) (g.stacks.get(0).is(net.minecraft.tags.ItemTags.FISHES) ? fish : others).add(g);

        List<Entry> entries = new ArrayList<>();
        List<float[]> pos = new ArrayList<>();
        List<Float> taken = new ArrayList<>();
        for (DiveCourse.Shelf sh : course.shelves()) {
            int room = sh.room();
            if (others.isEmpty() || room == 0) continue;
            float iy = sh.top() - 12;
            float lo = Math.min(sh.base(), sh.tip()) + 12, hi = Math.max(sh.base(), sh.tip()) - 12;
            int n = room == 2 && r.nextFloat() < 0.5F ? 2 : 1;
            for (int k = 0; k < n; k++) {
                float x = n == 1 ? lo + r.nextFloat() * (hi - lo) : (k == 0 ? lo : hi);
                ItemStack st = pick(others, iy / DiveCourse.DEPTH, r);
                entries.add(new Entry(st, x, iy));
                pos.add(new float[]{x, iy, 0, 0, 0, 0});
            }
            taken.add(iy);
        }
        float y = 110 + r.nextInt(50);
        while (y < DiveCourse.DEPTH - 24) {
            float frac = y / DiveCourse.DEPTH;
            float iy = y;
            boolean clear = true;
            for (float t : taken) if (Math.abs(t - iy) < MIN_SPACING) clear = false;
            float lo = course.leftMax(iy - 14, iy + 14) + 14, hi = course.rightMin(iy - 14, iy + 14) - 14;
            if (clear && hi > lo && !fish.isEmpty()) {
                ItemStack st = pick(fish, frac, r);
                float range = Math.min((hi - lo) / 2F, 12 + r.nextFloat() * 40);
                float centre = lo + range + r.nextFloat() * (hi - lo - 2 * range);
                float speed = 14 + r.nextFloat() * 26;
                entries.add(new Entry(st, centre, iy));
                pos.add(new float[]{centre, iy, range, speed, r.nextFloat(), 1});
            }
            y += MIN_SPACING + r.nextInt(65);
        }

        // The floor treasure: one roll of the pool's floor table, resting in the middle of the floor.
        LootTable floorTable = tableFor(level, pool.floorTable());
        if (floorTable == LootTable.EMPTY && !pool.floorNamed()) floorTable = tableFor(level, DEFAULT_FLOOR);
        if (floorTable != LootTable.EMPTY) {
            for (ItemStack st : floorTable.getRandomItems(params)) {
                if (st.isEmpty()) continue;
                float fx = course.floorX(), fy = DiveCourse.DEPTH - 12;
                entries.add(new Entry(st, fx, fy));
                pos.add(new float[]{fx, fy, 0, 0, 0, 0});
                break;
            }
        }

        Session s = new Session(nextId++, hook, pool, entries, level.getGameTime());
        SESSIONS.put(player.getUUID(), s);
        List<ItemStack> stacks = new ArrayList<>();
        for (Entry e : entries) stacks.add(e.stack());
        DiveNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StartDivePacket(s.id, seed, pool.theme(), pool.colors(), stacks, pos));
        level.playSound(null, hook.getX(), hook.getY(), hook.getZ(), SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.6F, 0.8F);
    }

    private static final class Group {
        int count;
        final List<ItemStack> stacks = new ArrayList<>();
    }

    private static ItemStack pick(List<Group> list, float frac, Random r) {
        double exp = 1.0 - 1.6 * frac;
        double total = 0;
        double[] w = new double[list.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = Math.pow(list.get(i).count, exp);
            total += w[i];
        }
        double x = r.nextDouble() * total;
        Group g = list.get(list.size() - 1);
        for (int i = 0; i < w.length; i++) {
            x -= w[i];
            if (x <= 0) {
                g = list.get(i);
                break;
            }
        }
        return g.stacks.get(r.nextInt(g.stacks.size())).copy();
    }

    /** What the client reports: 0 = caught entry {@code index}, 1 = hit the rock or ran out of line, 2 = reeled in. */
    public static void onResult(ServerPlayer player, int sessionId, int kind, int index) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null || s.id != sessionId) {
            if (kind == 0) refuse(player, s == null ? "the dive had already ended on the server (" + lastEnd.getOrDefault(player.getUUID(), "unknown") + ")"
                    : "it was for an older dive");
            return;
        }
        SESSIONS.remove(player.getUUID());
        if (kind == 0) {
            if (index < 0 || index >= s.entries.size()) refuse(player, "no such catch (" + index + ")");
            else {
                Entry e = s.entries.get(index);
                float seconds = (player.level().getGameTime() - s.startTick) / 20F;
                float earliest = e.y() / DiveCourse.SPEED * 0.3F - 2F; // a loose sanity check only
                if (seconds < earliest) refuse(player, String.format("too early (%.1f s, needs %.1f s)", seconds, earliest));
                else award(player, s, e.stack().copy());
            }
        }
        if (!s.hook.isRemoved()) s.hook.discard();
    }

    /** Why the last dive ended on the server, for the message when a late catch arrives. */
    private static final Map<UUID, String> lastEnd = new HashMap<>();

    /** A catch that didn't pay out: tell the player why (and log it), so problems can be reported. */
    private static void refuse(ServerPlayer player, String why) {
        LOG.warn("Salvage Fishing: {}'s catch was not given: {}", player.getGameProfile().getName(), why);
        player.displayClientMessage(net.minecraft.network.chat.Component.literal("[Salvage Fishing] Catch not given: " + why)
                .withStyle(net.minecraft.ChatFormatting.GRAY), false);
    }

    private static void end(ServerPlayer player, Session s, boolean tell, String why) {
        SESSIONS.remove(player.getUUID());
        lastEnd.put(player.getUUID(), why);
        LOG.info("Salvage Fishing: {}'s dive ended on the server: {}", player.getGameProfile().getName(), why);
        if (!s.hook.isRemoved()) s.hook.discard();
        if (tell) DiveNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new EndDivePacket(s.id));
    }

    private static void award(ServerPlayer player, Session s, ItemStack stack) {
        ServerLevel level = player.serverLevel();
        FishingHook hook = s.hook;
        ItemStack rod = rodOf(player);
        if (rod.isEmpty()) {
            refuse(player, "no fishing rod in hand");
            return;
        }
        if (hook.isRemoved()) refuse(player, "the bobber was already gone (giving the catch anyway)");
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, hook.position())
                .withParameter(LootContextParams.TOOL, rod)
                .withParameter(LootContextParams.THIS_ENTITY, hook)
                .withLuck(luck(player, rod))
                .create(LootContextParamSets.FISHING);
        LootContext ctx = new LootContext.Builder(params).create(null);
        ObjectArrayList<ItemStack> loot = new ObjectArrayList<>();
        loot.add(stack);
        ItemStack original = stack.copy();
        loot = ForgeHooks.modifyLoot(CATCH_ID, loot, ctx);
        if (loot.stream().allMatch(ItemStack::isEmpty)) {
            refuse(player, "a loot modifier emptied it (giving the original)");
            loot = new ObjectArrayList<>();
            loot.add(original);
        }

        ItemFishedEvent event = new ItemFishedEvent(loot, 1, hook);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            refuse(player, "another mod cancelled the catch");
            return;
        }
        CriteriaTriggers.FISHING_ROD_HOOKED.trigger(player, rod, hook, loot);
        for (ItemStack st : loot) {
            if (st.isEmpty()) continue;
            if (s.pool.direct()) {
                if (!player.getInventory().add(st)) {
                    ItemEntity drop = new ItemEntity(level, player.getX(), player.getY() + 0.5, player.getZ(), st);
                    drop.setNoPickUpDelay();
                    level.addFreshEntity(drop);
                }
            } else {
                ItemEntity item = new ItemEntity(level, hook.getX(), hook.getY(), hook.getZ(), st);
                double dx = player.getX() - hook.getX(), dy = player.getY() - hook.getY(), dz = player.getZ() - hook.getZ();
                item.setDeltaMovement(dx * 0.1D, dy * 0.1D + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08D, dz * 0.1D);
                level.addFreshEntity(item);
            }
            level.addFreshEntity(new ExperienceOrb(level, player.getX(), player.getY() + 0.5D, player.getZ() + 0.5D, level.random.nextInt(6) + 1));
            if (st.is(ItemTags.FISHES)) player.awardStat(Stats.FISH_CAUGHT, 1);
        }
        InteractionHand hand = handOf(player);
        rod.hurtAndBreak(Math.max(1, event.getRodDamage()), player, p -> p.broadcastBreakEvent(hand));
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1.0F, 1.0F);
    }
}
