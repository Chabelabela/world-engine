package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LevelShapeTest {
    @Test
    void coversTheTerrainRangeWithRoomToSpare() {
        assertTrue(LevelShape.surfaceY(TerrainColumns.MIN_HEIGHT) - LevelShape.MIN_Y >= 32, "rock under the deepest sea");
        assertTrue(LevelShape.maxY() - LevelShape.surfaceY(TerrainColumns.MAX_HEIGHT) >= 100, "air over the highest peak");
        assertEquals(0, LevelShape.MIN_Y % 16);
        assertEquals(0, LevelShape.HEIGHT % 16);
        assertTrue(LevelShape.MIN_Y + LevelShape.HEIGHT <= 2_032, "vanilla's dimension height limit");
        assertEquals(62, LevelShape.surfaceY(0));
    }

    @Test
    void matchesTheDimensionType() throws IOException {
        String json = resource("/data/worldengine/dimension_type/universe.json");
        assertEquals(LevelShape.MIN_Y, intField(json, "min_y"));
        assertEquals(LevelShape.HEIGHT, intField(json, "height"));
        assertEquals(LevelShape.HEIGHT, intField(json, "logical_height"));
    }

    static String resource(String path) throws IOException {
        try (InputStream in = LevelShapeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int intField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        assertTrue(m.find(), "no field " + name);
        return Integer.parseInt(m.group(1));
    }
}
