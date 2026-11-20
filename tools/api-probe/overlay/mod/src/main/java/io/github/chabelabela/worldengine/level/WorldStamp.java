package io.github.chabelabela.worldengine.level;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.planet.TerrainStamp;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Saved with the universe level: the terrain and ABI versions its chunks were generated with. */
public final class WorldStamp extends SavedData {
    public static final Codec<WorldStamp> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("terrain_version").forGetter(s -> s.stamp.terrainVersion()),
            Codec.INT.fieldOf("abi_version").forGetter(s -> s.stamp.abiVersion()))
            .apply(i, (terrain, abi) -> new WorldStamp(new TerrainStamp(terrain, abi))));
    public static final SavedDataType<WorldStamp> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(WorldEngineMod.MOD_ID, "world_stamp"), WorldStamp::new, CODEC, null);

    private TerrainStamp stamp;

    public WorldStamp() {
        this(TerrainStamp.NONE);
    }

    private WorldStamp(TerrainStamp stamp) {
        this.stamp = stamp;
    }

    public TerrainStamp stamp() {
        return stamp;
    }

    /**
     * Checks this world against the running core, and stamps it if it has no stamp yet. Returns a warning to
     * log, or null. A world keeps its first stamp, so the warning repeats on every start.
     */
    public String reconcile(TerrainStamp current) {
        String warning = stamp.warningFor(current);
        if (!stamp.isStamped()) {
            stamp = current;
            setDirty();
        }
        return warning;
    }
}
