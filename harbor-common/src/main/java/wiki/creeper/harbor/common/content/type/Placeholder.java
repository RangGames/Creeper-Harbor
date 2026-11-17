package wiki.creeper.harbor.common.content.type;

import wiki.creeper.harbor.common.id.HarborId;

/**
 * 파싱 오류가 났을 때 레코드를 끝까지 만들기 위한 임시 ID. 오류가 있으면 결과 전체를 버리므로 밖으로 나가지 않는다.
 */
final class Placeholder {

    static final HarborId FISH = HarborId.parse("fish/invalid");
    static final HarborId ITEM = HarborId.parse("item/invalid");
    static final HarborId REGION = HarborId.parse("region/invalid");

    private Placeholder() {}
}
