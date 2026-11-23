package io.github.chabelabela.worldengine.planet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import org.junit.jupiter.api.Test;

class LocalTimeTest {
    private static final int DAY = LocalTime.MINECRAFT_DAY;

    @Test
    void theReferenceMeridianKeepsTheWorldClock() {
        assertEquals(6_000, LocalTime.localTicks(6_000, 0.0, DAY));
        assertEquals(1_234_567, LocalTime.localTicks(1_234_567, 0.0, DAY));
    }

    @Test
    void theSunIsAheadToTheEast() {
        // A quarter turn east sees sunset when the reference meridian sees noon; a quarter turn west, sunrise.
        assertEquals(12_000, LocalTime.localTicks(6_000, Math.PI / 2, DAY));
        assertEquals(0, LocalTime.localTicks(6_000, -Math.PI / 2, DAY));
        // Half a turn away it is midnight.
        assertEquals(18_000, LocalTime.localTicks(6_000, Math.PI, DAY));
        assertEquals(18_000, LocalTime.localTicks(6_000, -Math.PI, DAY));
    }

    @Test
    void itIsNeverNegative() {
        assertEquals(DAY - 6_000, LocalTime.localTicks(0, -Math.PI / 2, DAY));
    }

    @Test
    void aSlowerPlanetKeepsItsOwnDay() {
        // The moon's day is eight Minecraft days: eight world days make one moon day.
        int moonDay = Planets.MOON.dayTicks();
        assertEquals(8 * DAY, moonDay);
        assertEquals(DAY, LocalTime.localTicks(8 * DAY, 0.0, moonDay));
        assertEquals(750, LocalTime.localTicks(6_000, 0.0, moonDay));
        assertEquals(750 + 6_000, LocalTime.localTicks(6_000, Math.PI / 2, moonDay));
    }

    @Test
    void formatsAsAClock() {
        assertEquals("06:00", LocalTime.format(0));
        assertEquals("12:00", LocalTime.format(6_000));
        assertEquals("00:00", LocalTime.format(18_000));
        assertEquals("05:59", LocalTime.format(23_999));
        assertEquals("18:30", LocalTime.format(12_500 + 24_000 * 7));
    }

    @Test
    void aDayMustLastSomeTicks() {
        assertThrows(IllegalArgumentException.class, () -> new PlanetSpec("x", 1L, PlanetKind.BARREN,
                new PlanetLayout(0, 0, 4_096), 0));
    }
}
