package io.github.chabelabela.worldengine.gametest;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.level.SeamCrossingHandler;
import io.github.chabelabela.worldengine.nativebridge.SeamCrossing;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Seam crossing in a real server. The GameTest level is flat, but {@link SeamCrossingHandler} only needs level
 * coordinates, so these tests place a dropped item at the home planet's net coordinates and run the handler directly,
 * all within one tick. Every chunk a test touches is loaded first: an entity moved into a chunk that isn't loaded
 * drops out of the level's entity list, so the handler wouldn't see it.
 */
public class SeamGameTests {
    private static final PlanetLayout HOME = Planets.HOME.layout();
    private static final int N = Planets.HOME.cells();
    private static final double Y = 100.0;

    @GameTest
    public void theEquatorWrapKeepsMomentum(GameTestHelper helper) {
        double z = HOME.faceOriginZ(PlanetFace.E3) + 100.5;
        double east = HOME.faceOriginX(PlanetFace.E3) + N;
        double west = HOME.faceOriginX(PlanetFace.E0);
        Scene.run(helper, east - 4.5, z, new double[] {east + 0.25, z, west + 0.25 - 0.3, z}, (scene, item) -> {
            scene.move(item, east + 0.25, z, new Vec3(0.3, 0, 0), -90.0F);
            // Fold's point is 0.25 inside E0's west edge; it lands one tick (0.3) short of it.
            scene.expect(item, west + 0.25 - 0.3, z, 0.3, 0.0, -90.0F, "across the equator wrap");
            // Now 0.05 blocks off E0's edge, but the cooldown stops it folding straight back.
            scene.assertInLevel(item, "after landing");
            SeamCrossingHandler.tickLevel(helper.getLevel());
            scene.expect(item, west + 0.25 - 0.3, z, 0.3, 0.0, -90.0F, "still there during the cooldown");
        });
    }

    @GameTest
    public void aRotatedSeamTurnsMomentumAndLook(GameTestHelper helper) {
        int ox = HOME.faceOriginX(PlanetFace.NORTH);
        int oz = HOME.faceOriginZ(PlanetFace.NORTH);
        SeamCrossing fold = WorldEngineMod.core().seamCross(N, PlanetFace.NORTH.id(), -0.25, 100.5, -0.281, 0, 90);
        double foldX = HOME.faceOriginX(PlanetFace.E0) + fold.x();
        double foldZ = HOME.faceOriginZ(PlanetFace.E0) + fold.z();
        double[] load = {ox - 0.25, oz + 100.5, foldX, foldZ - 0.281};
        Scene.run(helper, ox + 3.5, oz + 100.5, load, (scene, item) -> {
            scene.move(item, ox - 0.25, oz + 100.5, new Vec3(-0.281, 0, 0), 90.0F);
            // North's west edge meets E0 three quarter turns round: running west carries on running south.
            scene.expect(item, foldX, foldZ - 0.281, 0.0, 0.281, 0.0F, "across North's west edge");
        });
    }

    @GameTest
    public void beyondACornerItGoesBack(GameTestHelper helper) {
        int ox = HOME.faceOriginX(PlanetFace.E0);
        int oz = HOME.faceOriginZ(PlanetFace.E0);
        Scene.run(helper, ox + 2.5, oz + 2.5, new double[] {ox - 0.5, oz - 0.5}, (scene, item) -> {
            scene.move(item, ox - 0.5, oz - 0.5, new Vec3(-1, 0, -1), 135.0F);
            scene.expect(item, ox + 2.5, oz + 2.5, 0.0, 0.0, 135.0F, "back from beyond E0's north-west corner");
        });
    }

    @GameTest
    public void edgesTheNetJoinsAreJustWalking(GameTestHelper helper) {
        double edge = HOME.faceOriginX(PlanetFace.E2);
        double z = HOME.faceOriginZ(PlanetFace.E2) + 100.5;
        Scene.run(helper, edge - 2.5, z, new double[] {edge + 0.5, z}, (scene, item) -> {
            scene.move(item, edge + 0.5, z, new Vec3(0.3, 0, 0), -90.0F);
            scene.expect(item, edge + 0.5, z, 0.3, 0.0, -90.0F, "from E1 onto E2, untouched");
        });
    }

    /** One dropped item at net coordinates, with the chunks it uses kept loaded, cleaned up afterwards. */
    private static final class Scene {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final List<ChunkPos> tickets = new ArrayList<>();

        private Scene(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
        }

        /**
         * Loads the chunks at (x, z) and at every {x, z} pair in {@code alsoLoad} (where the steps move the item and
         * where it lands), puts a still item at (x, z), lets the handler remember it, runs {@code steps}, and cleans
         * up.
         */
        static void run(GameTestHelper helper, double x, double z, double[] alsoLoad, StepsBody steps) {
            Scene scene = new Scene(helper);
            scene.load(x, z);
            for (int i = 0; i < alsoLoad.length; i += 2) {
                scene.load(alsoLoad[i], alsoLoad[i + 1]);
            }
            ItemEntity item = new ItemEntity(scene.level, x, Y, z, new ItemStack(Items.STONE));
            item.setNoGravity(true);
            item.setDeltaMovement(Vec3.ZERO);
            scene.level.addFreshEntity(item);
            try {
                scene.assertInLevel(item, "after spawning");
                SeamCrossingHandler.tickLevel(scene.level);
                steps.accept(scene, item);
                helper.succeed();
            } finally {
                item.discard();
                scene.tickets.forEach(pos -> scene.level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 1));
            }
        }

        void assertInLevel(Entity item, String when) {
            helper.assertTrue(StreamSupport.stream(level.getAllEntities().spliterator(), false).anyMatch(e -> e == item),
                    "the item is in the level's entity list " + when);
        }

        /** Moves the item (as its own tick would) and runs the handler once. */
        void move(Entity item, double x, double z, Vec3 velocity, float yaw) {
            item.snapTo(x, Y, z, yaw, 0.0F);
            item.setDeltaMovement(velocity);
            assertInLevel(item, "after moving");
            SeamCrossingHandler.tickLevel(level);
        }

        void expect(Entity item, double x, double z, double vx, double vz, float yaw, String what) {
            String got = String.format(Locale.ROOT, "%s: at (%.6f, %.6f) moving (%.6f, %.6f) yaw %.2f", what,
                    item.getX(), item.getZ(), item.getDeltaMovement().x, item.getDeltaMovement().z, item.getYRot());
            helper.assertTrue(Math.abs(item.getX() - x) < 1e-9 && Math.abs(item.getZ() - z) < 1e-9, got);
            helper.assertTrue(Math.abs(item.getY() - Y) < 1e-9, got + ": height kept");
            helper.assertTrue(Math.abs(item.getDeltaMovement().x - vx) < 1e-9
                    && Math.abs(item.getDeltaMovement().z - vz) < 1e-9, got + ": velocity");
            helper.assertTrue(Math.floorMod(Math.round(item.getYRot() - yaw), 360) == 0, got + ": yaw");
        }

        private void load(double x, double z) {
            ChunkPos pos = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            level.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 1);
            level.getChunk(pos.x(), pos.z());
            tickets.add(pos);
        }
    }

    /** The part of a test that runs once its scene has settled. */
    @FunctionalInterface
    private interface StepsBody {
        void accept(Scene scene, Entity item);
    }
}
