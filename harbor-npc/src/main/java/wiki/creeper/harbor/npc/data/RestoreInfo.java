package wiki.creeper.harbor.npc.data;

/** One cutscene_restore row: state before the cutscene started. */
public record RestoreInfo(
        String server,
        String cutscene,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String gameMode,
        boolean allowFlight,
        boolean flying) {}
