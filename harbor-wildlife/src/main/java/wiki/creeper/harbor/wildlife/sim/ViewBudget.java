package wiki.creeper.harbor.wildlife.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 표시 대상 선정 ('17-3' 9.4 거리 단계): 플레이어별로 가까운 개체부터 넣으며 마릿수·부품 예산까지 채운다. 새 개체는 show 거리 안만, 이미 보이던 개체는
 * keep 거리까지 유지해 경계에서 깜박이지 않게 한다. 순수 계산.
 */
public final class ViewBudget {

    /**
     * 후보 하나.
     *
     * @param distanceSq 시청자와의 거리 제곱
     * @param parts 리그 부품 수
     * @param shown 지난 선정에서 이 시청자에게 보였는지
     * @param priority 연출 개체 (거리와 관계없이 먼저)
     */
    public record Candidate(
            UUID id, double distanceSq, int parts, boolean shown, boolean priority) {}

    private ViewBudget() {}

    public static List<UUID> select(
            List<Candidate> candidates, int maxCount, int maxParts, double show, double keep) {
        double show2 = show * show;
        double keep2 = keep * keep;
        List<Candidate> sorted = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.distanceSq() <= show2 || (c.shown() && c.distanceSq() <= keep2)) {
                sorted.add(c);
            }
        }
        sorted.sort(
                Comparator.comparing((Candidate c) -> !c.priority())
                        .thenComparingDouble(Candidate::distanceSq));
        List<UUID> result = new ArrayList<>();
        int parts = 0;
        for (Candidate c : sorted) {
            if (result.size() >= maxCount) {
                break;
            }
            if (parts + c.parts() > maxParts) {
                continue; // 더 작은 리그는 아직 들어갈 수 있다
            }
            parts += c.parts();
            result.add(c.id());
        }
        return result;
    }

    /**
     * 플레이어 부품 예산 = min(기본 × 연출 강도 배율, Core 기준 − 다른 모듈이 이미 보여 주는 부품).
     *
     * @param otherParts 낚시 그림자·수족관·보스 등 Wildlife가 아닌 리그 부품
     */
    public static int partBudget(int base, double intensityScale, int coreBudget, int otherParts) {
        int scaled = (int) Math.round(base * intensityScale);
        return Math.max(0, Math.min(scaled, coreBudget - Math.max(0, otherParts)));
    }

    public static int countBudget(int base, double scale) {
        return Math.max(0, (int) Math.round(base * scale));
    }

    /** 거리 단계. */
    public enum Tier {
        /** 16블록 이하: 2틱마다 전송, 전체 키프레임. */
        NEAR,
        /** 16~40블록: 4틱마다 전송, 고유 동작 파티클 생략. */
        MID,
        /** 40블록 초과: 숨김, 2Hz 계산. */
        FAR
    }

    public static Tier tier(double nearestDistance, double near, double show) {
        if (nearestDistance <= near) {
            return Tier.NEAR;
        }
        return nearestDistance <= show ? Tier.MID : Tier.FAR;
    }
}
