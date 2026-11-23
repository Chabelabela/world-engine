package io.github.chabelabela.worldengine.client;

import io.github.chabelabela.worldengine.level.LocalSkies;
import io.github.chabelabela.worldengine.level.UniverseTime;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The sky over the camera in the universe level (M1f). Minecraft draws the sun, moon and stars as an observer on the
 * equator sees them; {@link #tiltAt} turns that sky to the camera's latitude and grid, so the sky turns about the
 * planet's axis wherever the camera is.
 */
public final class UniverseSky {
    private UniverseSky() {
    }

    /**
     * The tilt for a camera at {@code position} in {@code level}, or null where the sky is Minecraft's own: outside
     * the universe level, and in empty space between the planets (so the sky turns back to Minecraft's when the
     * camera leaves a planet's net). In an overlap band, the band's own sky.
     */
    public static Matrix4f tiltAt(Level level, Vec3 position) {
        if (!UniverseTime.isUniverse(level)) {
            return null;
        }
        LocalSkies.Place place = LocalSkies.at(position.x, position.z);
        if (place == null) {
            return null;
        }
        double[] t = place.sky().tilt();
        return new Matrix4f(
                (float) t[0], (float) t[1], (float) t[2], 0.0F,
                (float) t[3], (float) t[4], (float) t[5], 0.0F,
                (float) t[6], (float) t[7], (float) t[8], 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
    }
}
