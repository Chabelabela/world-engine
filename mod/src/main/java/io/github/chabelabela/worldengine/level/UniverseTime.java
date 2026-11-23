package io.github.chabelabela.worldengine.level;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.planet.LocalTime;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.timeline.Timeline;

/**
 * Local time of day in the universe level (M1f). Minecraft drives the day through timelines (sky colour, sky light,
 * sun and moon angles, monsters burning, villager schedules, ...) sampled from the world's day clock. In the
 * universe level each timeline instead reads the clock as it stands where it is sampled: {@link LocalTime} for the
 * planet and longitude there ({@link LocalSkies}).
 *
 * <p>How: the level's timeline layers get {@link LocalClocks}, and each timeline attribute also gets a positional
 * layer that changes nothing, so Minecraft samples it per position. While a value is sampled at a position (through
 * {@code EnvironmentAttributeValueSamplerMixin}) the clocks read that position's local time; values asked for
 * without a position, such as the level's overall sky darkness, keep the world clock: the reference meridian's time.
 */
public final class UniverseTime {
    /** The universe level's dimension type (data/worldengine/dimension_type/universe.json). */
    public static final ResourceKey<DimensionType> UNIVERSE =
            ResourceKey.create(Registries.DIMENSION_TYPE, WorldEngineMod.id("universe"));

    private static final ThreadLocal<Vec3> SAMPLED = new ThreadLocal<>();

    private UniverseTime() {
    }

    /** True if {@code level} is a World Engine universe level, on the server or the client. */
    public static boolean isUniverse(Level level) {
        return level.dimensionTypeRegistration().is(UNIVERSE);
    }

    /** Runs {@code body} as sampling environment attributes at {@code position}. */
    public static <T> T sampledAt(Vec3 position, Supplier<T> body) {
        Vec3 outer = SAMPLED.get();
        SAMPLED.set(position);
        try {
            return body.get();
        } finally {
            if (outer == null) {
                SAMPLED.remove();
            } else {
                SAMPLED.set(outer);
            }
        }
    }

    /** True while an attribute is being sampled at a position on this thread. */
    public static boolean sampling() {
        return SAMPLED.get() != null;
    }

    /** Adds {@code timelines} to {@code builder} as the universe level's: on local time, sampled per position. */
    public static void addTimelineLayers(EnvironmentAttributeSystem.Builder builder, HolderSet<Timeline> timelines,
            ClockManager worldClocks) {
        ClockManager local = new LocalClocks(worldClocks);
        for (Holder<Timeline> timeline : timelines) {
            builder.addTimelineLayer(timeline, local);
            for (EnvironmentAttribute<?> attribute : timeline.value().attributes()) {
                samplePerPosition(builder, attribute);
            }
        }
    }

    private static <V> void samplePerPosition(EnvironmentAttributeSystem.Builder builder,
            EnvironmentAttribute<V> attribute) {
        builder.addPositionalLayer(attribute, (value, position, interpolator) -> value);
    }

    /** Clocks that read the local time where an attribute is being sampled, or the world's time elsewhere. */
    public static final class LocalClocks implements ClockManager {
        private final ClockManager world;

        LocalClocks(ClockManager world) {
            this.world = world;
        }

        @Override
        public ClockInstance getInstance(Holder<WorldClock> clock) {
            ClockInstance instance = world.getInstance(clock);
            Vec3 position = SAMPLED.get();
            if (position == null) {
                return instance;
            }
            LocalSkies.Place place = LocalSkies.at(position.x, position.z);
            if (place == null) {
                return instance;
            }
            return new LocalClock(LocalTime.localTicks(instance.totalTicks(), place.sky().longitude(),
                    place.planet().dayTicks()), instance);
        }
    }

    private record LocalClock(long totalTicks, ClockInstance world) implements ClockInstance {
        @Override
        public float partialTick() {
            return world.partialTick();
        }

        @Override
        public float rate() {
            return world.rate();
        }

        @Override
        public boolean isPaused() {
            return world.isPaused();
        }
    }
}
