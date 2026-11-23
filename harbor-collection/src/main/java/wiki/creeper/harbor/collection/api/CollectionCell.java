package wiki.creeper.harbor.collection.api;

import wiki.creeper.harbor.common.id.FishId;

/** One stored collection cell with its species ({@link CollectionService#storedEntries}). */
public record CollectionCell(FishId fish, CollectionEntry entry) {}
