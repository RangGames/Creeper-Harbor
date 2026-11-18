package wiki.creeper.harbor.economy.content;

import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 제작 스테이션·경매 게시판 위치 (stations.yml, 추가 제안: docs 20 7.3 shops.yml의 stations 칸을 따로 둔 파일). 좌표에
 * Interaction 엔티티를 두고, 클릭하면 그 장소의 레시피(또는 경매장)를 연다. world는 regions.yml과 같은 월드 ID (개발 서버는 balance.yml
 * world_alias로 대응).
 *
 * @param kind 장소 종류. auction은 경매 등록·취소 가능 반경의 기준점 (docs 20 7.5)
 */
public record StationDef(
        HarborId id,
        Kind kind,
        String name,
        String world,
        double x,
        double y,
        double z,
        float width,
        float height) {

    /** 장소 종류. 앞의 세 개는 {@link RecipeDef.Station}과 같은 이름이다. */
    public enum Kind implements Keyed {
        WORKSHOP,
        COOKING,
        SHIPYARD,
        AUCTION;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }

        /** 레시피를 보여주는 장소면 그 제작 장소. */
        public java.util.Optional<RecipeDef.Station> craftStation() {
            return switch (this) {
                case WORKSHOP -> java.util.Optional.of(RecipeDef.Station.WORKSHOP);
                case COOKING -> java.util.Optional.of(RecipeDef.Station.COOKING);
                case SHIPYARD -> java.util.Optional.of(RecipeDef.Station.SHIPYARD);
                case AUCTION -> java.util.Optional.empty();
            };
        }
    }

    public static final String PREFIX = "station/";

    public static final ContentType.Entries<StationDef> TYPE =
            new ContentType.Entries<>(
                    "station", List.of("stations.yml"), PREFIX, StationDef::parse, StationDef::id);

    static StationDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("id", "type", "name", "location", "size"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("station/invalid"));
        Kind kind = node.enumValue("type", Kind.class).orElse(Kind.WORKSHOP);
        String world = "";
        double x = 0;
        double y = 0;
        double z = 0;
        YamlNode loc = node.require("location").orElse(null);
        if (loc != null) {
            loc.allowOnly(Set.of("world", "x", "y", "z"));
            world = loc.string("world");
            x = loc.number("x");
            y = loc.number("y");
            z = loc.number("z");
        }
        float width = 1;
        float height = 1;
        if (node.has("size")) {
            YamlNode size = node.get("size").orElseThrow();
            size.allowOnly(Set.of("width", "height"));
            width = (float) size.number("width", 1);
            height = (float) size.number("height", 1);
            if (width <= 0 || height <= 0 || width > 8 || height > 8) {
                size.error("size는 0보다 크고 8 이하여야 합니다");
            }
        }
        return new StationDef(id, kind, node.string("name"), world, x, y, z, width, height);
    }
}
