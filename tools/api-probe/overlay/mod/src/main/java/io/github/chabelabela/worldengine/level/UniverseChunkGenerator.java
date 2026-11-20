package io.github.chabelabela.worldengine.level;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.chabelabela.worldengine.nativebridge.PlanetBiome;
import io.github.chabelabela.worldengine.nativebridge.PlanetKind;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.ColumnMaterials;
import io.github.chabelabela.worldengine.planet.FaceCell;
import io.github.chabelabela.worldengine.planet.Ground;
import io.github.chabelabela.worldengine.planet.LevelShape;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.Planets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * The universe level's generator: planet terrain from the native core where a chunk lies on a planet's
 * net (see {@link Planets}), empty space everywhere else. Vanilla then decorates the terrain through the
 * biomes (trees, grass, ores, mobs) and places structures.
 *
 * <p>Each chunk costs one batched native call for its biomes and one for its blocks, about 0.25 ms each.
 */
public final class UniverseChunkGenerator extends ChunkGenerator {
    public static final MapCodec<UniverseChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            RegistryOps.<Biome, UniverseChunkGenerator>retrieveGetter(Registries.BIOME))
            .apply(i, UniverseChunkGenerator::new));

    private final UniverseBiomeSource biomes;
    private volatile Spawn spawn;

    private record Spawn(long worldSeed, ChunkPos chunk) {
    }

    public UniverseChunkGenerator(HolderGetter<Biome> biomeGetter) {
        this(new UniverseBiomeSource(biomeGetter));
    }

    private UniverseChunkGenerator(UniverseBiomeSource biomes) {
        super(biomes);
        this.biomes = biomes;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /** Called once when the level is created, before anything asks for biomes or terrain. */
    @Override
    public ChunkGeneratorStructureState createState(
            HolderLookup<StructureSet> structureSets, RandomState randomState, long seed) {
        PlanetTerrain.core(); // fail early, with a clear message, if the core is missing
        biomes.bindWorldSeed(seed);
        return super.createState(structureSets, randomState, seed);
    }

    /** Where the world spawn (and stronghold rings) centre: dry land near the home planet's E1 centre. */
    @Override
    public ChunkPos getOrigin(RandomState randomState) {
        long seed = randomState.seed();
        Spawn cached = spawn;
        if (cached == null || cached.worldSeed() != seed) {
            int[] chunk = PlanetTerrain.landChunk(Planets.HOME, seed);
            cached = new Spawn(seed, new ChunkPos(chunk[0], chunk[1]));
            spawn = cached;
        }
        return cached.chunk();
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState randomState, Blender blender, StructureManager structures, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        PlanetHit hit = Planets.at(minX, minZ);
        if (hit == null) {
            Holder<Biome> space = biomes.voidBiome();
            chunk.fillBiomesFromNoise((quartX, quartY, quartZ) -> space);
        } else {
            TerrainColumns columns = PlanetTerrain.sample(hit, randomState.seed(), 16, 16);
            chunk.fillBiomesFromNoise((quartX, quartY, quartZ) -> {
                // Sample each 4x4 quart at its centre column, as UniverseBiomeSource#createResolver does.
                int dx = Math.clamp((quartX << 2) + 2 - minX, 0, 15);
                int dz = Math.clamp((quartZ << 2) + 2 - minZ, 0, 15);
                return biomes.holder(columns.biome(dx, dz));
            });
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
            StructureManager structures, BiomeManager biomeManager, WorldGenRegion region,
            Set<Holder<Biome>> biomesInChunk) {
        ChunkPos pos = chunk.getPos();
        PlanetHit hit = Planets.at(pos.getMinBlockX(), pos.getMinBlockZ());
        if (hit == null) {
            return CompletableFuture.completedFuture(chunk);
        }
        TerrainColumns columns = PlanetTerrain.sample(hit, randomState.seed(), 16, 16);
        PlanetKind kind = hit.planet().kind();
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = Math.max(chunk.getMinY(), LevelShape.MIN_Y);
        int maxY = Math.min(chunk.getMaxY(), LevelShape.maxY());
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int height = columns.height(dx, dz);
                PlanetBiome biome = columns.biome(dx, dz);
                int top = Math.min(ColumnMaterials.topY(height, kind), maxY);
                for (int y = minY; y <= top; y++) {
                    Ground ground = ColumnMaterials.at(y, height, biome, kind);
                    if (ground != Ground.AIR) {
                        chunk.getSection(chunk.getSectionIndex(y))
                                .setBlockState(dx, y & 15, dz, GroundStates.of(ground), false);
                    }
                }
                // A heightmap keeps the highest block that matches it, so only the column's top blocks matter:
                // the solid surface for the ocean floor, and the water or the surface for the world surface.
                int surface = Math.min(LevelShape.surfaceY(height), maxY);
                if (surface >= minY) {
                    oceanFloor.update(dx, surface, dz,
                            GroundStates.of(ColumnMaterials.at(surface, height, biome, kind)));
                }
                if (top >= minY) {
                    worldSurface.update(dx, top, dz, GroundStates.of(ColumnMaterials.at(top, height, biome, kind)));
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            return level.getMinY();
        }
        TerrainColumns column = PlanetTerrain.column(hit, randomState.seed());
        int height = column.height(0, 0);
        PlanetBiome biome = column.biome(0, 0);
        PlanetKind kind = hit.planet().kind();
        for (int y = Math.min(ColumnMaterials.topY(height, kind), level.getMaxY()); y >= level.getMinY(); y--) {
            if (type.isOpaque().test(GroundStates.of(ColumnMaterials.at(y, height, biome, kind)))) {
                return y + 1;
            }
        }
        return level.getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        BlockState[] states = new BlockState[level.getHeight()];
        PlanetHit hit = Planets.at(x, z);
        if (hit == null) {
            Arrays.fill(states, GroundStates.of(Ground.AIR));
        } else {
            TerrainColumns column = PlanetTerrain.column(hit, randomState.seed());
            for (int i = 0; i < states.length; i++) {
                states[i] = GroundStates.of(ColumnMaterials.at(
                        level.getMinY() + i, column.height(0, 0), column.biome(0, 0), hit.planet().kind()));
            }
        }
        return new NoiseColumn(level.getMinY(), states);
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // As NoiseBasedChunkGenerator does: passive mobs from the biome when a chunk is first generated.
        ChunkPos pos = region.getCenter();
        BlockPos top = pos.getWorldPosition().atY(region.getMaxY());
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(region.getSeed(), pos.getMinBlockX(), pos.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(region, top, pos, random);
    }

    @Override
    public int getGenDepth() {
        return LevelShape.HEIGHT;
    }

    @Override
    public int getSeaLevel() {
        return LevelShape.SEA_LEVEL;
    }

    @Override
    public int getMinY() {
        return LevelShape.MIN_Y;
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos, SamplerContext context) {
        PlanetHit hit = Planets.at(pos.getX(), pos.getZ());
        if (hit == null) {
            info.add("World Engine: empty space");
        } else {
            FaceCell cell = hit.cell();
            info.add(String.format(Locale.ROOT, "World Engine: %s, face %s, cell %d %d",
                    hit.planet().name(), cell.face(), cell.x(), cell.z()));
        }
    }
}
