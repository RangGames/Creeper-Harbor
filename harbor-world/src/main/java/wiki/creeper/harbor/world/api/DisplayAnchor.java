package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.id.HarborId;

/**
 * Personal aquarium tank slot (docs 20 0.1, 8.4 'personal aquarium'). World only manages the
 * position of tank blocks in a plot and the edit permission; the species in each slot, swimming
 * visuals and visitor info are handled by Collection (including the display slot count). slot is
 * the order in which the tank was placed in the plot, so moving a tank keeps its display data
 * (housing_display).
 *
 * @param decor tank decor ID (decor/glass_tank …)
 * @param facing front face of the tank (NORTH · EAST · SOUTH · WEST)
 */
public record DisplayAnchor(
        long plotId, int slot, HarborId decor, String world, int x, int y, int z, String facing) {}
