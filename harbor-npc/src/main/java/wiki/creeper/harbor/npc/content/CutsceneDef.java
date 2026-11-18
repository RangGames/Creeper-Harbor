package wiki.creeper.harbor.npc.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 컷씬 (cutscenes/*.yml, 추가 제안, docs 20 5.3). 시간 t는 시작부터의 틱.
 *
 * <pre>{@code
 * - id: cutscene/prologue_drift
 *   world: harbor_main
 *   skippable: seen          # seen(한 번 본 뒤) · always · never
 *   time: night              # 틱 또는 dawn·day·dusk·night (개인 시간)
 *   weather: thunder         # clear · rain · thunder (개인 날씨)
 *   camera:
 *     - {t: 0, pos: [10, 70, 5], yaw: 90, pitch: 10}
 *     - {t: 80, pos: [20, 72, 5], yaw: 120, pitch: 5}
 *   actors:
 *     - {id: hansol, npc: npc/hansol, path: [{t: 0, pos: [15, 64, 8, 180, 0]}]}
 *   lines: [{t: 20, actor: hansol, text: npc.cutscene.prologue.1, ticks: 60}]
 *   titles: [{t: 0, title: npc.cutscene.prologue.title, subtitle: npc.cutscene.prologue.sub}]
 *   sounds: [{t: 0, sound: minecraft:weather.rain, volume: 1, pitch: 1}]
 *   end: {return: origin}    # origin 또는 [x, y, z, yaw, pitch]
 * }</pre>
 *
 * @param duration 전체 길이 (명시하지 않으면 마지막 큐가 끝나는 틱)
 * @param time 개인 시간 틱 (없으면 그대로)
 * @param weather 개인 날씨 (없으면 그대로)
 */
public record CutsceneDef(
        HarborId id,
        String world,
        Skippable skippable,
        @Nullable Long time,
        @Nullable String weather,
        int duration,
        List<CameraKey> camera,
        List<Actor> actors,
        List<Line> lines,
        List<TitleCue> titles,
        List<SoundCue> sounds,
        @Nullable Spot end) {

    public static final String PREFIX = "cutscene/";

    public static final ContentType.Entries<CutsceneDef> TYPE =
            new ContentType.Entries<>(
                    "cutscene",
                    List.of("cutscenes/*.yml"),
                    PREFIX,
                    CutsceneDef::parse,
                    CutsceneDef::id);

    /** 건너뛰기 규칙 ('8. 시스템 상세 기획' 7장: 한 번 본 컷씬은 스킵 가능). */
    public enum Skippable implements Keyed {
        SEEN,
        ALWAYS,
        NEVER;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }

        public boolean allows(boolean seen) {
            return this == ALWAYS || (this == SEEN && seen);
        }
    }

    public record CameraKey(int t, Spot pos) {}

    /**
     * 배우. npc가 있으면 그 NPC의 스킨·자세·이름을 쓰고, skin·name·pose로 덮어쓸 수 있다.
     *
     * @param path 위치 키프레임 (사이는 틱마다 보간)
     */
    public record Actor(
            String id,
            @Nullable NpcId npc,
            @Nullable Skin skin,
            @Nullable String name,
            @Nullable String pose,
            List<CameraKey> path) {

        public Actor {
            path = List.copyOf(path);
        }
    }

    /**
     * 대사. actor가 비어 있으면 내레이션(액션바 자막만).
     *
     * @param text messages_ko.yml 키
     */
    public record Line(int t, @Nullable String actor, String text, int ticks) {}

    public record TitleCue(
            int t, String title, @Nullable String subtitle, int fadeIn, int stay, int fadeOut) {}

    public record SoundCue(int t, String sound, float volume, float pitch) {}

    public CutsceneDef {
        camera = List.copyOf(camera);
        actors = List.copyOf(actors);
        lines = List.copyOf(lines);
        titles = List.copyOf(titles);
        sounds = List.copyOf(sounds);
    }

    public Optional<Actor> actor(String id) {
        return actors.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    /** 끝나면 원래 위치로 돌아가는지. */
    public boolean returnsToOrigin() {
        return end == null;
    }

    private static final Pattern SOUND = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");
    private static final Pattern ACTOR = Pattern.compile("[a-z0-9_]+");

    static CutsceneDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(
                Set.of(
                        "id",
                        "world",
                        "skippable",
                        "time",
                        "weather",
                        "duration",
                        "camera",
                        "actors",
                        "lines",
                        "titles",
                        "sounds",
                        "end"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("cutscene/invalid"));
        String world = node.string("world");
        Skippable skippable =
                node.has("skippable")
                        ? node.enumValue("skippable", Skippable.class).orElse(Skippable.SEEN)
                        : Skippable.SEEN;
        Long time = null;
        if (node.has("time")) {
            YamlNode t = node.get("time").orElseThrow();
            String raw = t.asString();
            if (Conditions.TIMES.contains(raw)) {
                time = Conditions.ticksOf(raw);
            } else {
                long ticks = t.asLong();
                if (ticks < 0 || ticks >= 24_000) {
                    t.error("time은 0~23999 틱 또는 dawn·day·dusk·night여야 합니다");
                }
                time = ticks;
            }
        }
        String weather = node.optString("weather").orElse(null);
        if (weather != null && !Conditions.WEATHERS.contains(weather)) {
            node.error("weather는 clear·rain·thunder 중 하나여야 합니다: '" + weather + "'");
        }

        List<CameraKey> camera = keys(node.list("camera"), world, "camera");
        if (camera.isEmpty()) {
            node.error("camera 키프레임이 하나 이상 있어야 합니다");
        }

        List<Actor> actors = new ArrayList<>();
        Set<String> actorIds = new HashSet<>();
        for (YamlNode a : node.list("actors")) {
            a.allowOnly(Set.of("id", "npc", "skin", "name", "pose", "path"));
            String actorId = a.string("id");
            if (!ACTOR.matcher(actorId).matches()) {
                a.error("배우 ID는 소문자·숫자·밑줄이어야 합니다: '" + actorId + "'");
            }
            if (!actorIds.add(actorId)) {
                a.error("배우 ID가 중복됩니다: '" + actorId + "'");
            }
            NpcId npc = null;
            if (a.has("npc")) {
                YamlNode n = a.get("npc").orElseThrow();
                HarborId npcId = n.asId(NpcId.PREFIX).orElse(null);
                if (npcId != null) {
                    npc = new NpcId(npcId);
                    ctx.ref("npc", npcId, n.pos());
                }
            }
            Skin skin = a.get("skin").flatMap(Skin::parse).orElse(null);
            if (npc == null && skin == null) {
                a.error("배우에는 npc 또는 skin이 있어야 합니다");
            }
            List<CameraKey> path = keys(a.list("path"), world, "path");
            if (path.isEmpty()) {
                a.error("배우 path에 시작 위치(t: 0)가 있어야 합니다");
            }
            actors.add(
                    new Actor(
                            actorId,
                            npc,
                            skin,
                            a.optString("name").orElse(null),
                            a.optString("pose").orElse(null),
                            path));
        }

        int end = camera.isEmpty() ? 0 : camera.getLast().t();
        List<Line> lines = new ArrayList<>();
        for (YamlNode l : node.list("lines")) {
            l.allowOnly(Set.of("t", "actor", "text", "ticks"));
            String actor = l.optString("actor").orElse(null);
            if (actor != null && !actorIds.contains(actor)) {
                l.error("없는 배우입니다: '" + actor + "'");
            }
            Line line =
                    new Line(
                            l.integer("t", 0, 0, 72_000),
                            actor,
                            l.string("text"),
                            l.integer("ticks", 60, 1, 1_200));
            end = Math.max(end, line.t() + line.ticks());
            lines.add(line);
        }
        List<TitleCue> titles = new ArrayList<>();
        for (YamlNode t : node.list("titles")) {
            t.allowOnly(Set.of("t", "title", "subtitle", "fade_in", "stay", "fade_out"));
            TitleCue cue =
                    new TitleCue(
                            t.integer("t", 0, 0, 72_000),
                            t.string("title"),
                            t.optString("subtitle").orElse(null),
                            t.integer("fade_in", 10, 0, 200),
                            t.integer("stay", 60, 0, 1_200),
                            t.integer("fade_out", 10, 0, 200));
            end = Math.max(end, cue.t() + cue.fadeIn() + cue.stay());
            titles.add(cue);
        }
        List<SoundCue> sounds = new ArrayList<>();
        for (YamlNode s : node.list("sounds")) {
            s.allowOnly(Set.of("t", "sound", "volume", "pitch"));
            String sound = s.string("sound");
            if (!SOUND.matcher(sound).matches()) {
                s.error("사운드 키 형식이 틀렸습니다: '" + sound + "'");
            }
            SoundCue cue =
                    new SoundCue(
                            s.integer("t", 0, 0, 72_000),
                            sound,
                            (float) s.number("volume", 1),
                            (float) s.number("pitch", 1));
            end = Math.max(end, cue.t());
            sounds.add(cue);
        }
        for (Actor a : actors) {
            end = Math.max(end, a.path().getLast().t());
        }
        int duration = node.integer("duration", end, 1, 72_000);
        if (duration < end) {
            node.error("duration(" + duration + ")이 마지막 큐(" + end + "틱)보다 짧습니다");
        }

        Spot endSpot = null;
        if (node.has("end")) {
            YamlNode e = node.get("end").orElseThrow();
            e.allowOnly(Set.of("return"));
            YamlNode r = e.require("return").orElse(e);
            if (r.isList()) {
                endSpot = Spot.parseList(r, world).orElse(null);
            } else if (!r.asString().equals("origin")) {
                r.error("end.return은 origin 또는 [x, y, z, yaw, pitch]여야 합니다");
            }
        }
        lines.sort(Comparator.comparingInt(Line::t));
        titles.sort(Comparator.comparingInt(TitleCue::t));
        sounds.sort(Comparator.comparingInt(SoundCue::t));
        return new CutsceneDef(
                id,
                world,
                skippable,
                time,
                weather == null ? null : weather.equals("storm") ? "thunder" : weather,
                duration,
                camera,
                actors,
                lines,
                titles,
                sounds,
                endSpot);
    }

    /** [{t, pos: [x, y, z(, yaw, pitch)], yaw, pitch}] — t는 커져야 한다. */
    private static List<CameraKey> keys(List<YamlNode> list, String world, String what) {
        List<CameraKey> keys = new ArrayList<>();
        int previous = -1;
        for (YamlNode k : list) {
            k.allowOnly(Set.of("t", "pos", "yaw", "pitch"));
            int t = k.integer("t", 0, 0, 72_000);
            if (t <= previous) {
                k.error(what + " 키프레임의 t는 커져야 합니다: " + t);
            }
            if (keys.isEmpty() && t != 0) {
                k.error(what + "의 첫 키프레임은 t: 0이어야 합니다");
            }
            previous = t;
            Spot pos = k.require("pos").flatMap(p -> Spot.parseList(p, world)).orElse(null);
            if (pos == null) {
                continue;
            }
            if (k.has("yaw") || k.has("pitch")) {
                pos =
                        pos.withRotation(
                                (float) k.number("yaw", pos.yaw()),
                                (float) k.number("pitch", pos.pitch()));
            }
            keys.add(new CameraKey(t, pos));
        }
        return keys;
    }
}
