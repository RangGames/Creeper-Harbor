package wiki.creeper.harbor.npc.data;

/** cutscene_restore 한 행: 컷씬 시작 전 상태. */
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
