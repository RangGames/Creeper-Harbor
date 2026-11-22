package wiki.creeper.harbor.common.model;

import java.util.UUID;

/** Value pointing at a player. The name is for display; identification is by uuid. */
public record PlayerRef(UUID uuid, String name) {}
