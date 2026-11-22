package wiki.creeper.harbor.quest.ui;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.npc.api.NpcInfo;
import wiki.creeper.harbor.quest.editor.QuestDraft;
import wiki.creeper.harbor.quest.service.QuestServices;

/**
 * The editor's objective screen. Each type picks its target differently: catch and donate (fish,
 * grade, any, region), deliver (fish, item, grade), talk and affinity (NPC), visit (current
 * location + radius / region), cutscene and use item (list), everything else by text input. The
 * objective key is what saved progress attaches to, so it is never changed on existing objectives.
 */
final class ObjectiveEditor {

    /** Types selectable in the editor (other modules' types via YAML). */
    static final List<String> TYPES =
            List.of(
                    "catch",
                    "deliver",
                    "talk",
                    "visit",
                    "boss",
                    "donate",
                    "use_item",
                    "cutscene",
                    "collection",
                    "job_level",
                    "job_tier",
                    "reputation",
                    "affinity",
                    "flag",
                    "chart_percent");

    /** Types that use only count, without a target. */
    private static final List<String> NO_TARGET = List.of("job_level", "job_tier", "reputation");

    private final HarborApi api;
    private final QuestServices q;
    private final EditorPickers pickers;
    private final QuestEditor editor;

    ObjectiveEditor(HarborApi api, QuestServices q, EditorPickers pickers, QuestEditor editor) {
        this.api = api;
        this.q = q;
        this.pickers = pickers;
        this.editor = editor;
    }

    private Component text(
            String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) {
        return editor.text(key, r);
    }

    private static String str(Map<String, Object> o, String key, String fallback) {
        Object v = o.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    /** Objective icon on the edit screen. */
    ItemStack icon(Player admin, QuestDraft d, Map<String, Object> o, int index) {
        String type = str(o, "type", "?");
        return MenuIcons.icon(
                material(type),
                text(
                        "objective.icon",
                        Placeholder.unparsed("index", String.valueOf(index + 1)),
                        Placeholder.component("type", typeName(type))),
                List.of(
                        text("objective.key", Placeholder.unparsed("value", str(o, "key", "-"))),
                        text(
                                "objective.target",
                                Placeholder.unparsed("value", str(o, "target", "-"))),
                        text(
                                "objective.count",
                                Placeholder.unparsed("value", str(o, "count", "1"))),
                        text("objective.text", Placeholder.unparsed("value", str(o, "text", "-"))),
                        text("hint.open")),
                false);
    }

    private Component typeName(String type) {
        return api.messages().get("quest.editor.type." + type);
    }

    // ------------------------------------------------------------------ Type selection

    /**
     * @param index -1 for a new objective
     */
    void chooseType(Player admin, QuestDraft d, int index) {
        Menu menu = new Menu(text("title.objective_type"), 2);
        int slot = 0;
        for (String type : TYPES) {
            menu.button(
                    slot++,
                    MenuIcons.icon(
                            material(type),
                            typeName(type),
                            List.of(api.messages().get("quest.editor.type_hint." + type)),
                            false),
                    e -> {
                        if (index < 0) {
                            d.addObjective(type);
                            open(admin, d, d.objectives().size() - 1);
                        } else {
                            Map<String, Object> o = d.objectives().get(index);
                            d.objectiveSet(o, "type", type);
                            if (NO_TARGET.contains(type)) {
                                d.objectiveSet(o, "target", null);
                            } else {
                                d.objectiveSet(o, "target", "any");
                            }
                            open(admin, d, index);
                        }
                    });
        }
        menu.button(
                17,
                MenuIcons.icon(Material.ARROW, api.messages().get("quest.dialog.back")),
                e -> {
                    if (index < 0) {
                        editor.main(admin, d);
                    } else {
                        open(admin, d, index);
                    }
                });
        menu.open(admin);
    }

    // ------------------------------------------------------------------ Single objective

    void open(Player admin, QuestDraft d, int index) {
        if (index < 0 || index >= d.objectives().size()) {
            editor.main(admin, d);
            return;
        }
        Map<String, Object> o = d.objectives().get(index);
        String type = str(o, "type", "catch");
        Menu menu =
                new Menu(
                        text(
                                "title.objective",
                                Placeholder.unparsed("index", String.valueOf(index + 1)),
                                Placeholder.component("type", typeName(type))),
                        4);
        menu.button(
                0,
                MenuIcons.icon(
                        material(type),
                        text("objective.type", Placeholder.component("value", typeName(type))),
                        List.of(text("hint.open")),
                        false),
                e -> chooseType(admin, d, index));
        if (!NO_TARGET.contains(type)) {
            menu.button(
                    1,
                    MenuIcons.icon(
                            Material.TARGET,
                            text(
                                    "objective.target",
                                    Placeholder.unparsed("value", str(o, "target", "any"))),
                            List.of(
                                    api.messages()
                                            .get("quest.editor.target_hint." + targetMode(type))),
                            false),
                    e -> target(admin, d, index, e.getClick().isRightClick()));
        }
        int count = QuestDraft.integer(o, "count", 1);
        menu.button(
                2,
                MenuIcons.icon(
                        Material.HOPPER,
                        text(
                                "objective.count",
                                Placeholder.unparsed("value", String.valueOf(count))),
                        List.of(
                                text(
                                        "hint.number",
                                        Placeholder.unparsed("small", "1"),
                                        Placeholder.unparsed("big", "10"))),
                        false),
                e -> {
                    int next = Math.max(1, count + QuestEditor.step(e.getClick(), 1, 10));
                    d.objectiveSet(o, "count", String.valueOf(next));
                    open(admin, d, index);
                });
        if (type.equals("catch") || type.equals("deliver") || type.equals("donate")) {
            int quality = QuestDraft.integer(o, "quality_min", 0);
            menu.button(
                    3,
                    MenuIcons.icon(
                            Material.NETHER_STAR,
                            text(
                                    "objective.quality",
                                    Placeholder.unparsed(
                                            "value", quality == 0 ? "-" : "★" + quality)),
                            List.of(text("hint.cycle")),
                            false),
                    e -> {
                        int next =
                                Math.floorMod(quality + (e.getClick().isRightClick() ? -1 : 1), 6);
                        d.objectiveSet(o, "quality_min", next == 0 ? null : String.valueOf(next));
                        open(admin, d, index);
                    });
        }
        menu.button(
                4,
                MenuIcons.icon(
                        Material.OAK_SIGN,
                        text("objective.text", Placeholder.unparsed("value", str(o, "text", "-"))),
                        List.of(text("objective.text_hint")),
                        false),
                e -> {
                    if (e.getClick().isShiftClick()) {
                        d.objectiveSet(o, "text", null);
                        open(admin, d, index);
                        return;
                    }
                    editor.input(
                            admin,
                            text("objective.text_title"),
                            List.of(text("objective.text_body")),
                            str(o, "text", ""),
                            80,
                            false,
                            v -> {
                                d.objectiveSet(o, "text", v);
                                open(admin, d, index);
                            });
                });
        menu.button(
                5,
                MenuIcons.icon(
                        Material.COMPASS,
                        text(
                                "objective.waypoint",
                                Placeholder.unparsed("value", str(o, "waypoint", "-"))),
                        List.of(text("objective.waypoint_hint")),
                        false),
                e -> {
                    if (e.getClick().isShiftClick()) {
                        d.objectiveSet(o, "waypoint", null);
                    } else if (e.getClick().isRightClick()) {
                        Optional<NpcInfo> near = q.npcs().nearest(admin, 10);
                        near.ifPresentOrElse(
                                n -> d.objectiveSet(o, "waypoint", n.id().id().toString()),
                                () -> api.messages().send(admin, "quest.editor.no_npc_near"));
                    } else {
                        d.objectiveSet(o, "waypoint", here(admin, false, 0));
                    }
                    open(admin, d, index);
                });
        menu.button(
                8,
                MenuIcons.icon(
                        Material.NAME_TAG,
                        text("objective.key", Placeholder.unparsed("value", str(o, "key", "-"))),
                        List.of(text("objective.key_hint")),
                        false),
                null);
        for (int i = 27; i < 36; i++) {
            menu.button(i, MenuIcons.filler(), null);
        }
        menu.button(
                27,
                MenuIcons.icon(Material.ARROW, api.messages().get("quest.dialog.back")),
                e -> editor.main(admin, d));
        menu.button(
                29,
                MenuIcons.icon(
                        Material.SPECTRAL_ARROW,
                        text("objective.move_up"),
                        List.of(text("objective.move_hint")),
                        false),
                e -> {
                    d.moveObjective(index, -1);
                    open(admin, d, Math.max(0, index - 1));
                });
        menu.button(
                30,
                MenuIcons.icon(
                        Material.SPECTRAL_ARROW,
                        text("objective.move_down"),
                        List.of(text("objective.move_hint")),
                        false),
                e -> {
                    d.moveObjective(index, 1);
                    open(admin, d, Math.min(d.objectives().size() - 1, index + 1));
                });
        menu.button(
                35,
                MenuIcons.icon(
                        Material.TNT,
                        text("objective.delete"),
                        List.of(text("objective.delete_hint")),
                        false),
                e -> {
                    if (e.getClick().isShiftClick()) {
                        d.removeObjective(index);
                        editor.main(admin, d);
                    }
                });
        menu.open(admin);
    }

    // ------------------------------------------------------------------ Target

    /** How each type picks its target (message key name). */
    static String targetMode(String type) {
        return switch (type) {
            case "catch", "donate" -> "fish";
            case "deliver" -> "deliver";
            case "talk", "affinity" -> "npc";
            case "visit" -> "visit";
            case "cutscene" -> "cutscene";
            case "use_item" -> "item";
            case "collection", "chart_percent" -> "region";
            default -> "text";
        };
    }

    /**
     * @param alt right click (grades instead of the fish list; the region list for visit)
     */
    private void target(Player admin, QuestDraft d, int index, boolean alt) {
        Map<String, Object> o = d.objectives().get(index);
        String type = str(o, "type", "catch");
        Runnable back = () -> open(admin, d, index);
        java.util.function.Consumer<String> set =
                v -> {
                    d.objectiveSet(o, "target", v);
                    back.run();
                };
        Component title = text("title.pick_target");
        switch (targetMode(type)) {
            case "fish" -> {
                List<EditorPickers.Option> options =
                        alt ? pickers.regions("region:") : pickers.catchTargets();
                if (!alt) {
                    options = new java.util.ArrayList<>(options);
                    options.addAll(pickers.fish());
                }
                pickers.pick(admin, title, options, set, back);
            }
            case "deliver" -> {
                List<EditorPickers.Option> options =
                        new java.util.ArrayList<>(pickers.catchTargets());
                options.addAll(alt ? pickers.items() : pickers.fish());
                pickers.pick(admin, title, options, set, back);
            }
            case "npc" -> {
                if (alt) {
                    q.npcs()
                            .nearest(admin, 10)
                            .ifPresentOrElse(
                                    n -> set.accept(n.id().id().toString()),
                                    () -> {
                                        api.messages().send(admin, "quest.editor.no_npc_near");
                                        back.run();
                                    });
                } else {
                    pickers.pick(admin, title, pickers.npcs(), set, back);
                }
            }
            case "visit" -> {
                if (alt) {
                    pickers.pick(admin, title, pickers.regions("region:"), set, back);
                } else {
                    editor.input(
                            admin,
                            text("target.radius_title"),
                            List.of(
                                    text(
                                            "target.radius_body",
                                            Placeholder.unparsed("here", here(admin, true, 8)))),
                            "8",
                            4,
                            false,
                            v -> {
                                int radius;
                                try {
                                    radius = Math.clamp(Integer.parseInt(v.strip()), 1, 512);
                                } catch (NumberFormatException e) {
                                    radius = 8;
                                }
                                set.accept(here(admin, true, radius));
                            });
                }
            }
            case "cutscene" -> pickers.pick(admin, title, pickers.cutscenes(), set, back);
            case "item" -> pickers.pick(admin, title, pickers.items(), set, back);
            case "region" -> {
                List<EditorPickers.Option> options = new java.util.ArrayList<>();
                options.add(
                        new EditorPickers.Option(
                                MenuIcons.icon(
                                        Material.BUCKET, api.messages().get("quest.target.any")),
                                "any"));
                options.addAll(pickers.regions("region:"));
                pickers.pick(admin, title, options, set, back);
            }
            default ->
                    editor.input(
                            admin,
                            text("target.text_title"),
                            List.of(
                                    api.messages()
                                            .get(
                                                    "quest.editor.target_hint."
                                                            + type.toLowerCase(Locale.ROOT))),
                            str(o, "target", ""),
                            120,
                            false,
                            set);
        }
    }

    /**
     * Current location: point:<world>,x,y,z,radius (point=true) or "world,x,y,z" (waypoint). Uses
     * the content world ID (world_alias).
     */
    private String here(Player admin, boolean point, int radius) {
        Location at = java.util.Objects.requireNonNull(admin.getLocation());
        String world = q.worldId(at.getWorld());
        String xyz = world + "," + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
        return point ? "point:" + xyz + "," + radius : xyz;
    }

    static Material material(String type) {
        return switch (type) {
            case "catch" -> Material.FISHING_ROD;
            case "deliver" -> Material.CHEST;
            case "talk" -> Material.VILLAGER_SPAWN_EGG;
            case "visit" -> Material.COMPASS;
            case "boss" -> Material.TRIDENT;
            case "donate" -> Material.AXOLOTL_BUCKET;
            case "use_item" -> Material.BLAZE_POWDER;
            case "cutscene" -> Material.ENDER_EYE;
            case "collection" -> Material.KNOWLEDGE_BOOK;
            case "job_level", "job_tier" -> Material.EXPERIENCE_BOTTLE;
            case "reputation" -> Material.BELL;
            case "affinity" -> Material.POPPY;
            case "flag" -> Material.WHITE_BANNER;
            case "chart_percent" -> Material.FILLED_MAP;
            default -> Material.PAPER;
        };
    }
}
