package io.github.chabelabela.worldengine.gametest;

import io.github.chabelabela.worldengine.WorldEngineMod;
import io.github.chabelabela.worldengine.level.PlanetTerrain;
import io.github.chabelabela.worldengine.level.UniverseChunkGenerator;
import io.github.chabelabela.worldengine.level.WorldStamp;
import io.github.chabelabela.worldengine.nativebridge.TerrainColumns;
import io.github.chabelabela.worldengine.planet.BiomeTable;
import io.github.chabelabela.worldengine.planet.ColumnMaterials;
import io.github.chabelabela.worldengine.planet.LevelShape;
import io.github.chabelabela.worldengine.planet.PlanetFace;
import io.github.chabelabela.worldengine.planet.PlanetHit;
import io.github.chabelabela.worldengine.planet.PlanetLayout;
import io.github.chabelabela.worldengine.planet.Planets;
import io.github.chabelabela.worldengine.planet.SeamBand;
import io.github.chabelabela.worldengine.planet.SeamBands;
import io.github.chabelabela.worldengine.planet.TerrainStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.WorldPresetTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * World generation checks inside a real server. The GameTest server's own world is flat, so these tests take
 * the {@code worldengine:universe} world preset from its registry (decoded from the mod's data pack, exactly
 * as a new world would be), and run its generator on stand-alone chunks.
 */
public class UniverseGameTests {
    private static final ResourceKey<WorldPreset> PRESET =
            ResourceKey.create(Registries.WORLD_PRESET, WorldEngineMod.id("universe"));
    /** A column on each face of the home planet (E1, E0, E2, E3, North, South), and one on the moon. */
    private static final int[][] COLUMNS = {
        {3, 5}, {-9_000, 1_234}, {9_000, -4_321}, {30_000, 2_000}, {100, -9_000}, {-200, 9_500}, {106_100, 40}};

    @GameTest
    public void worldTypeIsRegistered(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Holder<WorldPreset> preset = registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(PRESET);
        check(helper, preset.is(WorldPresetTags.NORMAL), "the preset is listed on the Create World screen");
        LevelStem overworld = preset.value().overworld().orElseThrow();
        check(helper, overworld.generator() instanceof UniverseChunkGenerator, "the overworld uses the universe generator");
        DimensionType type = overworld.type().value();
        check(helper, type.minY() == LevelShape.MIN_Y && type.height() == LevelShape.HEIGHT, "the level is y -384..895");
        for (String id : new String[] {BiomeTable.VOID, "worldengine:barren"}) {
            check(helper, registries.lookupOrThrow(Registries.BIOME).get(biome(id)).isPresent(), id + " is loaded");
        }
        helper.succeed();
    }

    @GameTest
    public void chunksFollowTheCore(GameTestHelper helper) {
        Universe u = universe(helper);
        for (int[] c : COLUMNS) {
            ProtoChunk chunk = u.generate(new ChunkPos(c[0] >> 4, c[1] >> 4));
            PlanetHit hit = Planets.at(c[0], c[1]);
            TerrainColumns column = PlanetTerrain.column(hit, u.seed());
            int height = column.height(0, 0);
            String where = hit.planet().name() + " (" + c[0] + ", " + c[1] + ") at " + height + " m";
            var expected = u.generator().getBaseColumn(c[0], c[1], chunk, u.randomState());
            for (int y = LevelShape.MIN_Y; y <= LevelShape.maxY(); y++) {
                BlockPos pos = new BlockPos(c[0], y, c[1]);
                if (chunk.getBlockState(pos) != expected.getBlock(y)) {
                    helper.fail("block at y " + y + " of " + where + " is " + chunk.getBlockState(pos)
                            + ", expected " + expected.getBlock(y) + " (seed " + u.seed() + ")");
                }
            }
            check(helper, chunk.getBlockState(new BlockPos(c[0], LevelShape.MIN_Y, c[1])).is(Blocks.BEDROCK),
                    "bedrock floor at " + where);
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, c[0] & 15, c[1] & 15);
            check(helper, floor == LevelShape.surfaceY(height), "ocean floor heightmap at " + where + " is " + floor);
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, c[0] & 15, c[1] & 15);
            check(helper, top == ColumnMaterials.topY(height, hit.planet().kind()), "surface heightmap at " + where);
            int base = u.generator().getBaseHeight(c[0], c[1], Heightmap.Types.WORLD_SURFACE_WG, chunk, u.randomState());
            check(helper, base == top + 1, "getBaseHeight agrees with the chunk at " + where);
        }
        helper.succeed();
    }

    /** A band chunk is its twin chunk, turned: every block of every column, from bedrock to sky. */
    @GameTest
    public void bandsCopyTheirTwin(GameTestHelper helper) {
        Universe u = universe(helper);
        SeamBands bands = PlanetTerrain.bands();
        int n = Planets.HOME.cells();
        PlanetLayout home = Planets.HOME.layout();
        PlanetLayout moon = Planets.MOON.layout();
        int[][] bandColumns = {
            {home.faceOriginX(PlanetFace.E3) + n + 40, home.faceOriginZ(PlanetFace.E3) + 5_000}, // the equator wrap
            {home.faceOriginX(PlanetFace.NORTH) - 70, home.faceOriginZ(PlanetFace.NORTH) + 9_000}, // three quarter turns
            {moon.faceOriginX(PlanetFace.E2) + 1_000, moon.faceOriginZ(PlanetFace.E2) - 20}, // the moon
        };
        for (int[] c : bandColumns) {
            SeamBand band = bands.at(c[0], c[1]);
            check(helper, band != null, "(" + c[0] + ", " + c[1] + ") is in a band");
            ProtoChunk chunk = u.generate(new ChunkPos(c[0] >> 4, c[1] >> 4));
            int[] corner = band.twinBlock(c[0], c[1]);
            ProtoChunk twinChunk = u.generate(new ChunkPos(corner[0] >> 4, corner[1] >> 4));
            int minX = chunk.getPos().getMinBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    int[] twin = band.twinBlock(minX + dx, minZ + dz);
                    check(helper, twin[0] >> 4 == twinChunk.getPos().x() && twin[1] >> 4 == twinChunk.getPos().z(),
                            "a band chunk's twin is one chunk");
                    for (int y = LevelShape.MIN_Y; y <= LevelShape.maxY(); y++) {
                        BlockState here = chunk.getBlockState(new BlockPos(minX + dx, y, minZ + dz));
                        BlockState there = twinChunk.getBlockState(new BlockPos(twin[0], y, twin[1]));
                        if (here != there) {
                            helper.fail(band.planet().name() + " " + band.face() + " " + band.side() + " band block ("
                                    + (minX + dx) + ", " + y + ", " + (minZ + dz) + ") is " + here + ", its twin "
                                    + there);
                        }
                    }
                }
            }
            check(helper, noiseBiome(chunk, QuartPos.fromBlock(minX + 2), QuartPos.fromBlock(64),
                    QuartPos.fromBlock(minZ + 2)).equals(noiseBiome(twinChunk,
                    QuartPos.fromBlock(band.twinBlock(minX + 2, minZ + 2)[0]), QuartPos.fromBlock(64),
                    QuartPos.fromBlock(band.twinBlock(minX + 2, minZ + 2)[1]))), "the band has its twin's biome");
        }
        helper.succeed();
    }

    @GameTest
    public void spaceIsEmpty(GameTestHelper helper) {
        Universe u = universe(helper);
        int x = 70_000;
        int z = 0;
        check(helper, Planets.at(x, z) == null, "the test column is off every planet");
        ProtoChunk chunk = u.generate(new ChunkPos(x >> 4, z >> 4));
        for (LevelChunkSection section : chunk.getSections()) {
            check(helper, section.hasOnlyAir(), "no blocks in space");
        }
        check(helper, noiseBiome(chunk, QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z)).is(biome(BiomeTable.VOID)),
                "space is the void biome");
        helper.succeed();
    }

    @GameTest
    public void biomesFollowTheTable(GameTestHelper helper) {
        Universe u = universe(helper);
        for (int[] c : COLUMNS) {
            int qx = QuartPos.fromBlock(c[0]);
            int qy = QuartPos.fromBlock(64);
            int qz = QuartPos.fromBlock(c[1]);
            PlanetHit hit = Planets.at(QuartPos.toBlock(qx) + 2, QuartPos.toBlock(qz) + 2);
            String expected = BiomeTable.biomeId(PlanetTerrain.column(hit, u.seed()).biome(0, 0));
            ProtoChunk chunk = u.generate(new ChunkPos(c[0] >> 4, c[1] >> 4));
            check(helper, noiseBiome(chunk, qx, qy, qz).is(biome(expected)),
                    "chunk biome at (" + c[0] + ", " + c[1] + ") is " + expected);
            Holder<Biome> direct = u.generator().getBiomeSource().createUncachedResolver(u.randomState())
                    .getNoiseBiome(qx, qy, qz);
            check(helper, direct.is(biome(expected)), "biome source at (" + c[0] + ", " + c[1] + ") is " + expected);
        }
        helper.succeed();
    }

    @GameTest
    public void spawnIsOnDryLand(GameTestHelper helper) {
        Universe u = universe(helper);
        ChunkPos origin = u.generator().getOrigin(u.randomState());
        PlanetHit hit = Planets.at(origin.getMinBlockX() + 8, origin.getMinBlockZ() + 8);
        check(helper, hit != null && hit.planet() == Planets.HOME, "the spawn chunk is on the home planet (seed " + u.seed() + ")");
        int height = PlanetTerrain.column(hit, u.seed()).height(0, 0);
        check(helper, height >= 1, "the spawn column is dry land, got " + height + " m (seed " + u.seed() + ")");
        helper.succeed();
    }

    @GameTest
    public void generationIsFastEnough(GameTestHelper helper) {
        Universe u = universe(helper);
        for (int i = 0; i < 4; i++) {
            u.generate(new ChunkPos(100 + i, 0)); // warm up
        }
        int chunks = 64;
        long start = System.nanoTime();
        for (int i = 0; i < chunks; i++) {
            u.generate(new ChunkPos(i % 8, i / 8));
        }
        double ms = (System.nanoTime() - start) / 1e6 / chunks;
        WorldEngineMod.LOGGER.info("GameTest: biomes + terrain took {} ms per chunk", String.format(Locale.ROOT, "%.2f", ms));
        // Generous: it catches order-of-magnitude regressions on shared CI runners; vanilla's noise generator
        // takes a few ms per chunk.
        check(helper, ms < 50, "biomes + terrain took " + ms + " ms per chunk");
        helper.succeed();
    }

    @GameTest
    public void worldStampSurvivesASave(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        Path dir = Files.createTempDirectory("worldengine-stamp");
        try {
            try (SavedDataStorage storage = new SavedDataStorage(dir, level.getServer().getFixerUpper(), level.registryAccess())) {
                check(helper, storage.computeIfAbsent(WorldStamp.TYPE).reconcile(new TerrainStamp(1, 3)) == null,
                        "a new world is stamped without a warning");
                storage.saveAndJoin();
            }
            try (SavedDataStorage storage = new SavedDataStorage(dir, level.getServer().getFixerUpper(), level.registryAccess())) {
                WorldStamp stamp = storage.computeIfAbsent(WorldStamp.TYPE);
                check(helper, stamp.stamp().equals(new TerrainStamp(1, 3)), "the stamp was saved, got " + stamp.stamp());
                check(helper, stamp.reconcile(new TerrainStamp(2, 3)) != null, "a newer terrain version warns");
            }
        } finally {
            deleteQuietly(dir);
        }
        helper.succeed();
    }

    /** Deletes a directory tree, ignoring errors: it is only a test's scratch space. */
    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** The universe preset's generator, bound to the test world's seed as a real level would bind it. */
    private record Universe(UniverseChunkGenerator generator, RandomState randomState, ServerLevel level) {
        long seed() {
            return randomState.seed();
        }

        ProtoChunk generate(ChunkPos pos) {
            LevelHeightAccessor height = LevelHeightAccessor.create(LevelShape.MIN_Y, LevelShape.HEIGHT);
            ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, height,
                    PalettedContainerFactory.create(level.registryAccess()), null);
            generator.createBiomes(randomState, Blender.empty(), null, chunk).join();
            generator.buildTerrain(chunk, Blender.empty(), randomState, null, null, null, Set.of()).join();
            return chunk;
        }
    }

    private static Universe universe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryAccess registries = level.registryAccess();
        UniverseChunkGenerator generator = (UniverseChunkGenerator) registries.lookupOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(PRESET).value().overworld().orElseThrow().generator();
        RandomState randomState = level.getChunkSource().randomState();
        generator.createState(registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState, randomState.seed());
        return new Universe(generator, randomState, level);
    }

    /** The biome stored for a quart. Read from the section: a stand-alone chunk has no generation status. */
    private static Holder<Biome> noiseBiome(ProtoChunk chunk, int quartX, int quartY, int quartZ) {
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(QuartPos.toBlock(quartY)));
        return section.getNoiseBiome(quartX & 3, quartY & 3, quartZ & 3);
    }

    private static ResourceKey<Biome> biome(String id) {
        return ResourceKey.create(Registries.BIOME, Identifier.parse(id));
    }

    private static void check(GameTestHelper helper, boolean condition, String what) {
        helper.assertTrue(condition, what);
    }
}
