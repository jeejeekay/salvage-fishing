package IGoByJake.salvagefishing;

import IGoByJake.salvagefishing.net.DiveNetwork;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

/**
 * Salvage Fishing: casting into a liquid starts a dive. The hook drops down a 2D shaft between rock walls; steer it
 * (A/D, or click and drag) past what you don't want and snag what you do. Everything in the shaft comes from the
 * liquid's fishing loot table (see {@link DivePools}); rarer catches get commoner the deeper you go.
 */
@Mod(SalvageFishing.MODID)
public class SalvageFishing {
    public static final String MODID = "salvagefishing";

    public SalvageFishing() {
        DiveNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(DivePools::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(DiveServer::onPlayerTick);
        MinecraftForge.EVENT_BUS.addListener(DiveServer::onLogout);
    }
}
