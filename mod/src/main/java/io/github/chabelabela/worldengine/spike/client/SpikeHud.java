package io.github.chabelabela.worldengine.spike.client;

import io.github.chabelabela.worldengine.WorldEngineMod;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * The M1 measurement HUD (spike only). Per frame, after the last teleport:
 * <ul>
 *   <li>frames that had a <b>missing chunk</b> within 32 blocks of the camera: a chunk the client doesn't have,
 *       or a section with blocks whose mesh isn't compiled yet (vanilla renderer only; with Sodium the mesh
 *       check is skipped and only chunk data counts);</li>
 *   <li>bytes received per second, from vanilla's bandwidth logger (counts only on a real network connection:
 *       singleplayer uses an in-memory pipeline that is never measured);</li>
 *   <li>loading screens opened since the jump.</li>
 * </ul>
 * A teleport is any camera move of more than {@value #JUMP_DISTANCE} blocks in one frame. Two seconds after
 * each jump, one summary line goes to the client log ({@code [spike] E1 …}) for the measurement table.
 */
final class SpikeHud {
    private static final double JUMP_DISTANCE = 64.0;
    private static final int RADIUS = 32;
    private static final int SUMMARY_TICKS = 40;

    private static final boolean SODIUM =
            net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

    private Vec3 lastCamera;
    private boolean jumped;
    private long jumpNanos;
    private int framesSinceJump;
    private int missingFrames;
    private int lastMissingFrame = -1;
    private int loadingScreens;
    private int ticksSinceJump;
    private long rxBytesSinceJump;
    private boolean summaryLogged;
    private final long[] rxWindow = new long[20];
    private int rxIndex;
    private int jumps;

    // -------------------------------------------------------------------------------------------
    // Per frame
    // -------------------------------------------------------------------------------------------

    void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        Vec3 camera = mc.gameRenderer.mainCamera().position();
        if (lastCamera != null && camera.distanceTo(lastCamera) > JUMP_DISTANCE) {
            onJump(lastCamera, camera);
        }
        lastCamera = camera;
        if (jumped) {
            framesSinceJump++;
            if (anyMissing(mc, level, camera)) {
                missingFrames++;
                lastMissingFrame = framesSinceJump;
            }
        }
        draw(mc, graphics);
    }

    private void onJump(Vec3 from, Vec3 to) {
        jumps++;
        jumped = true;
        jumpNanos = System.nanoTime();
        framesSinceJump = 0;
        missingFrames = 0;
        lastMissingFrame = -1;
        loadingScreens = 0;
        ticksSinceJump = 0;
        rxBytesSinceJump = 0;
        summaryLogged = false;
        WorldEngineMod.LOGGER.info("[spike] client saw jump #{}: {} blocks", jumps, Math.round(from.distanceTo(to)));
    }

    /** True if some chunk within {@link #RADIUS} blocks of the camera isn't ready to be seen. */
    private static boolean anyMissing(Minecraft mc, ClientLevel level, Vec3 camera) {
        int renderChunks = mc.options.getEffectiveRenderDistance();
        int camChunkX = SectionPos.blockToSectionCoord(camera.x);
        int camChunkZ = SectionPos.blockToSectionCoord(camera.z);
        int minSectionY = Math.max(level.getMinSectionY(), SectionPos.blockToSectionCoord(camera.y - RADIUS));
        int maxSectionY = Math.min(level.getMaxSectionY(), SectionPos.blockToSectionCoord(camera.y + RADIUS));
        int r = Math.min(RADIUS >> 4, renderChunks);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(camChunkX + dx, camChunkZ + dz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    return true;
                }
                if (SODIUM) {
                    continue;
                }
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
                    if (section.hasOnlyAir()) {
                        continue; // air sections may never be meshed; they can't look missing anyway
                    }
                    BlockPos pos = SectionPos.of(camChunkX + dx, sy, camChunkZ + dz).origin();
                    if (!mc.levelRenderer.isSectionCompiledAndVisible(pos, 0L)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void draw(Minecraft mc, GuiGraphicsExtractor graphics) {
        String rx = mc.hasSingleplayerServer()
                ? "rx n/a (singleplayer: in-memory, unmeasured)"
                : String.format(Locale.ROOT, "rx %.1f KB/s", rxPerSecond() / 1024.0);
        String line1 = String.format(Locale.ROOT, "WE spike%s | %s", SODIUM ? " [sodium]" : "", rx);
        String line2 = jumped
                ? String.format(Locale.ROOT, "jump #%d: %d frames / %.1f s ago | missing-chunk frames %d%s | loading screens %d",
                        jumps, framesSinceJump, (System.nanoTime() - jumpNanos) / 1e9, missingFrames,
                        lastMissingFrame >= 0 ? " (last @" + lastMissingFrame + ")" : "", loadingScreens)
                : "no jump yet";
        int y = graphics.guiHeight() - 70;
        graphics.text(mc.font, line1, 4, y, 0xFFFFFF55, true);
        graphics.text(mc.font, line2, 4, y + 10, 0xFFFFFF55, true);
    }

    // -------------------------------------------------------------------------------------------
    // Per tick and screens
    // -------------------------------------------------------------------------------------------

    void endTick(Minecraft mc) {
        long sample = latestBandwidthSample(mc);
        rxWindow[rxIndex] = sample;
        rxIndex = (rxIndex + 1) % rxWindow.length;
        if (!jumped || summaryLogged) {
            return;
        }
        ticksSinceJump++;
        rxBytesSinceJump += sample;
        if (ticksSinceJump >= SUMMARY_TICKS) {
            summaryLogged = true;
            WorldEngineMod.LOGGER.info(
                    "[spike] E1 jump #{}: loadingScreen={} missingFrames={} lastMissingFrame={} framesIn2s={} rxBytes2s={} "
                            + "renderDistance={} sodium={} singleplayer={}",
                    jumps, loadingScreens > 0, missingFrames, lastMissingFrame, framesSinceJump,
                    mc.hasSingleplayerServer() ? "n/a" : Long.toString(rxBytesSinceJump),
                    mc.options.getEffectiveRenderDistance(), SODIUM, mc.hasSingleplayerServer());
        }
    }

    void screenOpened(Screen screen) {
        boolean loading = screen instanceof LevelLoadingScreen || screen instanceof ProgressScreen;
        if (loading && jumped) {
            loadingScreens++;
        }
        WorldEngineMod.LOGGER.info("[spike] screen opened: {}{}", screen.getClass().getName(), loading ? " (LOADING)" : "");
    }

    private long rxPerSecond() {
        long sum = 0;
        for (long b : rxWindow) {
            sum += b;
        }
        return sum;
    }

    /** Bytes received during the last client tick (the logger gets one sample per connection tick). */
    private static long latestBandwidthSample(Minecraft mc) {
        LocalSampleLogger logger = mc.getDebugOverlay().getBandwidthLogger();
        int size = logger.size();
        return size == 0 ? 0 : logger.get(size - 1);
    }
}
