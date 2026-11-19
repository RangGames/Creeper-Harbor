package wiki.creeper.harbor.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.Harbor;

/**
 * {@code harbor send %players% to lobby} · {@code harbor send %players% to sea}. 서버 이동은 프록시의 이동
 * 절차(저장 → 잠금 넘김)를 거친다.
 *
 * <ul>
 *   <li>lobby: 로비로. 도착해도 자동 출항하지 않고, 본 서버에서 나갔으면 자리 유예를 받는다
 *   <li>sea: 출항 = 로비에서 본 서버 대기열에 들어간다 (/출항과 같음). 로비가 아니면 아무 일도 없다
 * </ul>
 */
public final class EffMove extends Effect {

    private @Nullable Expression<Player> players;
    private boolean sea;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(
            Expression<?>[] exprs, int pattern, Kleenean delayed, ParseResult parseResult) {
        players = (Expression<Player>) exprs[0];
        sea = parseResult.mark == 1;
        return true;
    }

    @Override
    protected void execute(Event event) {
        Expression<Player> p = players;
        if (p == null) {
            return;
        }
        for (Player player : p.getArray(event)) {
            if (sea) {
                Harbor.api().proxy().requestDepart(player.getUniqueId());
            } else {
                var _ = Harbor.api().proxy().requestLobby(player.getUniqueId(), "skript");
            }
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        Expression<Player> p = players;
        return "harbor send "
                + (p == null ? "?" : p.toString(event, debug))
                + (sea ? " to sea" : " to lobby");
    }
}
