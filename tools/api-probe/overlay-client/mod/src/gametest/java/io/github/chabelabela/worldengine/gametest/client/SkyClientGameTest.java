package io.github.chabelabela.worldengine.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;

/** Screenshots of the universe level's sky (M1f), for a person to look at. */
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
            shoot(context, singleplayer, "spawn-noon-south-up", "time set 6000", "tp @a 0 400 0 0 -60");
            shoot(context, singleplayer, "spawn-sunrise-east", "time set 300", "tp @a 0 400 0 -90 -10");
            // Just over North's edge with E1, 45 degrees north: the noon sun stands 45 degrees high, due south.
            shoot(context, singleplayer, "north45-noon-south", "time set 6000", "tp @a 0 400 -8300 0 -30");
            // Its stars at midnight turn about a pole 45 degrees up in the north.
            shoot(context, singleplayer, "north45-midnight-north", "time set 18000", "tp @a 0 400 -8300 180 -40");
        }
    }

    private static void shoot(ClientGameTestContext context, TestSingleplayerContext singleplayer, String name,
            String time, String teleport) {
        singleplayer.getServer().runCommand(time);
        singleplayer.getServer().runCommand(teleport);
        context.waitTicks(40);
        context.takeScreenshot("worldengine-" + name);
    }
}
