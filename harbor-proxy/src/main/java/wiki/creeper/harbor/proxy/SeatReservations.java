package wiki.creeper.harbor.proxy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * 인스턴스에 간 사람의 본 서버 자리 (docs 20 1.4 인스턴스 배정: "귀환은 대기열을 거치지 않습니다 (자리 예약)").
 *
 * <ul>
 *   <li>본 서버에서 인스턴스로 옮기면 출발한 본 서버 자리를 잡아 두고, 대기열은 그만큼 빈자리를 덜 센다
 *   <li>그 본 서버로의 귀환 이동은 수용 한도를 넘어도 들여보낸다
 *   <li>본 서버에 다시 들어가면 푼다. 인스턴스에서 접속이 끊기면 재접속 유예 시간만큼 유지한다 (Boss 재접속 키로 돌아올 수 있게)
 * </ul>
 */
final class SeatReservations {

    private record Seat(String server, @Nullable Instant offlineSince) {}

    private final Map<UUID, Seat> seats = new ConcurrentHashMap<>();

    void reserve(UUID player, String mainServer) {
        seats.put(player, new Seat(mainServer, null));
    }

    Optional<String> seatOf(UUID player) {
        return Optional.ofNullable(seats.get(player)).map(Seat::server);
    }

    /** 이 본 서버로의 귀환이면 true (수용 한도 검사를 건너뛴다). */
    boolean holds(UUID player, String server) {
        Seat seat = seats.get(player);
        return seat != null && seat.server().equals(server);
    }

    int reservedOn(String mainServer) {
        return (int) seats.values().stream().filter(s -> s.server().equals(mainServer)).count();
    }

    Optional<String> release(UUID player) {
        return Optional.ofNullable(seats.remove(player)).map(Seat::server);
    }

    /** 인스턴스에서 접속이 끊겼다: 유예 시간 동안 자리를 유지한다. */
    void markOffline(UUID player, Instant now) {
        seats.computeIfPresent(player, (k, seat) -> new Seat(seat.server(), now));
    }

    void markOnline(UUID player) {
        seats.computeIfPresent(player, (k, seat) -> new Seat(seat.server(), null));
    }

    /** 유예 시간이 지난 오프라인 자리를 푼다. */
    void expire(Instant now, Duration grace) {
        seats.entrySet()
                .removeIf(
                        e -> {
                            Instant since = e.getValue().offlineSince();
                            return since != null
                                    && Duration.between(since, now).compareTo(grace) > 0;
                        });
    }
}
