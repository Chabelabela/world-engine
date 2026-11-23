package io.github.chabelabela.worldengine.level;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import io.github.chabelabela.worldengine.planet.BiomeTable;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Biomes of the universe level: each planet column's {@link PlanetBiome} through {@link BiomeTable}, and
 * {@code worldengine:void} off the planets. A column in an overlap band has its twin's biome.
 *
 * <p>Biomes depend on the world seed, which vanilla only passes to biome sources inside a climate sampler.
 * {@link UniverseChunkGenerator#createState} binds it here before the level asks for any biome. Chunk
 * generation doesn't use this path: the generator fills each chunk's biomes from one batched call.
 */
public final class UniverseBiomeSource extends BiomeSource {
    public static final MapCodec<UniverseBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            RegistryOps.<Biome, UniverseBiomeSource>retrieveGetter(Registries.BIOME))
            .apply(i, UniverseBiomeSource::new));

    private final List<Holder<Biome>> byPlanetBiome;
    private final Holder<Biome> voidBiome;
    private volatile Long worldSeed;

    public UniverseBiomeSource(HolderGetter<Biome> biomes) {
        List<Holder<Biome>> holders = new ArrayList<>();
        for (PlanetBiome biome : PlanetBiome.values()) {
            holders.add(lookup(biomes, BiomeTable.biomeId(biome)));
        }
        this.byPlanetBiome = List.copyOf(holders);
        this.voidBiome = lookup(biomes, BiomeTable.VOID);
    }

    private static Holder<Biome> lookup(HolderGetter<Biome> biomes, String id) {
        return biomes.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(id)));
    }

    Holder<Biome> holder(PlanetBiome biome) {
        return byPlanetBiome.get(biome.ordinal());
    }

    Holder<Biome> voidBiome() {
        return voidBiome;
    }

    void bindWorldSeed(long seed) {
        worldSeed = seed;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(byPlanetBiome.stream(), Stream.of(voidBiome)).distinct();
    }

    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        return (quartX, quartY, quartZ) -> biomeAt(QuartPos.toBlock(quartX) + 2, QuartPos.toBlock(quartZ) + 2);
    }

    /** The biome of block column (x, z), sampled at that one column (in a band, at its twin). */
    Holder<Biome> biomeAt(int x, int z) {
        if (Planets.at(x, z) == null && PlanetTerrain.bands().at(x, z) == null) {
            return voidBiome;
        }
        Long seed = worldSeed;
        if (seed == null) {
            throw new IllegalStateException("World Engine biomes were asked for before the level was created");
        }
        return holder(PlanetTerrain.at(x, z, seed).biome());
    }
}
