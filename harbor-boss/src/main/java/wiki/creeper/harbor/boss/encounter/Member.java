package wiki.creeper.harbor.boss.encounter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.logic.ContributionCalc;
import wiki.creeper.harbor.boss.logic.Qte;
import wiki.creeper.harbor.fishing.api.TensionSession;

/** Runtime state of one encounter participant (main thread only). */
final class Member {

    final UUID uuid;
    final String name;
    BossRole role;

    /** Whether the role was picked explicitly (preparation stage). */
    boolean chose;

    /** Whether they took part from the start (fastest record candidate condition). */
    final boolean fromStart;

    // Raw contribution data
    double damage;
    long tensionTicks;
    double repair;
    double evasion;
    long activeTicks;
    long lastActivity = Long.MIN_VALUE / 2;

    /**
     * Connection IP seen on entry (for same-IP multi-account checks; kept even if the player leaves
     * before the kill).
     */
    @Nullable String ip;

    // State
    /** Tick when being outside the arena or disconnected started (−1 if present). */
    long absentSince = -1;

    /** Released the slot and left the encounter (contribution is kept). */
    boolean dropped;

    /** Tick to return after falling into the water (−1 if not). */
    long fallenUntil = -1;

    /** Tick when the control reversal debuff ends. */
    long invertedUntil = -1;

    long lastStab = Long.MIN_VALUE / 2;
    Qte.InputState input = Qte.InputState.NONE;

    // Anchor
    @Nullable TensionSession session;

    /** Closing intentionally (so onEnd is not treated as a line snap). */
    boolean closingSession;

    long reopenAt = -1;

    Qte.@Nullable Track track;
    @Nullable BossBar bar;

    Member(UUID uuid, String name, BossRole role, boolean fromStart) {
        this.uuid = uuid;
        this.name = name;
        this.role = role;
        this.fromStart = fromStart;
    }

    /** Remembers the connection IP (keeps the previous value if unknown). */
    void rememberIp(Player p) {
        InetSocketAddress addr = p.getAddress();
        InetAddress host = addr == null ? null : addr.getAddress();
        if (host != null) {
            ip = host.getHostAddress();
        }
    }

    boolean present() {
        return absentSince < 0 && !dropped;
    }

    boolean fallen(long now) {
        return fallenUntil >= 0 && now < fallenUntil;
    }

    void activity(long now) {
        lastActivity = now;
    }

    ContributionCalc.Stats stats() {
        return new ContributionCalc.Stats(
                uuid, damage, tensionTicks / 20.0, repair, evasion, activeTicks);
    }
}
