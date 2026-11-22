package wiki.creeper.harbor.economy.content;

import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Crafting station and auction board locations (stations.yml, proposed: the stations field of docs
 * 20 7.3 shops.yml split into its own file). An Interaction entity is placed at the coordinates;
 * clicking it opens that place's recipes (or the auction house). world is the same world ID as in
 * regions.yml (dev servers map it via balance.yml world_alias).
 *
 * @param kind place kind. auction is the reference point for the listing/cancel radius (docs 20
 *     7.5)
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

    /** Place kind. The first three share names with {@link RecipeDef.Station}. */
    public enum Kind implements Keyed {
        WORKSHOP,
        COOKING,
        SHIPYARD,
        AUCTION;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }

        /** The crafting place, if this place shows recipes. */
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
