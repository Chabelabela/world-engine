package io.github.chabelabela.worldengine;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldEngineMod implements ModInitializer {
    public static final String MOD_ID = "worldengine";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("World Engine loading");
    }
}
