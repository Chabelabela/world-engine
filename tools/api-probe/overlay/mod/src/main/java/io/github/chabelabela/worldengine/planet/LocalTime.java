package io.github.chabelabela.worldengine.planet;

import java.util.Locale;

/**
 * Local time of day on a spinning planet (M1f). The world's day clock keeps the time at the home planet's
 * reference meridian, the centre of face E1, where the world spawns. Elsewhere the sun is ahead by the longitude's
 * share of a day, and on a planet whose day isn't Minecraft's the clock runs at that planet's pace.
 *
 * <p>Times are in Minecraft's day ticks: 0 is sunrise, 6,000 noon, 12,000 sunset and 18,000 midnight.
 */
public final class LocalTime {
    /** Ticks in a Minecraft day, and the period of its day timeline. */
    public static final int MINECRAFT_DAY = 24_000;

    private LocalTime() {
    }

    /**
     * The local time, in day ticks counted like the world clock, at {@code longitude} (radians east of the reference
     * meridian) on a planet whose day lasts {@code dayTicks}, when the world clock reads {@code clockTicks}. Never
     * negative: before the first local sunrise it counts from the end of the first day instead.
     */
    public static long localTicks(long clockTicks, double longitude, int dayTicks) {
        long scaled = Math.floorDiv(clockTicks, dayTicks) * MINECRAFT_DAY
                + Math.floorMod(clockTicks, dayTicks) * (long) MINECRAFT_DAY / dayTicks;
        long local = scaled + Math.round(longitude / (2 * Math.PI) * MINECRAFT_DAY);
        return local < 0 ? Math.floorMod(local, MINECRAFT_DAY) : local;
    }

    /** {@code ticks} as a 24-hour clock time, "HH:MM": day tick 0 is 06:00. */
    public static String format(long ticks) {
        long minutes = Math.floorMod(ticks + 6_000, MINECRAFT_DAY) * 1_440 / MINECRAFT_DAY;
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
