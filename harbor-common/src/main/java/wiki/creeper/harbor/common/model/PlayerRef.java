package wiki.creeper.harbor.common.model;

import java.util.UUID;

/** 플레이어를 가리키는 값. 이름은 표시용이고 식별은 uuid로 한다. */
public record PlayerRef(UUID uuid, String name) {}
