package io.github.chabelabela.worldengine.spike.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.spike.ShipPayloads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * E5's frame-by-frame pop metrics (spike only). While {@code CAPTURE} is on, every frame is read back (half size) right
 * after the level is drawn, before the GUI, and compared with the previous frame. Around each frame switch (the camera
 * entering or leaving the shipyard), {@value #PRE} frames before and {@value #POST} after are logged, one line each:
 * <ul>
 *   <li>{@code diff}: mean absolute luminance change from the previous frame (0–255), whole frame and a 3×3 grid;</li>
 *   <li>{@code camRel}/{@code dCam}: the camera relative to the ship as drawn, and how far that moved: a camera pop;</li>
 *   <li>{@code miss}/{@code holes}: chunks within render distance the client lacks, and sections vanilla wants to draw
 *       whose mesh isn't built yet (vanilla renderer);</li>
 *   <li>{@code sky}/{@code fog}: the camera's sky and fog colours.</li>
 * </ul>
 * With {@code CAPTURE_FRAMES}, those frames are also written as PNGs under {@code run/spike-e5/}.
 */
final class PopLogger {
    static final int PRE = 30;
    static final int POST = 120;

    private record Sample(long frame, double dtMs, boolean shipFrame, Vec3 camRel, int missingChunks, int holes, int sky,
                          int fog, String label) {
    }

    private record Kept(String line, NativeImage image) {
    }

    private final ArrayDeque<Kept> before = new ArrayDeque<>();
    private long frame;
    private long lastNanos;
    private Boolean lastShipFrame;
    private float[] prevThumb;
    private Vec3 prevCamRel;
    private int postLeft;
    private int postIndex;
    private int switches;
    private String switchName = "";
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WE spike frame writer");
        t.setDaemon(true);
        return t;
    });

    /** Every frame, right after the level is drawn (before post effects and the GUI). */
    void onFrame(Minecraft mc, ShipRenderer renderer) {
        if (!E5Client.settings.has(ShipPayloads.E5Settings.CAPTURE) || mc.level == null) {
            clearBefore();
            lastShipFrame = null;
            prevThumb = null;
            return;
        }
        long now = System.nanoTime();
        double dtMs = lastNanos == 0 ? 0 : (now - lastNanos) / 1e6;
        lastNanos = now;
        Vec3 camera = mc.gameRenderer.mainCamera().position();
        Vec3 centre = renderer.lastDrawCentre;
        Vec3 camRel = centre == null ? null : camera.subtract(E5Client.cellOrigin(centre));
        int missing = missingChunks(mc, mc.level, camera);
        int holes = holes(mc);
        float partialTick = E5Client.partialTick();
        int sky = rgb(mc.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, partialTick));
        int fog = rgb(mc.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR, partialTick));
        Sample sample = new Sample(frame++, dtMs, E5Client.inShipFrame(camera), camRel, missing, holes, sky, fog,
                E5Client.settings.label());
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        int factor = target.width % 2 == 0 && target.height % 2 == 0 ? 2 : 1;
        Screenshot.takeScreenshot(target, factor, image -> onImage(sample, image));
    }

    /** When the read-back of {@code sample}'s frame arrives (a few frames later, in order). */
    private void onImage(Sample sample, NativeImage image) {
        float[] thumb = thumbnail(image);
        double full = prevThumb == null ? Double.NaN : diff(prevThumb, thumb, image.getWidth() / 4, image.getHeight() / 4, -1);
        StringBuilder grid = new StringBuilder();
        for (int cell = 0; cell < 9; cell++) {
            if (cell > 0) {
                grid.append(',');
            }
            grid.append(prevThumb == null ? "nan"
                    : String.format(Locale.ROOT, "%.2f", diff(prevThumb, thumb, image.getWidth() / 4, image.getHeight() / 4, cell)));
        }
        double dCam = prevCamRel != null && sample.camRel() != null ? sample.camRel().distanceTo(prevCamRel) : Double.NaN;
        String camRel = sample.camRel() == null ? "none"
                : String.format(Locale.ROOT, "(%.4f,%.4f,%.4f)", sample.camRel().x, sample.camRel().y, sample.camRel().z);
        String line = String.format(Locale.ROOT,
                "f=%d dt=%.2f mode=%s camRel=%s dCam=%.4f diff=%.2f grid=%s miss=%d holes=%d sky=%06x fog=%06x label=\"%s\"",
                sample.frame(), sample.dtMs(), sample.shipFrame() ? "ship" : "surface", camRel, dCam, full, grid,
                sample.missingChunks(), sample.holes(), sample.sky(), sample.fog(), sample.label());
        boolean isSwitch = lastShipFrame != null && sample.shipFrame() != lastShipFrame;
        lastShipFrame = sample.shipFrame();
        prevThumb = thumb;
        prevCamRel = sample.camRel();
        boolean keepImages = E5Client.settings.has(ShipPayloads.E5Settings.CAPTURE_FRAMES);
        if (isSwitch) {
            switches++;
            switchName = (sample.shipFrame() ? "up" : "down") + switches;
            int index = -before.size();
            for (Kept kept : before) {
                emit(kept.line(), kept.image(), index++);
            }
            before.clear();
            postLeft = POST;
            postIndex = 0;
        }
        if (postLeft > 0) {
            emit(line, keepImages ? image : null, postIndex++);
            if (!keepImages) {
                image.close();
            }
            postLeft--;
        } else {
            before.add(new Kept(line, keepImages ? image : null));
            if (!keepImages) {
                image.close();
            }
            while (before.size() > PRE) {
                Kept old = before.poll();
                if (old.image() != null) {
                    old.image().close();
                }
            }
        }
    }

    private void emit(String line, NativeImage image, int index) {
        WorldEngineMod.LOGGER.info("[spike] E5 frame sw={} i={} {}", switchName, index, line);
        if (image == null) {
            return;
        }
        String label = E5Client.settings.label().replaceAll("[^A-Za-z0-9]+", "_");
        Path file = Path.of("spike-e5", label, String.format(Locale.ROOT, "%s_%+04d.png", switchName, index));
        writer.submit(() -> {
            try {
                Files.createDirectories(file.getParent());
                image.writeToFile(file);
            } catch (Exception e) {
                WorldEngineMod.LOGGER.warn("[spike] E5 could not write {}: {}", file, e.toString());
            } finally {
                image.close();
            }
        });
    }

    private void clearBefore() {
        for (Kept kept : before) {
            if (kept.image() != null) {
                kept.image().close();
            }
        }
        before.clear();
    }

    /** Mean luminance of 4×4 pixel blocks. */
    private static float[] thumbnail(NativeImage image) {
        int w = image.getWidth() / 4;
        int h = image.getHeight() / 4;
        float[] out = new float[w * h];
        for (int ty = 0; ty < h; ty++) {
            for (int tx = 0; tx < w; tx++) {
                int sum = 0;
                for (int dy = 0; dy < 4; dy++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int c = image.getPixel(tx * 4 + dx, ty * 4 + dy);
                        sum += ARGB.red(c) + ARGB.green(c) + ARGB.blue(c);
                    }
                }
                out[tx + ty * w] = sum / 48.0F;
            }
        }
        return out;
    }

    /** Mean absolute difference over the whole thumbnail ({@code cell} -1) or one cell of a 3×3 grid (row-major). */
    private static double diff(float[] a, float[] b, int w, int h, int cell) {
        int x0 = 0;
        int x1 = w;
        int y0 = 0;
        int y1 = h;
        if (cell >= 0) {
            x0 = w * (cell % 3) / 3;
            x1 = w * (cell % 3 + 1) / 3;
            y0 = h * (cell / 3) / 3;
            y1 = h * (cell / 3 + 1) / 3;
        }
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                sum += Math.abs(a[x + y * w] - b[x + y * w]);
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Chunks within render distance that the client doesn't have. */
    private static int missingChunks(Minecraft mc, ClientLevel level, Vec3 camera) {
        int r = mc.options.getEffectiveRenderDistance();
        int cx = SectionPos.blockToSectionCoord(camera.x);
        int cz = SectionPos.blockToSectionCoord(camera.z);
        int missing = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                if (level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) == null) {
                    missing++;
                }
            }
        }
        return missing;
    }

    /** Sections vanilla wants to draw this frame whose mesh isn't built yet. */
    private static int holes(Minecraft mc) {
        int holes = 0;
        for (SectionRenderDispatcher.RenderSection section : mc.levelRenderer.visibleSections()) {
            if (section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) {
                holes++;
            }
        }
        return holes;
    }

    private static int rgb(Vector3fc c) {
        return ARGB.color(0, (int) (c.x() * 255), (int) (c.y() * 255), (int) (c.z() * 255)) & 0xFFFFFF;
    }
}
