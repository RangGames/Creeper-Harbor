package wiki.creeper.harbor.skript.lambda;

import org.bukkit.OfflinePlayer;

/** 람다 식 (플레이어). */
public final class OfflinePlayerExpr extends BaseExpr<OfflinePlayer> {

    public OfflinePlayerExpr(Spec spec, Spec.Getter<OfflinePlayer> getter) {
        super(spec, OfflinePlayer.class, getter);
    }
}
