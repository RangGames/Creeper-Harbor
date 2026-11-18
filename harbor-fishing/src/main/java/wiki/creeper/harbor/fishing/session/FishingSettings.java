package wiki.creeper.harbor.fishing.session;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.core.api.FlagService;

/**
 * 개인 낚시 설정 (/낚시 설정, docs 20 3.5). Core FlagService의 영구 플레이어 플래그에 둔다 (접속 중에는 메모리 캐시, 쓰기는 즉시 DB).
 *
 * @param lookMode 방향 입력을 시점 회전으로 (기본은 A·D 이동 키). 탈것 위에서는 자동으로 시점 회전
 * @param dialog 포획 결과 Dialog 자동 표시
 * @param intensity 연출 강도 0 최소 · 1 보통 · 2 최대
 */
public record FishingSettings(boolean lookMode, boolean dialog, int intensity) {

    static final String DIRECTION = "fishing/settings/direction";
    static final String DIALOG = "fishing/settings/dialog";
    static final String INTENSITY = "fishing/settings/intensity";

    public static final FishingSettings DEFAULT = new FishingSettings(false, true, 2);

    public static FishingSettings of(FlagService flags, UUID player) {
        boolean look = flags.playerValue(player, DIRECTION).map("look"::equals).orElse(false);
        boolean dialog = flags.playerValue(player, DIALOG).map(v -> !v.equals("off")).orElse(true);
        int intensity =
                flags.playerValue(player, INTENSITY)
                        .map(
                                v -> {
                                    try {
                                        return Math.clamp(Integer.parseInt(v), 0, 2);
                                    } catch (NumberFormatException e) {
                                        return DEFAULT.intensity();
                                    }
                                })
                        .orElse(DEFAULT.intensity());
        return new FishingSettings(look, dialog, intensity);
    }

    /** 저장한다. 완료(메인 스레드)되면 캐시에도 반영되어 있다. */
    static CompletableFuture<Void> save(FlagService flags, UUID player, FishingSettings s) {
        return CompletableFuture.allOf(
                flags.setPlayer(
                        player, FlagService.Scope.PERM, DIRECTION, s.lookMode() ? "look" : "move"),
                flags.setPlayer(player, FlagService.Scope.PERM, DIALOG, s.dialog() ? "on" : "off"),
                flags.setPlayer(
                        player, FlagService.Scope.PERM, INTENSITY, String.valueOf(s.intensity())));
    }
}
