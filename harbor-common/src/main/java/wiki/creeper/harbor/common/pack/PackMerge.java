package wiki.creeper.harbor.common.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 여러 팩에 같은 경로가 있을 때 고르기·합치기 ({@link PackBuilder#build}). owners는 우선순위가 높은 순서. */
final class PackMerge {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** notes에 하나씩 남기는 겹친 파일 수 상한 (나머지는 개수만). */
    private static final int NOTE_LIMIT = 30;

    private PackMerge() {}

    enum Kind {
        PLAIN,
        /** 최상위 키 합치기 (sounds.json, lang). */
        KEYS,
        /** sources 배열 합집합 (atlases). */
        ATLAS,
        /** providers 배열 이어 붙이기 (font). */
        FONT
    }

    static Kind kind(String path) {
        String[] parts = path.split("/", -1);
        if (parts.length < 3 || !parts[0].equals("assets") || !path.endsWith(".json")) {
            return Kind.PLAIN;
        }
        if (parts.length == 3 && parts[2].equals("sounds.json")) {
            return Kind.KEYS;
        }
        if (parts.length == 4 && parts[2].equals("lang")) {
            return Kind.KEYS;
        }
        if (parts.length >= 4 && parts[2].equals("atlases")) {
            return Kind.ATLAS;
        }
        if (parts.length >= 4 && parts[2].equals("font")) {
            return Kind.FONT;
        }
        return Kind.PLAIN;
    }

    static byte[] resolve(String path, List<PackSource> owners, List<String> notes)
            throws IOException {
        if (owners.size() == 1) {
            return owners.getFirst().read(path);
        }
        Kind kind = kind(path);
        if (kind != Kind.PLAIN) {
            try {
                List<JsonObject> docs = new ArrayList<>();
                for (PackSource s : owners) {
                    JsonElement json =
                            JsonParser.parseString(
                                    new String(s.read(path), StandardCharsets.UTF_8));
                    if (!json.isJsonObject()) {
                        throw new JsonParseException("객체가 아님");
                    }
                    docs.add(json.getAsJsonObject());
                }
                return GSON.toJson(merge(kind, docs)).getBytes(StandardCharsets.UTF_8);
            } catch (JsonParseException | IllegalStateException e) {
                note(notes, path + ": JSON을 합치지 못해 " + owners.getFirst().name() + " 것을 썼습니다");
                return owners.getFirst().read(path);
            }
        }
        StringBuilder names = new StringBuilder();
        for (PackSource s : owners) {
            names.append(names.isEmpty() ? "" : " > ").append(s.name());
        }
        note(notes, path + ": 겹침 (" + names + "), 앞쪽 것을 씀");
        return owners.getFirst().read(path);
    }

    /** docs는 우선순위 높은 순서. */
    static JsonObject merge(Kind kind, List<JsonObject> docs) {
        return switch (kind) {
            case KEYS -> {
                JsonObject out = new JsonObject();
                for (int i = docs.size() - 1; i >= 0; i--) {
                    for (Map.Entry<String, JsonElement> e : docs.get(i).entrySet()) {
                        out.add(e.getKey(), e.getValue());
                    }
                }
                yield out;
            }
            case ATLAS -> union(docs, "sources");
            case FONT -> union(docs, "providers");
            case PLAIN -> docs.getFirst();
        };
    }

    /** 배열 필드를 앞쪽부터 이어 붙이고 같은 항목은 한 번만. 다른 최상위 키는 앞쪽이 이긴다. */
    private static JsonObject union(List<JsonObject> docs, String field) {
        JsonObject out = new JsonObject();
        for (int i = docs.size() - 1; i >= 0; i--) {
            for (Map.Entry<String, JsonElement> e : docs.get(i).entrySet()) {
                if (!e.getKey().equals(field)) {
                    out.add(e.getKey(), e.getValue());
                }
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        JsonArray array = new JsonArray();
        for (JsonObject doc : docs) {
            JsonElement items = doc.get(field);
            if (items == null || !items.isJsonArray()) {
                continue;
            }
            for (JsonElement item : items.getAsJsonArray()) {
                if (seen.add(GSON.toJson(item))) {
                    array.add(item);
                }
            }
        }
        out.add(field, array);
        return out;
    }

    /** sounds.json 내용의 소리 키 → {@code ns:키}. */
    static void soundKeys(String namespace, byte[] data, Set<String> out) {
        try {
            JsonElement json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (json.isJsonObject()) {
                for (String key : json.getAsJsonObject().keySet()) {
                    out.add(namespace + ":" + key);
                }
            }
        } catch (JsonParseException e) {
            // 깨진 sounds.json: 소리 목록에서 빠질 뿐
        }
    }

    private static void note(List<String> notes, String line) {
        long overlaps = notes.stream().filter(n -> n.contains(": 겹침")).count();
        if (overlaps < NOTE_LIMIT || !line.contains(": 겹침")) {
            notes.add(line);
        } else if (overlaps == NOTE_LIMIT) {
            notes.add("… 겹친 파일이 더 있습니다: 겹침 (나머지 생략)");
        }
    }
}
