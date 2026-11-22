package wiki.creeper.harbor.npc.api;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * NPC information exposed to other modules.
 *
 * @param name base name (before name variants). Per-player names via {@link NpcService#displayName}
 * @param dialog dialog tree ID ({@code harbor:dialog/<name>}), or null
 * @param shop shop ID (Economy shops.yml), or null
 * @param spawned whether the entity is standing right now (reflects chunk loading, spawn conditions
 *     and wanderer schedule)
 */
public record NpcInfo(
        NpcId id,
        String name,
        NpcRole role,
        @Nullable HarborId dialog,
        @Nullable HarborId shop,
        boolean spawned) {}
