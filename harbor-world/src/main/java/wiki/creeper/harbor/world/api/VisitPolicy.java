package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.model.Keyed;

/** 하우징 방문 정책 (housing_plot.visit_policy). '친구'는 공동 건축자 목록으로 본다 (docs 20 8.8 열린 질문). */
public enum VisitPolicy implements Keyed {
    PUBLIC,
    MEMBERS,
    PRIVATE;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
