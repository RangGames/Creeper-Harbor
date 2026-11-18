package wiki.creeper.harbor.season.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.season.content.TitleDef;

/**
 * 칭호 저장소 (titles, docs 20 0.1·11.4). Collection·Quest·Boss·Shop은 지급 요청만 한다. 칭호는 시즌 초기화 대상이 아니다.
 *
 * <ul>
 *   <li>{@link #grant}는 (uuid, title_id) 유니크로 중복을 막고, 이미 있으면 {@link Grant#ALREADY_OWNED}로 성공 처리한다
 *       (멱등). 접속 중이 아니어도 DB에 바로 쓴다.
 *   <li>titles.yml에 없는 칭호는 {@link Grant#UNKNOWN_TITLE}로 거부한다. 콘텐츠에서 칭호를 가리키면 {@code
 *       ctx.ref("title", id, pos)}로 참조 검사를 걸어 두는 것을 권장한다.
 *   <li>결과 future는 모두 메인 스레드에서 완료된다.
 * </ul>
 *
 * <pre>{@code
 * // Collection: 도감 마일스톤 달성 시 (CodexMilestoneEvent를 Season이 듣지 않으므로 직접 부른다)
 * titles.grant(uuid, TitleId.of(milestone.title()), "codex:" + season + ":" + milestone.percent());
 * }</pre>
 */
public interface TitleService {

    /** 지급 결과. */
    enum Grant {
        GRANTED,
        ALREADY_OWNED,
        UNKNOWN_TITLE,
        FAILED;

        /** 지금 가지고 있는 상태인지 (새로 받았거나 이미 있었음). */
        public boolean owned() {
            return this == GRANTED || this == ALREADY_OWNED;
        }
    }

    /**
     * 칭호를 준다.
     *
     * @param refId 출처 참조 ID (기록용, 1~64자. 예 {@code hof:3:biggest_fish:fish/x}, {@code
     *     worldboss:<event>:<uuid>})
     */
    CompletableFuture<Grant> grant(UUID player, TitleId title, @Nullable String refId);

    /** 칭호를 회수한다 (관리자, 후원 종료 등). 장착 중이면 장착도 푼다. 있었으면 true. */
    CompletableFuture<Boolean> revoke(UUID player, TitleId title);

    /** 보유 칭호 목록 (DB에서 읽음, 얻은 순서). */
    CompletableFuture<List<OwnedTitle>> list(UUID player);

    /** 접속 중인 플레이어가 칭호를 가졌는지 (메모리 캐시, 메인 스레드). 접속 중이 아니면 false. */
    boolean has(UUID player, TitleId title);

    /** 접속 여부와 관계없이 DB로 확인한다. */
    CompletableFuture<Boolean> hasAsync(UUID player, TitleId title);

    /** 장착한 칭호 (접속 중인 플레이어, 메인 스레드). */
    Optional<TitleId> equipped(UUID player);

    /** 칭호를 장착한다 (Shop 장착 화면의 칭호 슬롯과 같은 값). null이면 해제. 가지고 있지 않은 칭호면 false. 접속 중인 플레이어만, 메인 스레드. */
    boolean equip(UUID player, @Nullable TitleId title);

    /** 칭호 정의 (titles.yml). */
    Optional<TitleDef> definition(TitleId title);

    /** 표시 문구 (titles.yml name, MiniMessage). 정의가 없으면 ID. */
    Component display(TitleId title);

    /** 장착 칭호 표시 문구 (채팅·이름표 앞에 붙이는 용도). 장착하지 않았으면 빈 Optional. */
    default Optional<Component> equippedDisplay(UUID player) {
        return equipped(player).map(this::display);
    }
}
