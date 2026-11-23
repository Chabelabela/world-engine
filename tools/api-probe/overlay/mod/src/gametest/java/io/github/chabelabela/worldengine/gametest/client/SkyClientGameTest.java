package io.github.chabelabela.worldengine.gametest.client;

import io.github.chabelabela.worldengine.client.UniverseSky;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The universe level's sky in a real client (M1f): a World Engine world is created, and at two places the test
 * checks where the tilted sky puts the noon sun and takes screenshots for a person to look at (CI uploads them).
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
            // Spawn: Minecraft's own sky, the noon sun straight up.
            goTo(context, singleplayer, "time set 6000", "tp @a 0 400 0 0 -60");
            expectNoonSun(context, 0.0F, 1.0F, 0.0F, "at spawn");
            context.takeScreenshot("worldengine-spawn-noon-south-up");
            goTo(context, singleplayer, "time set 300", "tp @a 0 400 0 -90 -10");
            context.takeScreenshot("worldengine-spawn-sunrise-east");
            // Just over North's edge with E1, 45 degrees north: the noon sun stands 45 degrees high, due south.
            goTo(context, singleplayer, "time set 6000", "tp @a 0 400 -8300 0 -30");
            expectNoonSun(context, 0.0F, 0.71F, 0.71F, "45 degrees north");
            context.takeScreenshot("worldengine-north45-noon-south");
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
}
