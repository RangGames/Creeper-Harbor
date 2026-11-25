package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.common.rig.Vec3;

/**
 * Temporary decks for arenas that have no platform at the deck point yet (balance.yml boss.deck,
 * proposed). One floor layer under the deck point, covering the spawn, helm and repair spots plus a
 * margin, is placed only on air, water and other replaceable blocks; the original blocks are kept
 * and put back {@code keep_seconds} after the fight. A fight starting at the same point before that
 * takes the deck over. Blocks are set without physics so water does not flow into the gaps. Main
 * thread only.
 *
 * <p>The originals live only in memory: if the server crashes mid-fight the planks stay (harmless,
 * and the next fight there simply stands on them).
 */
final class TempDecks {

    private record Placed(Block block, BlockData original) {}

    private static final class Deck {
        final List<Placed> blocks = new ArrayList<>();
        @Nullable Encounter owner;
        long removeAt = -1;
    }

    private final EncounterManager mgr;
    private final Map<String, Deck> decks = new HashMap<>();

    TempDecks(EncounterManager mgr) {
        this.mgr = mgr;
    }

    /**
     * An encounter needs its deck point: takes over a temporary deck still standing there, or
     * builds one if there is nothing to stand on.
     */
    void claim(Encounter e, Location point) {
        String key = key(point);
        Deck existing = decks.get(key);
        if (existing != null) {
            existing.owner = e;
            existing.removeAt = -1;
            return;
        }
        if (e.def.isWorld() || !e.bb.deckAuto() || DeckGround.standable(point) != null) {
            return;
        }
        Deck deck = build(e, point);
        if (deck.blocks.isEmpty()) {
            return;
        }
        deck.owner = e;
        decks.put(key, deck);
        mgr.log()
                .info(
                        "보스 {} 슬롯 {}: 갑판이 없어 임시 갑판 {}칸을 깔았습니다 ({}, {}, {})",
                        e.def.id(),
                        e.slot,
                        deck.blocks.size(),
                        point.getBlockX(),
                        point.getBlockY() - 1,
                        point.getBlockZ());
    }

    /** The encounter ended: its deck is removed after keep_seconds (players get off first). */
    void release(Encounter e) {
        for (Deck d : decks.values()) {
            if (d.owner == e) {
                d.owner = null;
                d.removeAt = mgr.now() + e.bb.deckKeepTicks();
            }
        }
    }

    /** Removes decks whose keep time is over (called every second). */
    void tick(long now) {
        decks.values()
                .removeIf(
                        d -> {
                            if (d.owner != null || d.removeAt < 0 || now < d.removeAt) {
                                return false;
                            }
                            restore(d);
                            return true;
                        });
    }

    /** Server shutdown: puts every original block back right away. */
    void shutdown() {
        decks.values().forEach(TempDecks::restore);
        decks.clear();
    }

    private static Deck build(Encounter e, Location point) {
        BossDef.Deck def = e.def.arena().deck();
        List<Vec3> points = new ArrayList<>();
        points.add(def.spawn());
        Vec3 wheel = def.wheel();
        if (wheel != null) {
            points.add(wheel);
        }
        points.addAll(def.repair());
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (Vec3 v : points) {
            Location at = e.deck(v);
            minX = Math.min(minX, at.getX());
            maxX = Math.max(maxX, at.getX());
            minZ = Math.min(minZ, at.getZ());
            maxZ = Math.max(maxZ, at.getZ());
        }
        int margin = e.bb.deckMargin();
        int x0 = (int) Math.floor(minX) - margin;
        int x1 = (int) Math.floor(maxX) + margin;
        int z0 = (int) Math.floor(minZ) - margin;
        int z1 = (int) Math.floor(maxZ) + margin;
        int y = point.getBlockY() - 1;
        Deck deck = new Deck();
        World world = point.getWorld();
        if (world == null || y <= world.getMinHeight() || y >= world.getMaxHeight()) {
            return deck;
        }
        BlockData floor = material(e.bb.deckBlock()).createBlockData();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                Block b = world.getBlockAt(x, y, z);
                if (!b.isReplaceable() && !b.isLiquid()) {
                    continue;
                }
                deck.blocks.add(new Placed(b, b.getBlockData()));
                b.setBlockData(floor, false);
            }
        }
        return deck;
    }

    private static void restore(Deck d) {
        for (int i = d.blocks.size() - 1; i >= 0; i--) {
            Placed p = d.blocks.get(i);
            p.block().setBlockData(p.original(), false);
        }
        d.blocks.clear();
    }

    private static Material material(String id) {
        Material m = Material.matchMaterial(id);
        return m != null && m.isBlock() && m.isSolid() ? m : Material.SPRUCE_PLANKS;
    }

    private static String key(Location point) {
        World w = point.getWorld();
        return (w == null ? "?" : w.getName())
                + ":"
                + point.getBlockX()
                + ":"
                + point.getBlockY()
                + ":"
                + point.getBlockZ();
    }
}
