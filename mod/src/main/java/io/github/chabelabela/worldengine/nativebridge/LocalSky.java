package io.github.chabelabela.worldengine.nativebridge;

/**
 * How a point on a planet sees the sky ({@code we_local_sky}): its {@code longitude} (radians east of face E1's
 * centre, in (-π, π]), its {@code latitude} (radians), and the {@code tilt} that turns Minecraft's sky, as seen from
 * the equator, into the sky over the point, in the point's grid axes: a 3x3 rotation, column by column (the x, y and
 * z columns, three numbers each), the order JOML's {@code Matrix3f} constructor takes. It holds an array, so compare
 * tilts with {@code Arrays.equals}.
 */
public record LocalSky(double longitude, double latitude, double[] tilt) {
    public LocalSky {
        if (tilt.length != 9) {
            throw new IllegalArgumentException("tilt must have 9 numbers");
        }
    }

    /** {@code tilt} times ({@code x}, {@code y}, {@code z}). */
    public double[] apply(double x, double y, double z) {
        return new double[] {
            tilt[0] * x + tilt[3] * y + tilt[6] * z,
            tilt[1] * x + tilt[4] * y + tilt[7] * z,
            tilt[2] * x + tilt[5] * y + tilt[8] * z,
        };
    }
}
