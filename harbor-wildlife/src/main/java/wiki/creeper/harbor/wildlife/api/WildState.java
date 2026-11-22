package wiki.creeper.harbor.wildlife.api;

/** Fish state machine ('17-3' 9.4 table). */
public enum WildState {
    /** Swims toward targets picked within a radius of home. */
    CRUISE,
    /** Resting (speed ×0.15, plays idle). */
    IDLE,
    /** Plays a special action. */
    ACTION,
    /** Flees away from the threat. */
    FLEE,
    /** Slowly circles the bobber. */
    APPROACH,
    /** Bite (handed over to the Fishing shadow). */
    BITE,
    /** Follows the school leader with separation, alignment and cohesion. */
    SCHOOL
}
