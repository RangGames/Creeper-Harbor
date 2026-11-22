package wiki.creeper.harbor.skript.lambda;

import org.bukkit.OfflinePlayer;

/** Lambda expression (player). */
public final class OfflinePlayerExpr extends BaseExpr<OfflinePlayer> {

    public OfflinePlayerExpr(Spec spec, Spec.Getter<OfflinePlayer> getter) {
        super(spec, OfflinePlayer.class, getter);
    }
}
