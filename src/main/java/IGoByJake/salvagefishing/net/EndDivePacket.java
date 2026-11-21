package IGoByJake.salvagefishing.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server → client: the dive was cut short (bobber gone, rod put away, too far away). */
public record EndDivePacket(int id) {
    public static void encode(EndDivePacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.id);
    }

    public static EndDivePacket decode(FriendlyByteBuf buf) {
        return new EndDivePacket(buf.readVarInt());
    }

    public static void handle(EndDivePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> IGoByJake.salvagefishing.client.ClientDive.end(p.id)));
        ctx.get().setPacketHandled(true);
    }
}
