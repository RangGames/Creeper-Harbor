package wiki.creeper.harbor.common.pack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;

/**
 * Generates resource pack files for custom blocks (proposed). The automatic resource pack build
 * overlays them on the asset folder.
 *
 * <ul>
 *   <li>{@code assets/minecraft/blockstates/note_block.json}: all 800 note block states. States
 *       used by custom blocks (both powered states) get that model; the rest get the vanilla note
 *       block model
 *   <li>Block models for blocks written with texture or top/side ({@code
 *       <ns>:generated/block/<name>})
 *   <li>Item definitions for all custom blocks ({@code
 *       assets/<ns>/items/generated/block/<name>.json})
 * </ul>
 *
 * Blocks whose textures/models are missing from the asset folder are left out with a warning (so
 * clients do not see purple-black boxes; that state keeps the vanilla look).
 */
public final class FurnishingPack {

    /** Vanilla note block model. */
    static final String VANILLA = "minecraft:block/note_block";

    public static final String BLOCKSTATE = "assets/minecraft/blockstates/note_block.json";

    /**
     * @param files pack path → content
     * @param warnings notes on blocks that could not be added
     */
    public record Result(Map<String, byte[]> files, List<String> warnings) {
        public Result {
            files = Map.copyOf(files);
            warnings = List.copyOf(warnings);
        }
    }

    private FurnishingPack() {}

    /**
     * @param exists whether the asset folder has a file at that pack path (null means no check)
     */
    public static Result generate(
            Collection<CustomBlockDef> blocks, @Nullable Predicate<String> exists) {
        if (blocks.isEmpty()) {
            return new Result(Map.of(), List.of());
        }
        Map<String, byte[]> files = new TreeMap<>();
        List<String> warnings = new ArrayList<>();
        Map<Integer, Variant> stateModels = new HashMap<>();
        List<CustomBlockDef> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparing(CustomBlockDef::id));
        for (CustomBlockDef block : sorted) {
            String missing = missing(block, exists);
            if (missing != null) {
                warnings.add(block.id() + ": 에셋 폴더에 " + missing + " 파일이 없어 넣지 않았습니다");
                continue;
            }
            if (block.states().stream().anyMatch(stateModels::containsKey)) {
                warnings.add(block.id() + ": 상태 번호가 다른 블록과 겹쳐 넣지 않았습니다");
                continue;
            }
            String ns = block.id().namespace();
            String name = block.id().name();
            String modelJson = modelJson(block);
            if (modelJson != null) {
                files.put(
                        "assets/" + ns + "/models/generated/block/" + name + ".json",
                        bytes(modelJson));
            }
            files.put(
                    "assets/" + ns + "/items/generated/block/" + name + ".json",
                    bytes(
                            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                                    + block.blockModel()
                                    + "\"}}\n"));
            for (String orientation : block.rotation().orientations()) {
                int[] rot = CustomBlockDef.Rotation.modelRotation(orientation);
                stateModels.put(
                        block.stateOf(orientation),
                        new Variant(block.blockModel(), rot[0], rot[1]));
            }
        }
        files.put(BLOCKSTATE, bytes(blockstate(stateModels)));
        return new Result(files, warnings);
    }

    /** Model and rotation (degrees) of one state. */
    record Variant(String model, int x, int y) {}

    /** Note block blockstate file (state → model/rotation; missing states are vanilla). */
    static String blockstate(Map<Integer, Variant> stateModels) {
        Variant vanilla = new Variant(VANILLA, 0, 0);
        StringBuilder sb = new StringBuilder("{\n  \"variants\": {");
        boolean first = true;
        int states = CustomBlockDef.INSTRUMENTS.size() * CustomBlockDef.NOTES;
        for (int state = 0; state < states; state++) {
            Variant v = stateModels.getOrDefault(state, vanilla);
            for (boolean powered : new boolean[] {false, true}) {
                sb.append(first ? "\n" : ",\n");
                first = false;
                sb.append("    \"")
                        .append(CustomBlockDef.properties(state, powered))
                        .append("\": {\"model\": \"")
                        .append(v.model())
                        .append('"');
                if (v.x() != 0) {
                    sb.append(", \"x\": ").append(v.x());
                }
                if (v.y() != 0) {
                    sb.append(", \"y\": ").append(v.y());
                }
                sb.append('}');
            }
        }
        return sb.append("\n  }\n}\n").toString();
    }

    private static @Nullable String modelJson(CustomBlockDef block) {
        String texture = block.texture();
        if (texture != null) {
            return "{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\""
                    + texture
                    + "\"}}\n";
        }
        String top = block.top();
        String side = block.side();
        if (top != null && side != null) {
            String bottom = block.bottom();
            return "{\"parent\":\"minecraft:block/cube_bottom_top\",\"textures\":{\"top\":\""
                    + top
                    + "\",\"bottom\":\""
                    + (bottom != null ? bottom : top)
                    + "\",\"side\":\""
                    + side
                    + "\"}}\n";
        }
        Map<String, String> faces = block.faces();
        if (!faces.isEmpty()) {
            StringBuilder sb =
                    new StringBuilder("{\"parent\":\"minecraft:block/cube\",\"textures\":{");
            sb.append("\"particle\":\"").append(faces.get("north")).append('"');
            for (String face : CustomBlockDef.FACES) {
                sb.append(",\"").append(face).append("\":\"").append(faces.get(face)).append('"');
            }
            return sb.append("}}\n").toString();
        }
        return null; // custom-made model
    }

    /**
     * Referenced file missing from the asset folder (the first one). The minecraft namespace is
     * vanilla and not checked.
     */
    private static @Nullable String missing(
            CustomBlockDef block, @Nullable Predicate<String> exists) {
        if (exists == null) {
            return null;
        }
        List<String> paths = new ArrayList<>();
        String model = block.model();
        if (model != null) {
            paths.add(path(model, "models", ".json"));
        }
        List<@Nullable String> textures = new ArrayList<>();
        textures.add(block.texture());
        textures.add(block.top());
        textures.add(block.side());
        textures.add(block.bottom());
        textures.addAll(block.faces().values());
        for (String tex : textures) {
            if (tex != null) {
                paths.add(path(tex, "textures", ".png"));
            }
        }
        for (String p : paths) {
            if (p != null && !exists.test(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * {@code harbor:block/x} → {@code assets/harbor/<kind>/block/x<ext>}. null for the minecraft
     * namespace.
     */
    static @Nullable String path(String key, String kind, String ext) {
        int colon = key.indexOf(':');
        String ns = key.substring(0, colon);
        if (ns.equals("minecraft")) {
            return null;
        }
        return "assets/" + ns + "/" + kind + "/" + key.substring(colon + 1) + ext;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
