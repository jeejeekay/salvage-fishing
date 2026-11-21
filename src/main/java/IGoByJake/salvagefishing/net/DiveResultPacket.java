package IGoByJake.salvagefishing.net;

import IGoByJake.salvagefishing.DiveServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client → server: how the dive ended. kind 0 = caught entry {@code index}, 1 = rock or out of line, 2 = reeled in. */
public record DiveResultPacket(int id, int kind, int index) {
    public static final int CATCH = 0, FAIL = 1, ABORT = 2;

    public static void encode(DiveResultPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.id);
        buf.writeByte(p.kind);
        buf.writeVarInt(p.index + 1);
    }

    public static DiveResultPacket decode(FriendlyByteBuf buf) {
        return new DiveResultPacket(buf.readVarInt(), buf.readByte(), buf.readVarInt() - 1);
    }

    public static void handle(DiveResultPacket p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player != null) ctx.get().enqueueWork(() -> DiveServer.onResult(player, p.id, p.kind, p.index));
        ctx.get().setPacketHandled(true);
    }
}
