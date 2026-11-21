package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiomeTableTest {
    @Test
    void everyPlanetBiomeHasANamespacedBiome() {
        for (PlanetBiome biome : PlanetBiome.values()) {
            String id = BiomeTable.biomeId(biome);
            assertTrue(id.matches("(minecraft|worldengine):[a-z_]+"), biome + " -> " + id);
        }
    }

    @Test
    void worldEngineBiomesShipWithTheMod() throws IOException {
        List<String> ids = new ArrayList<>();
        ids.add(BiomeTable.VOID);
        for (PlanetBiome biome : PlanetBiome.values()) {
            ids.add(BiomeTable.biomeId(biome));
        }
        for (String id : ids) {
            if (id.startsWith("worldengine:")) {
                String path = "/data/worldengine/worldgen/biome/" + id.substring("worldengine:".length()) + ".json";
                assertTrue(LevelShapeTest.resource(path).contains("\"features\""), path);
            }
        }
    }
}
