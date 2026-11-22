package wiki.creeper.harbor.wildlife.sim;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.WildState;
import wiki.creeper.harbor.wildlife.content.BehaviourBook.ActionDef;

/** One virtual fish ('17-3' 9.4 fish state machine). Simulation thread only (no locking). */
final class Agent {

    final UUID id;
    final String world;
    final @Nullable String region;
    final @Nullable String tag;
    final int phaseOffset;
    SpeciesProfile profile;

    Vec3 pos;
    Vec3 heading;
    Vec3 velocity = Vec3.ZERO;
    Vec3 impulse = Vec3.ZERO;
    Vec3 steer = Vec3.ZERO;
    double speed;
    float yaw;
    float pitch;

    Vec3 home;
    Vec3 target;
    double retargetAt;

    WildState state;
    WildState resume;
    double stateUntil;
    double nextDice;

    @Nullable ActionDef action;
    int repsLeft;
    double repEnds;

    @Nullable UUID school;
    boolean leader;

    // threat
    Vec3 threat = Vec3.ZERO;
    @Nullable Vec3 pendingThreat;
    final Map<UUID, Double> startled = new HashMap<>();

    // bobber approach
    @Nullable Vec3 hook;
    double deadline;
    boolean claimed;
    @Nullable UUID angler;
    double orbitAngle;
    double biteEnds;
    boolean hidden;

    // scripted
    final boolean scripted;
    final boolean hold;
    final boolean ignoreThreat;
    final double expiresAt;
    List<Vec3> path;
    int pathIndex;
    final double airBaseY;
    final double bobPhase;

    // output
    String animation;
    double animationSpeed = 1;
    @Nullable String oneShot;
    int oneShotSeq;
    int teleportSeq;

    // simulation
    double farAccum;
    double surfaceY = Double.NaN;
    double surfaceAt;
    double nearest = Double.MAX_VALUE;
    boolean removed;

    Agent(
            UUID id,
            SpeciesProfile profile,
            String world,
            Vec3 pos,
            Vec3 heading,
            @Nullable String region,
            @Nullable String tag,
            int phaseOffset,
            boolean scripted,
            boolean hold,
            boolean ignoreThreat,
            double expiresAt,
            List<Vec3> path,
            double airBaseY,
            double bobPhase) {
        this.id = id;
        this.profile = profile;
        this.world = world;
        this.pos = pos;
        this.heading = heading;
        this.region = region;
        this.tag = tag;
        this.phaseOffset = phaseOffset;
        this.scripted = scripted;
        this.hold = hold;
        this.ignoreThreat = ignoreThreat;
        this.expiresAt = expiresAt;
        this.path = path;
        this.airBaseY = airBaseY;
        this.bobPhase = bobPhase;
        this.home = pos;
        this.target = pos;
        this.state = WildState.CRUISE;
        this.resume = WildState.CRUISE;
        this.animation = profile.swimAnimation();
    }

    boolean member() {
        return school != null && !leader;
    }

    /** State to return to after a special action, rest or flight ends. */
    WildState cruiseState() {
        return member() ? WildState.SCHOOL : WildState.CRUISE;
    }
}
