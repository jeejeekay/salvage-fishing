package IGoByJake.salvagefishing.client;

import IGoByJake.salvagefishing.net.StartDivePacket;
import net.minecraft.client.Minecraft;

/** Client entry points for the dive packets. */
public final class ClientDive {
    private ClientDive() {
    }

    public static void start(StartDivePacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new DiveScreen(p));
    }

    public static void end(int id) {
        if (Minecraft.getInstance().screen instanceof DiveScreen s && s.id() == id) s.serverEnded();
    }
}
