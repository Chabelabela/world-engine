package io.github.chabelabela.worldengine.planet;

import static io.github.chabelabela.worldengine.nativebridge.PlanetKind.BARREN;
import static io.github.chabelabela.worldengine.nativebridge.PlanetKind.TERRESTRIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import org.junit.jupiter.api.Test;

class ColumnMaterialsTest {
    @Test
    void landColumn() {
        // 10 m above the datum: top block at y = 72.
        assertEquals(Ground.BEDROCK, ColumnMaterials.at(LevelShape.MIN_Y, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(Ground.STONE, ColumnMaterials.at(68, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        for (int y = 69; y <= 71; y++) {
            assertEquals(Ground.DIRT, ColumnMaterials.at(y, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        }
        assertEquals(Ground.GRASS, ColumnMaterials.at(72, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(Ground.AIR, ColumnMaterials.at(73, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(72, ColumnMaterials.topY(10, TERRESTRIAL));
    }

    @Test
    void oceanColumnFillsWithWaterToSeaLevel() {
        // 20 m below the datum: sea floor at y = 42, water from 43 to 62.
        assertEquals(Ground.SAND, ColumnMaterials.at(42, -20, PlanetBiome.OCEAN, TERRESTRIAL));
        assertEquals(Ground.WATER, ColumnMaterials.at(43, -20, PlanetBiome.OCEAN, TERRESTRIAL));
        assertEquals(Ground.WATER, ColumnMaterials.at(62, -20, PlanetBiome.OCEAN, TERRESTRIAL));
        assertEquals(Ground.AIR, ColumnMaterials.at(63, -20, PlanetBiome.OCEAN, TERRESTRIAL));
        assertEquals(62, ColumnMaterials.topY(-20, TERRESTRIAL));
        assertEquals(Ground.GRAVEL, ColumnMaterials.at(-200, -262, PlanetBiome.DEEP_OCEAN, TERRESTRIAL));
    }

    @Test
    void grassUnderWaterBecomesDirt() {
        assertEquals(Ground.DIRT, ColumnMaterials.at(61, -1, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(Ground.WATER, ColumnMaterials.at(62, -1, PlanetBiome.PLAINS, TERRESTRIAL));
    }

    @Test
    void grassStaysGrassWithoutOceans() {
        assertEquals(Ground.GRASS, ColumnMaterials.at(LevelShape.surfaceY(-5), -5, PlanetBiome.PLAINS, BARREN));
    }

    @Test
    void topAndAirAgreeAtTheExtremes() {
        int[] heights = {TerrainColumns.MIN_HEIGHT, -1, 0, 1, TerrainColumns.MAX_HEIGHT};
        for (PlanetKind kind : PlanetKind.values()) {
            PlanetBiome biome = kind == TERRESTRIAL ? PlanetBiome.PLAINS : PlanetBiome.BARREN;
            for (int h : heights) {
                int topY = ColumnMaterials.topY(h, kind);
                String what = kind + " at height " + h;
                assertNotEquals(Ground.AIR, ColumnMaterials.at(topY, h, biome, kind), what);
                assertEquals(Ground.AIR, ColumnMaterials.at(topY + 1, h, biome, kind), what);
                assertEquals(Ground.BEDROCK, ColumnMaterials.at(LevelShape.MIN_Y, h, biome, kind), what);
            }
        }
    }

    @Test
    void beachAtTheDatumIsDry() {
        assertEquals(Ground.SAND, ColumnMaterials.at(62, 0, PlanetBiome.BEACH, TERRESTRIAL));
        assertEquals(Ground.AIR, ColumnMaterials.at(63, 0, PlanetBiome.BEACH, TERRESTRIAL));
        assertEquals(62, ColumnMaterials.topY(0, TERRESTRIAL));
    }

    @Test
    void barrenPlanetsHaveNoWater() {
        assertEquals(Ground.GRAVEL, ColumnMaterials.at(42, -20, PlanetBiome.BARREN, BARREN));
        assertEquals(Ground.STONE, ColumnMaterials.at(41, -20, PlanetBiome.BARREN, BARREN));
        assertEquals(Ground.AIR, ColumnMaterials.at(50, -20, PlanetBiome.BARREN, BARREN));
        assertEquals(42, ColumnMaterials.topY(-20, BARREN));
    }

    @Test
    void outsideTheLevelIsAir() {
        assertEquals(Ground.AIR, ColumnMaterials.at(LevelShape.MIN_Y - 1, 10, PlanetBiome.PLAINS, TERRESTRIAL));
        assertEquals(Ground.AIR, ColumnMaterials.at(LevelShape.maxY() + 1, 10, PlanetBiome.PLAINS, TERRESTRIAL));
    }

    @Test
    void everyBiomeHasASolidTopAndSubsoil() {
        for (PlanetBiome biome : PlanetBiome.values()) {
            for (int y = 47; y <= 50; y++) {
                Ground g = ColumnMaterials.at(y, -12, biome, TERRESTRIAL);
                assertNotEquals(Ground.AIR, g, biome + " at " + y);
                assertNotEquals(Ground.WATER, g, biome + " at " + y);
            }
        }
    }
}
