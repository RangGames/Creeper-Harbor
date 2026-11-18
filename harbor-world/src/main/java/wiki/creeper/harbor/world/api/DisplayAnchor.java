package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.id.HarborId;

/**
 * 개인 어항 수조 자리 (docs 20 0.1, 8.4 '개인 어항'). World는 플롯 안 수조 블록의 위치와 편집 권한만 관리하고, 칸에 넣을 어종·헤엄 연출·방문객
 * 정보는 Collection이 처리한다 (전시 칸 수 계산도 Collection). slot은 플롯 안에서 수조를 놓은 순서 번호라 수조를 옮겨도 전시
 * 데이터(housing_display)와 이어진다.
 *
 * @param decor 수조 장식 ID (decor/glass_tank …)
 * @param facing 수조 앞면 (NORTH · EAST · SOUTH · WEST)
 */
public record DisplayAnchor(
        long plotId, int slot, HarborId decor, String world, int x, int y, int z, String facing) {}
