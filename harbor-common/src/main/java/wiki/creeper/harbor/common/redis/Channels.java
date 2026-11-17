package wiki.creeper.harbor.common.redis;

/** 서버 공용 채널 ('11. 데이터 설계' 4장). 모듈 전용 메시지 record는 각 모듈 api 패키지에 둔다. */
public final class Channels {

    public static final String ANNOUNCE = "harbor:announce";
    public static final String EVENT = "harbor:event";
    public static final String MARKET = "harbor:market";
    public static final String WORLD_BOSS = "harbor:worldboss";

    /** 서버 전체 공지 (희귀 포획, 신기록, 보스). message는 MiniMessage 문자열. */
    public record Announcement(String message) {}

    public static final Channel<Announcement> ANNOUNCEMENTS = new Channel<>(ANNOUNCE, Announcement.class);

    private Channels() {}
}
