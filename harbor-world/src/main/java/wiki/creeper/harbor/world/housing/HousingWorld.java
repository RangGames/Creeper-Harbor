package wiki.creeper.harbor.world.housing;

import java.util.List;
import java.util.Random;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.config.ServerRole;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.content.WorldBalance;

/**
 * Loads or creates the housing world (balance.yml {@code world.housing.world}) on the server that
 * hosts it: the server named by {@code world.housing.server}, or, when that is unset, every main
 * server (single main server setups). Without this the world never exists and /부두 answers "that
 * region is not open".
 *
 * <p>Terrain (proposed): every cell gets a grass yard the size of the largest plot (so a plot that
 * grows needs no block changes, and plots created on another server need no block writes), and the
 * road between yards is a spruce boardwalk over shallow water. Bedrock below, nothing deeper.
 */
final class HousingWorld {

    private HousingWorld() {}

    /** Whether this server hosts the housing world. */
    static boolean hostedHere(WorldKit kit) {
        String server = kit.balance().b().string("world.housing.server").orElse(null);
        if (server == null || server.isBlank()) {
            return kit.api().infra().role() == ServerRole.MAIN;
        }
        return server.equals(kit.api().infra().serverId());
    }

    /** Loads the world if this server hosts it (main thread, at enable). */
    static @Nullable World ensure(WorldKit kit) {
        WorldBalance b = kit.balance();
        String name = b.housingWorld();
        World existing = kit.bukkitWorld(name);
        if (existing != null || !hostedHere(kit)) {
            return existing;
        }
        Layout layout = new Layout(b.housingCell(), b.housingLargeSize(), b.housingFloorY());
        World world =
                new WorldCreator(name)
                        .environment(World.Environment.NORMAL)
                        .generateStructures(false)
                        .generator(new Generator(layout))
                        .biomeProvider(new SingleBiome())
                        .createWorld();
        if (world == null) {
            kit.log().error("하우징 월드 {}를 만들지 못했습니다", name);
            return null;
        }
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        kit.log().info("하우징 월드 {}를 불러왔습니다", name);
        return world;
    }

    /** Cell layout used by the generator (fixed at world creation). */
    record Layout(int cell, int yard, int floorY) {

        /** Whether the block column lies in a cell's yard (the largest plot square). */
        boolean inYard(int x, int z) {
            int offset = (cell - yard) / 2;
            int lx = Math.floorMod(x, cell);
            int lz = Math.floorMod(z, cell);
            return lx >= offset && lx < offset + yard && lz >= offset && lz < offset + yard;
        }
    }

    private static final class Generator extends ChunkGenerator {

        private static final int DEPTH = 8;

        private final Layout layout;

        Generator(Layout layout) {
            this.layout = layout;
        }

        @Override
        public void generateNoise(
                WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
            int top = layout.floorY();
            int bottom = Math.max(data.getMinHeight(), top - DEPTH);
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = chunkX * 16 + dx;
                    int z = chunkZ * 16 + dz;
                    data.setBlock(dx, bottom, dz, Material.BEDROCK);
                    if (layout.inYard(x, z)) {
                        data.setRegion(dx, bottom + 1, dz, dx + 1, top, dz + 1, Material.DIRT);
                        data.setBlock(dx, top, dz, Material.GRASS_BLOCK);
                    } else {
                        data.setRegion(dx, bottom + 1, dz, dx + 1, top - 3, dz + 1, Material.SAND);
                        data.setRegion(dx, top - 3, dz, dx + 1, top, dz + 1, Material.WATER);
                        data.setBlock(dx, top, dz, Material.SPRUCE_PLANKS);
                    }
                }
            }
        }

        @Override
        public @Nullable Location getFixedSpawnLocation(World world, Random random) {
            // Road just south of cell 0's yard.
            int offset = (layout.cell() - layout.yard()) / 2;
            double x = offset + layout.yard() / 2.0;
            double z = offset + layout.yard() + 2.5;
            return new Location(world, x, layout.floorY() + 1, z, 180, 0);
        }
    }

    /** One temperate biome everywhere (no snow or ice on the water). */
    private static final class SingleBiome extends BiomeProvider {

        @Override
        public Biome getBiome(WorldInfo info, int x, int y, int z) {
            return Biome.PLAINS;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo info) {
            return List.of(Biome.PLAINS);
        }
    }
}
