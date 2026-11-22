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

/**
 * Picking/merging when several packs have the same path ({@link PackBuilder#build}). owners are in
 * descending priority.
 */
final class PackMerge {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Max overlapping files noted individually in notes (the rest only as a count). */
    private static final int NOTE_LIMIT = 30;

    private PackMerge() {}

    enum Kind {
        PLAIN,
        /** Merge top-level keys (sounds.json, lang). */
        KEYS,
        /** Union of the sources array (atlases). */
        ATLAS,
        /** Append the providers array (font). */
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

    /** docs are in descending priority. */
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

    /**
     * Appends array fields from the earliest, keeping identical items once. Other top-level keys:
     * earlier wins.
     */
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

    /** Sound keys in sounds.json content → {@code ns:key}. */
    static void soundKeys(String namespace, byte[] data, Set<String> out) {
        try {
            JsonElement json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (json.isJsonObject()) {
                for (String key : json.getAsJsonObject().keySet()) {
                    out.add(namespace + ":" + key);
                }
            }
        } catch (JsonParseException e) {
            // Broken sounds.json: it is just left out of the sound list
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
