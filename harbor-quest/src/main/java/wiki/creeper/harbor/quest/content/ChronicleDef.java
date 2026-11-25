package wiki.creeper.harbor.quest.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * "Harbor Chronicle" and main story chapters (chronicle.yml, proposed, docs 20 6.3·6.4, '5. Server
 * Story' ch. 3·6).
 *
 * @param completeTitle title given when the full chronicle is collected
 * @param requiredPieces pieces the full chronicle needs (chapters 1~7 of '5. Server Story', default
 *     7). The title waits for this many even while fewer pieces are defined, so it is not granted
 *     early when only the CBT chapters exist
 * @param recapMulbineul chronicle recap mulbineul (per chapter, D9)
 * @param recapReputation chronicle recap reputation (per chapter, proposed value)
 * @param recapMax per-season cap on recap mulbineul (D9: 27)
 */
public record ChronicleDef(
        List<Chapter> chapters,
        List<Piece> pieces,
        @Nullable String completeTitle,
        int requiredPieces,
        long recapMulbineul,
        int recapReputation,
        long recapMax) {

    /**
     * Chapter. Completing the final quest clears the chapter.
     *
     * @param region region used for the chronicle recap check (this chapter's region; first entry
     *     in a new season gives the recap reward)
     * @param area sub-area within the region (temporary until World splits regions; empty means the
     *     whole region)
     * @param cutscene cutscene replayed from /연대기
     */
    public record Chapter(
            int chapter,
            String name,
            QuestId finalQuest,
            @Nullable RegionId region,
            @Nullable Area area,
            @Nullable HarborId cutscene) {}

    /** Horizontal circle (x, z, radius). */
    public record Area(double x, double z, double radius) {
        public boolean contains(double px, double pz) {
            double dx = px - x;
            double dz = pz - z;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    /**
     * A piece of the truth.
     *
     * @param advancement custom advancement key (created by the harbor-tools datapack; skipped if
     *     absent on the server)
     */
    public record Piece(
            String id,
            int chapter,
            String name,
            String icon,
            @Nullable String advancement,
            List<String> text) {}

    /** Chapters 1~7 each end with one piece ('5. Server Story' 3). */
    public static final int DEFAULT_REQUIRED_PIECES = 7;

    public static final ContentType.Single<ChronicleDef> TYPE =
            new ContentType.Single<>(
                    "quest_chronicle", "chronicle.yml", false, ChronicleDef::parse);

    public ChronicleDef {
        chapters = List.copyOf(chapters);
        pieces = List.copyOf(pieces);
    }

    public Optional<Piece> piece(String id) {
        return pieces.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Pieces needed for the title: required_pieces, or every defined piece if there are more. */
    public int totalPieces() {
        return Math.max(requiredPieces, pieces.size());
    }

    public Optional<Chapter> chapter(int chapter) {
        return chapters.stream().filter(c -> c.chapter() == chapter).findFirst();
    }

    static ChronicleDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("chapters", "pieces", "complete_title", "required_pieces", "recap"));
        List<Chapter> chapters = new ArrayList<>();
        for (YamlNode c : node.list("chapters")) {
            c.allowOnly(Set.of("chapter", "name", "final", "region", "area", "cutscene"));
            HarborId fin =
                    c.require("final")
                            .flatMap(f -> f.asId(QuestId.PREFIX))
                            .orElse(HarborId.parse("quest/invalid"));
            ctx.ref("quest", fin, c.pos());
            RegionId region =
                    c.get("region")
                            .flatMap(r -> r.asId(RegionId.PREFIX))
                            .map(
                                    id -> {
                                        ctx.ref("region", id, c.pos());
                                        return new RegionId(id);
                                    })
                            .orElse(null);
            Area area =
                    c.get("area")
                            .map(
                                    a -> {
                                        a.allowOnly(Set.of("x", "z", "r"));
                                        return new Area(
                                                a.number("x"), a.number("z"), a.number("r"));
                                    })
                            .orElse(null);
            HarborId cutscene =
                    c.get("cutscene")
                            .flatMap(x -> x.asId("cutscene/"))
                            .map(
                                    id -> {
                                        ctx.ref("cutscene", id, c.pos());
                                        return id;
                                    })
                            .orElse(null);
            chapters.add(
                    new Chapter(
                            c.integer("chapter", 0, 0, 8),
                            c.string("name"),
                            new QuestId(fin),
                            region,
                            area,
                            cutscene));
        }
        List<Piece> pieces = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (YamlNode p : node.list("pieces")) {
            p.allowOnly(Set.of("id", "chapter", "name", "icon", "advancement", "text"));
            String id = p.string("id");
            if (!id.matches("[a-z0-9_]{1,32}")) {
                p.error("조각 id는 소문자·숫자·밑줄 1~32자여야 합니다: " + id);
            }
            if (!ids.add(id)) {
                p.error("조각 id가 중복됩니다: " + id);
            }
            pieces.add(
                    new Piece(
                            id,
                            p.integer("chapter", 1, 0, 8),
                            p.string("name"),
                            p.string("icon", "minecraft:paper"),
                            p.optString("advancement").orElse(null),
                            p.get("text").map(YamlNode::asStringOrList).orElse(List.of())));
        }
        String title =
                node.optString("complete_title")
                        .map(
                                t -> {
                                    HarborId id = HarborId.tryParse(t).orElse(null);
                                    if (id == null) {
                                        node.error("칭호 ID 형식이 틀렸습니다: " + t);
                                        return t;
                                    }
                                    ctx.ref("title", id, node.pos());
                                    return id.toString();
                                })
                        .orElse(null);
        YamlNode recap = node.get("recap").orElse(null);
        long mulbineul = 3;
        int reputation = 150;
        long max = 27;
        if (recap != null) {
            recap.allowOnly(Set.of("mulbineul", "reputation", "max_mulbineul"));
            mulbineul = recap.longValue("mulbineul", 3);
            reputation = recap.integer("reputation", 150);
            max = recap.longValue("max_mulbineul", 27);
        }
        int required = node.integer("required_pieces", DEFAULT_REQUIRED_PIECES, 1, 64);
        return new ChronicleDef(chapters, pieces, title, required, mulbineul, reputation, max);
    }
}
