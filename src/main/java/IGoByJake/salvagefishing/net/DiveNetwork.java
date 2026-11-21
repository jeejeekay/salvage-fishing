package IGoByJake.salvagefishing.net;

import IGoByJake.salvagefishing.SalvageFishing;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class DiveNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(SalvageFishing.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private DiveNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, StartDivePacket.class, StartDivePacket::encode, StartDivePacket::decode, StartDivePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, EndDivePacket.class, EndDivePacket::encode, EndDivePacket::decode, EndDivePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DiveResultPacket.class, DiveResultPacket::encode, DiveResultPacket::decode, DiveResultPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
}
