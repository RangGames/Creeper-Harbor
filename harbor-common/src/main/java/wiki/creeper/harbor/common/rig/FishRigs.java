package wiki.creeper.harbor.common.rig;

import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;

/**
 * 어종 → 어종 리그 연결 (추가 제안). 어종 리그는 리소스팩 모델 이름을 따라 {@code rig/fish/<모델 이름>}이다. 어종 ID와 모델 이름이 다를 수
 * 있어서(예: {@code fish/calm_bay_gizzard_shad} → 모델 {@code harbor:fish/calm_bay_shad}) 항상
 * FishDef.model()에서 구한다. Wildlife·Fishing·Collection이 같은 규칙을 쓴다.
 */
public final class FishRigs {

    private static final String MODEL_PREFIX = "fish/";

    private FishRigs() {}

    /**
     * 어종 리그 ID: model {@code <ns>:fish/<이름>} → {@code <ns>:rig/fish/<이름>}. 모델 키가 그 꼴이 아니면 어종 ID
     * 이름으로.
     */
    public static RigId rigOf(FishDef fish) {
        String model = fish.model();
        try {
            HarborId key = HarborId.parse(model);
            if (key.path().startsWith(MODEL_PREFIX)
                    && key.path().length() > MODEL_PREFIX.length()) {
                return new RigId(new HarborId(key.namespace(), "rig/" + key.path()));
            }
        } catch (IllegalArgumentException e) {
            // 모델 키가 HarborId 형식이 아니면 어종 ID로
        }
        return byId(fish);
    }

    /** 예전 규칙 (어종 ID 이름). {@link #rigOf}가 가리키는 리그가 없을 때만 대신 쓴다. */
    public static RigId byId(FishDef fish) {
        HarborId id = fish.id().id();
        return new RigId(new HarborId(id.namespace(), "rig/" + MODEL_PREFIX + id.name()));
    }

    /** 어종 크기 범위 가운데 값 (블록, 1블록 = 1m). 범위가 없으면 0.4. */
    public static double naturalLength(FishDef fish) {
        double cm = (fish.sizeMin() + fish.sizeMax()) / 2;
        return cm > 0 ? cm / 100.0 : 0.4;
    }

    /**
     * 리그를 몸길이 lengthBlocks(블록)로 그릴 인스턴스 배율. 리그 length가 없으면 1 (리그 원래 크기). 리소스팩 안내의 "배율 = 크기(m) ÷
     * model_length_blocks"와 같다 (리그 scale도 반영).
     */
    public static double fitScale(RigDef rig, double lengthBlocks) {
        double own = rig.length() * rig.scale();
        if (own <= 0 || lengthBlocks <= 0) {
            return 1.0;
        }
        return lengthBlocks / own;
    }
}
