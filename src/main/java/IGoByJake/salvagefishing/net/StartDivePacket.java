package IGoByJake.salvagefishing.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server → client: a dive starts. The shaft's seed (walls), its look, and what's down there (x, y, drift, phase). */
public record StartDivePacket(int id, long seed, String theme, List<ItemStack> stacks, List<float[]> pos) {
    public static void encode(StartDivePacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.id);
        buf.writeLong(p.seed);
        buf.writeUtf(p.theme, 32);
        buf.writeVarInt(p.stacks.size());
        for (int i = 0; i < p.stacks.size(); i++) {
            buf.writeItem(p.stacks.get(i));
            for (float f : p.pos.get(i)) buf.writeFloat(f);
        }
    }

    public static StartDivePacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        long seed = buf.readLong();
        String theme = buf.readUtf(32);
        int n = buf.readVarInt();
        List<ItemStack> stacks = new ArrayList<>(n);
        List<float[]> pos = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            stacks.add(buf.readItem());
            pos.add(new float[]{buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()});
        }
        return new StartDivePacket(id, seed, theme, stacks, pos);
    }

    public static void handle(StartDivePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> IGoByJake.salvagefishing.client.ClientDive.start(p)));
        ctx.get().setPacketHandled(true);
    }
}
