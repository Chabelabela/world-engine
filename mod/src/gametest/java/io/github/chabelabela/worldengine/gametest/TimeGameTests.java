package io.github.chabelabela.worldengine.gametest;

import io.github.chabelabela.worldengine.level.LocalSkies;
import io.github.chabelabela.worldengine.level.UniverseTime;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.PlanetSpec;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.timeline.Timeline;
import net.minecraft.world.timeline.Timelines;

/**
 * Local time of day (M1f), on Minecraft's own day timeline. The GameTest level isn't a universe level, so each test
 * builds the universe level's timeline layers itself ({@link UniverseTime#addTimelineLayers}, as the mixin does for
 * a universe level) on a stopped clock, and samples them at the home planet's net coordinates.
 */
public class TimeGameTests {
    private static final long NOON = 6_000;

    @GameTest
    public void theSunFollowsLongitude(GameTestHelper helper) {
        EnvironmentAttributeSystem day = dayAt(helper, NOON);
        // Noon at the reference meridian, E1's centre, where the world spawns.
        checkAngle(helper, sunAngle(day, centre(Planets.HOME, PlanetFace.E1)), 0, "noon on E1");
        // Half a turn round the planet it is midnight.
        checkAngle(helper, sunAngle(day, centre(Planets.HOME, PlanetFace.E3)), 180, "midnight on E3");
        // A quarter turn east the sun is setting, and a quarter turn west it is rising, symmetrically.
        float east = sunAngle(day, centre(Planets.HOME, PlanetFace.E2));
        float west = sunAngle(day, centre(Planets.HOME, PlanetFace.E0));
        check(helper, east > 0 && east < 180, "the sun is going down on E2: " + east);
        checkAngle(helper, east + west, 360, "sunset on E2 mirrors sunrise on E0");
        // Sky light follows: day on E1, night on E3.
        float dayLight = day.getValue(EnvironmentAttributes.SKY_LIGHT_LEVEL, centre(Planets.HOME, PlanetFace.E1));
        float nightLight = day.getValue(EnvironmentAttributes.SKY_LIGHT_LEVEL, centre(Planets.HOME, PlanetFace.E3));
        check(helper, dayLight > nightLight, "sky light " + dayLight + " by day, " + nightLight + " by night");
        helper.succeed();
    }

    @GameTest
    public void withoutAPlaceItIsTheWorldClock(GameTestHelper helper) {
        EnvironmentAttributeSystem day = dayAt(helper, NOON);
        // A positional sample first must not leave a local value in the cache for the position-free one.
        checkAngle(helper, sunAngle(day, centre(Planets.HOME, PlanetFace.E3)), 180, "midnight on E3");
        checkAngle(helper, day.getDimensionValue(EnvironmentAttributes.SUN_ANGLE), 0, "the level's own value");
        checkAngle(helper, sunAngle(day, centre(Planets.HOME, PlanetFace.E1)), 0, "noon on E1");
        checkAngle(helper, sunAngle(day, new Vec3(70_000, 100, 0)), 0, "empty space");
        helper.succeed();
    }

    @GameTest
    public void aBandHasItsTwinsTime(GameTestHelper helper) {
        EnvironmentAttributeSystem day = dayAt(helper, NOON + 1_234);
        PlanetLayout home = Planets.HOME.layout();
        int n = Planets.HOME.cells();
        double z = home.faceOriginZ(PlanetFace.E3) + 5_000.5;
        // E3's east band copies E0's west strip, unturned: 40 blocks into the band is 40 blocks into E0.
        float band = sunAngle(day, new Vec3(home.faceOriginX(PlanetFace.E3) + n + 40.5, 100, z));
        float twin = sunAngle(day, new Vec3(home.faceOriginX(PlanetFace.E0) + 40.5, 100, z));
        check(helper, band == twin, "band " + band + ", twin " + twin);
        helper.succeed();
    }

    /**
     * The sky is continuous where a face meets its turned band, at the edge's midpoint. (Elsewhere the grid lines kink
     * at the seam, by up to 60 degrees near a cube's corner, and a band's sky is its twin's: see the plan's Known
     * limits.)
     */
    @GameTest
    public void theSkyCarriesOnAcrossATurnedSeam(GameTestHelper helper) {
        // North's west band copies E0's north strip, turned three quarter turns.
        PlanetLayout home = Planets.HOME.layout();
        double x0 = home.faceOriginX(PlanetFace.NORTH);
        double z = home.faceOriginZ(PlanetFace.NORTH) + Planets.HOME.cells() / 2 + 0.5;
        LocalSkies.Place onFace = LocalSkies.at(x0 + 0.5, z);
        LocalSkies.Place inBand = LocalSkies.at(x0 - 0.5, z);
        check(helper, onFace != null && inBand != null, "both sides have a sky");
        double[] a = onFace.sky().tilt();
        double[] b = inBand.sky().tilt();
        for (int i = 0; i < 9; i++) {
            check(helper, Math.abs(a[i] - b[i]) < 1e-3,
                    "tilt[" + i + "]: " + a[i] + " on the face, " + b[i] + " in the band");
        }
        helper.succeed();
    }

    @GameTest
    public void theMoonKeepsItsOwnSlowerDay(GameTestHelper helper) {
        // A moon day lasts eight world days: at world noon its spawn meridian has had an eighth of a morning.
        int eighth = (int) (NOON * Planets.HOME.dayTicks() / Planets.MOON.dayTicks());
        float moon = sunAngle(dayAt(helper, NOON), centre(Planets.MOON, PlanetFace.E1));
        float home = sunAngle(dayAt(helper, eighth), centre(Planets.HOME, PlanetFace.E1));
        checkAngle(helper, moon, home, "the moon at world noon is home at tick " + eighth);
        helper.succeed();
    }

    /** The universe level's day timeline alone, on a world clock stopped at {@code clockTicks}. */
    private static EnvironmentAttributeSystem dayAt(GameTestHelper helper, long clockTicks) {
        Holder<Timeline> day = helper.getLevel().registryAccess().lookupOrThrow(Registries.TIMELINE)
                .getOrThrow(Timelines.OVERWORLD_DAY);
        EnvironmentAttributeSystem.Builder builder = EnvironmentAttributeSystem.builder();
        UniverseTime.addTimelineLayers(builder, HolderSet.direct(day), clock -> new Stopped(clockTicks));
        return builder.build();
    }

    private record Stopped(long totalTicks) implements ClockInstance {
        @Override
        public float partialTick() {
            return 0.0F;
        }

        @Override
        public float rate() {
            return 1.0F;
        }

        @Override
        public boolean isPaused() {
            return true;
        }
    }

    private static Vec3 centre(PlanetSpec planet, PlanetFace face) {
        return new Vec3(planet.layout().faceOriginX(face) + planet.cells() / 2.0, 100,
                planet.layout().faceOriginZ(face) + planet.cells() / 2.0);
    }

    private static float sunAngle(EnvironmentAttributeSystem system, Vec3 position) {
        return system.getValue(EnvironmentAttributes.SUN_ANGLE, position);
    }

    private static void checkAngle(GameTestHelper helper, double degrees, double expected, String what) {
        check(helper, Math.abs(Math.IEEEremainder(degrees - expected, 360)) < 0.01,
                String.format(Locale.ROOT, "%s: sun angle %.3f, expected %.3f", what, degrees, expected));
    }

    private static void check(GameTestHelper helper, boolean condition, String what) {
        helper.assertTrue(condition, what);
    }
}
