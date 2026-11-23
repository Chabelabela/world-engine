package io.github.chabelabela.worldengine.gametest.client;

import io.github.chabelabela.worldengine.client.UniverseSky;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The universe level's sky in a real client (M1f): a World Engine world is created, and at four views in two places
 * the test checks the camera's tilt and, from the screenshots, where the sun is actually drawn. The screenshots are
 * also for a person to look at (CI uploads them).
 * Runs with {@code ./gradlew runClientGameTest}; it needs a display (CI uses a virtual one).
 */
public class SkyClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(creator -> creator.setWorldType(creator.getNormalPresetList().stream()
                        .filter(type -> type.preset() != null
                                && type.preset().is(Identifier.fromNamespaceAndPath("worldengine", "universe")))
                        .findFirst().orElseThrow()))
                .create()) {
            singleplayer.getServer().runCommand("gamemode spectator @a");
            singleplayer.getServer().runCommand("weather clear");
            // Spawn: Minecraft's own sky, the noon sun straight up.
            goTo(context, singleplayer, "time set 6000", "tp @a 0 400 0 0 -60");
            expectNoonSun(context, 0.0F, 1.0F, 0.0F, "at spawn");
            Path spawnNoon = context.takeScreenshot("worldengine-spawn-noon-south-up");
            expectSunAt(spawnNoon, 0.25F, 0.0F, "at spawn, noon");
            goTo(context, singleplayer, "time set 300", "tp @a 0 400 0 -90 -10");
            Path spawnSunrise = context.takeScreenshot("worldengine-spawn-sunrise-east");
            expectSunAt(spawnSunrise, 0.5F, 0.25F, "at spawn, sunrise");
            // Just over North's edge with E1, 45 degrees north: the noon sun stands 45 degrees high, due south.
            goTo(context, singleplayer, "time set 6000", "tp @a 0 400 -8300 0 -30");
            expectNoonSun(context, 0.0F, 0.71F, 0.71F, "45 degrees north");
            Path northNoon = context.takeScreenshot("worldengine-north45-noon-south");
            expectSunAt(northNoon, 0.5F, 0.2F, "45 degrees north, noon");
            goTo(context, singleplayer, "time set 18000", "tp @a 0 400 -8300 180 -40");
            context.takeScreenshot("worldengine-north45-midnight-north");
        }
    }

    private static void goTo(ClientGameTestContext context, TestSingleplayerContext singleplayer, String time,
            String teleport) {
        singleplayer.getServer().runCommand(time);
        singleplayer.getServer().runCommand(teleport);
        context.waitTicks(40);
    }

    /** Where the camera's sky puts the sun at local noon (Minecraft's zenith, tilted), within 0.02. */
    private static void expectNoonSun(ClientGameTestContext context, float x, float y, float z, String where) {
        Vector3f noon = context.computeOnClient(minecraft -> {
            Matrix4f tilt = UniverseSky.tiltAt(minecraft.level, minecraft.gameRenderer.mainCamera().position());
            return tilt == null ? null : tilt.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
        });
        if (noon == null || Math.abs(noon.x - x) > 0.02F || Math.abs(noon.y - y) > 0.02F
                || Math.abs(noon.z - z) > 0.02F) {
            throw new AssertionError("noon sun " + where + ": " + noon + ", expected (" + x + ", " + y + ", " + z + ")");
        }
    }

    /** The centre of the sun's disc, the pure white pixels in the upper half, as fractions of the screenshot's size. */
    private static float[] sunIn(Path screenshot) {
        BufferedImage image;
        try {
            image = ImageIO.read(screenshot.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long sumX = 0;
        long sumY = 0;
        long count = 0;
        for (int y = 0; y < image.getHeight() / 2; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if ((rgb >> 16 & 0xFF) >= 250 && (rgb >> 8 & 0xFF) >= 250 && (rgb & 0xFF) >= 250) {
                    sumX += x;
                    sumY += y;
                    count++;
                }
            }
        }
        if (count == 0) {
            throw new AssertionError("no sun in " + screenshot.getFileName());
        }
        return new float[] {(float) sumX / count / image.getWidth(), (float) sumY / count / image.getHeight()};
    }

    /** The sun is drawn in the middle of the screenshot, between {@code minY} and {@code maxY} down from the top. */
    private static void expectSunAt(Path shot, float maxY, float minY, String where) {
        float[] sun = sunIn(shot);
        if (Math.abs(sun[0] - 0.5F) >= 0.05F || sun[1] < minY || sun[1] > maxY) {
            throw new AssertionError("sun " + where + ": at (" + sun[0] + ", " + sun[1]
                    + ") of the screenshot, expected x near 0.5 and y in [" + minY + ", " + maxY + "]");
        }
    }
}
