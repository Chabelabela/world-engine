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
 * coordinates, so these tests place a dropped item at the home planet's net coordinates and run the handler directly.
 */
public class SeamGameTests {
    private static final PlanetLayout HOME = Planets.HOME.layout();
    private static final int N = Planets.HOME.cells();
    private static final double Y = 100.0;

    @GameTest
    public void theEquatorWrapKeepsMomentum(GameTestHelper helper) {
        try (Scene scene = new Scene(helper)) {
            double z = HOME.faceOriginZ(PlanetFace.E3) + 100.5;
            double east = HOME.faceOriginX(PlanetFace.E3) + N;
            double west = HOME.faceOriginX(PlanetFace.E0);
            Entity item = scene.item(east - 4.5, z, west, z);
            scene.move(item, east + 0.25, z, new Vec3(0.3, 0, 0), -90.0F);
            // Fold's point is 0.25 inside E0's west edge; it lands one tick (0.3) short of it.
            scene.expect(item, west + 0.25 - 0.3, z, 0.3, 0.0, -90.0F, "across the equator wrap");
            // Now 0.05 blocks off E0's edge, but the cooldown stops it folding straight back.
            SeamCrossingHandler.tickLevel(helper.getLevel());
            scene.expect(item, west + 0.25 - 0.3, z, 0.3, 0.0, -90.0F, "still there during the cooldown");
        }
        helper.succeed();
    }

    @GameTest
    public void aRotatedSeamTurnsMomentumAndLook(GameTestHelper helper) {
        try (Scene scene = new Scene(helper)) {
            int ox = HOME.faceOriginX(PlanetFace.NORTH);
            int oz = HOME.faceOriginZ(PlanetFace.NORTH);
            SeamCrossing fold = WorldEngineMod.core().seamCross(N, PlanetFace.NORTH.id(), -0.25, 100.5, -0.281, 0, 90);
            double foldX = HOME.faceOriginX(PlanetFace.E0) + fold.x();
            double foldZ = HOME.faceOriginZ(PlanetFace.E0) + fold.z();
            Entity item = scene.item(ox + 3.5, oz + 100.5, foldX, foldZ);
            scene.move(item, ox - 0.25, oz + 100.5, new Vec3(-0.281, 0, 0), 90.0F);
            // North's west edge meets E0 three quarter turns round: running west carries on running south.
            scene.expect(item, foldX, foldZ - 0.281, 0.0, 0.281, 0.0F, "across North's west edge");
        }
        helper.succeed();
    }

    @GameTest
    public void beyondACornerItGoesBack(GameTestHelper helper) {
        try (Scene scene = new Scene(helper)) {
            int ox = HOME.faceOriginX(PlanetFace.E0);
            int oz = HOME.faceOriginZ(PlanetFace.E0);
            Entity item = scene.item(ox + 2.5, oz + 2.5, ox + 2.5, oz + 2.5);
            scene.move(item, ox - 0.5, oz - 0.5, new Vec3(-1, 0, -1), 135.0F);
            scene.expect(item, ox + 2.5, oz + 2.5, 0.0, 0.0, 135.0F, "back from beyond E0's north-west corner");
        }
        helper.succeed();
    }

    @GameTest
    public void edgesTheNetJoinsAreJustWalking(GameTestHelper helper) {
        try (Scene scene = new Scene(helper)) {
            double edge = HOME.faceOriginX(PlanetFace.E2);
            double z = HOME.faceOriginZ(PlanetFace.E2) + 100.5;
            Entity item = scene.item(edge - 2.5, z, edge + 0.5, z);
            scene.move(item, edge + 0.5, z, new Vec3(0.3, 0, 0), -90.0F);
            scene.expect(item, edge + 0.5, z, 0.3, 0.0, -90.0F, "from E1 onto E2, untouched");
        }
        helper.succeed();
    }

    /** Items at net coordinates, with their chunks loaded, cleaned up afterwards. */
    private static final class Scene implements AutoCloseable {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final List<ChunkPos> tickets = new ArrayList<>();
        private final List<Entity> entities = new ArrayList<>();

        Scene(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
        }

        /** A still item at (x, z), remembered by the handler; the chunks at (x, z) and (toX, toZ) are kept loaded. */
        Entity item(double x, double z, double toX, double toZ) {
            load(x, z);
            load(toX, toZ);
            ItemEntity item = new ItemEntity(level, x, Y, z, new ItemStack(Items.STONE));
            item.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(item);
            entities.add(item);
            SeamCrossingHandler.tickLevel(level);
            return item;
        }

        /** Moves the item (as its own tick would) and runs the handler once. */
        void move(Entity item, double x, double z, Vec3 velocity, float yaw) {
            item.snapTo(x, Y, z, yaw, 0.0F);
            item.setDeltaMovement(velocity);
            SeamCrossingHandler.tickLevel(level);
        }

        void expect(Entity item, double x, double z, double vx, double vz, float yaw, String what) {
            String got = String.format(Locale.ROOT, "%s: at (%.6f, %.6f) moving (%.6f, %.6f) yaw %.2f", what, item.getX(), item.getZ(),
                    item.getDeltaMovement().x, item.getDeltaMovement().z, item.getYRot());
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

        @Override
        public void close() {
            entities.forEach(Entity::discard);
            tickets.forEach(pos -> level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, pos, 1));
        }
    }
}
