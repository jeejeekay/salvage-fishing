package IGoByJake.salvagefishing;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FishingHook;

/**
 * A stand-in bobber used only to fill the shaft: never added to the world, has no owner (so nothing that acts on
 * the angler, like taking a bucket, runs while sampling), and reports the real bobber's open-water state so
 * treasure works as it does for normal fishing.
 */
final class SampleHook extends FishingHook {
    private final boolean openWater;

    SampleHook(FishingHook real) {
        super(EntityType.FISHING_BOBBER, real.level());
        this.openWater = real.isOpenWaterFishing();
        this.setPos(real.position());
    }

    @Override
    public boolean isOpenWaterFishing() {
        return this.openWater;
    }
}
