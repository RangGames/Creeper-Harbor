package wiki.creeper.harbor.collection.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Collection registration toast (docs 20 4.4 step 3) without a datapack. Once after startup the
 * advancements {@code harbor:codex/root} and {@code harbor:codex/<region>/<fish>} are loaded into
 * the server in memory only ({@code UnsafeValues.loadAdvancement(key, json, false)}: not written to
 * the bukkit datapack folder). Every species advancement is hidden with an impossible criterion and
 * only shows a toast: on a first season registration it is revoked, awarded (toast) and revoked
 * again {@code collection.toast.keep_ticks} later, so the advancement screen never keeps a
 * per-server copy of the collection and a new season shows the toast again. If loading fails (or
 * balance.yml {@code collection.toast.enabled} is false) the old title notice is used. Species
 * added by a content reload get the title notice until the next restart. Main thread only.
 */
final class CodexToasts {

    private static final String NAMESPACE = "harbor";
    private static final String ROOT = "codex/root";

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final Set<String> loaded = new HashSet<>();

    CodexToasts(HarborApi api, Tasks tasks, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
    }

    /** Called once on the first tick after startup (no player is online yet). */
    void register() {
        ContentSnapshot snapshot = api.content().snapshot();
        if (!enabled(snapshot)) {
            return;
        }
        if (!load(ROOT, rootJson())) {
            log.warn("도감 토스트 발전과제를 등록하지 못해 제목 알림을 씁니다");
            return;
        }
        int count = 0;
        for (FishDef fish : snapshot.all(FishDef.TYPE).values()) {
            String path = path(fish);
            if (load(path, fishJson(snapshot, fish, true))
                    || load(path, fishJson(snapshot, fish, false))) {
                count++;
            }
        }
        log.info("도감 토스트 발전과제 {}개를 메모리에 등록했습니다", count);
    }

    /** First season registration notice: toast if loaded, otherwise title. Sound either way. */
    void announce(Player player, FishDef fish) {
        if (!toast(player, fish)) {
            api.ui()
                    .title(
                            player,
                            api.messages().get("collection.registered.title"),
                            api.messages()
                                    .get(
                                            "item.name." + fish.grade().key(),
                                            Placeholder.unparsed("name", fish.name())),
                            Duration.ofMillis(200),
                            Duration.ofSeconds(2),
                            Duration.ofMillis(500));
        }
        HarborSounds.play(
                player, "harbor:ui.codex_register"); // '15. Sound' 4.5 collection registration
    }

    private boolean toast(Player player, FishDef fish) {
        if (!enabled(api.content().snapshot())) {
            return false;
        }
        String path = path(fish);
        Advancement adv =
                loaded.contains(path)
                        ? Bukkit.getAdvancement(new NamespacedKey(NAMESPACE, path))
                        : null;
        if (adv == null) {
            return false;
        }
        AdvancementProgress progress = player.getAdvancementProgress(adv);
        revoke(progress);
        for (String criterion : List.copyOf(progress.getRemainingCriteria())) {
            progress.awardCriteria(criterion);
        }
        long keep =
                Math.max(
                        1,
                        api.content()
                                .snapshot()
                                .single(Balance.TYPE)
                                .integer("collection.toast.keep_ticks", 20));
        var _ =
                tasks.later(
                        keep,
                        () -> {
                            if (player.isOnline()) {
                                revoke(player.getAdvancementProgress(adv));
                            }
                        });
        return true;
    }

    private static void revoke(AdvancementProgress progress) {
        for (String criterion : List.copyOf(progress.getAwardedCriteria())) {
            progress.revokeCriteria(criterion);
        }
    }

    private static boolean enabled(ContentSnapshot snapshot) {
        return snapshot.single(Balance.TYPE).bool("collection.toast.enabled", true);
    }

    static String path(FishDef fish) {
        return "codex/" + fish.region().id().name() + "/" + fish.id().id().name();
    }

    /**
     * Loads one advancement in memory (persist = false). UnsafeValues is deprecated as a whole
     * class in the Paper API but is the only runtime way to add an advancement without a datapack.
     */
    @SuppressWarnings("deprecation")
    private boolean load(String path, String json) {
        if (loaded.contains(path)) {
            return true;
        }
        NamespacedKey key = new NamespacedKey(NAMESPACE, path);
        try {
            if (Bukkit.getAdvancement(key) == null
                    && Bukkit.getUnsafe().loadAdvancement(Key.key(NAMESPACE, path), json, false)
                            == null) {
                return false;
            }
        } catch (RuntimeException e) {
            log.debug("발전과제 등록 실패 {}: {}", path, String.valueOf(e.getMessage()));
            return false;
        }
        loaded.add(path);
        return true;
    }

    private String rootJson() {
        JsonObject display = new JsonObject();
        display.add("icon", icon("minecraft:book", null));
        display.add("title", text(api.messages().get("collection.toast.root_title")));
        display.add("description", text(api.messages().get("collection.toast.root_description")));
        display.addProperty("show_toast", false);
        display.addProperty("announce_to_chat", false);
        display.addProperty("hidden", true);
        return advancement(null, display).toString();
    }

    private String fishJson(ContentSnapshot snapshot, FishDef fish, boolean model) {
        String base =
                snapshot.single(Balance.TYPE)
                        .string("items.fish_base_item")
                        .orElse("minecraft:cod");
        String itemModel =
                model && ItemModels.available(snapshot, fish.model()) ? fish.model() : null;
        String region =
                snapshot.get(RegionDef.TYPE, fish.region().id())
                        .map(RegionDef::name)
                        .orElse(fish.region().id().name());
        JsonObject display = new JsonObject();
        display.add("icon", icon(base, itemModel));
        display.add(
                "title",
                text(
                        api.messages()
                                .get(
                                        "item.name." + fish.grade().key(),
                                        Placeholder.unparsed("name", fish.name()))));
        display.add(
                "description",
                text(
                        api.messages()
                                .get(
                                        "collection.toast.description",
                                        Placeholder.unparsed("no", String.valueOf(fish.no())),
                                        Placeholder.unparsed("region", region))));
        display.addProperty("frame", frame(fish.grade()));
        display.addProperty("show_toast", true);
        display.addProperty("announce_to_chat", false);
        display.addProperty("hidden", true);
        return advancement(NAMESPACE + ":" + ROOT, display).toString();
    }

    /** Toast frame by grade: task, goal (epic), challenge (legendary and up). */
    static String frame(Grade grade) {
        if (grade.isAtLeast(Grade.LEGENDARY)) {
            return "challenge";
        }
        return grade.isAtLeast(Grade.EPIC) ? "goal" : "task";
    }

    private static JsonObject advancement(@Nullable String parent, JsonObject display) {
        JsonObject root = new JsonObject();
        if (parent != null) {
            root.addProperty("parent", parent);
        }
        JsonObject trigger = new JsonObject();
        trigger.addProperty("trigger", "minecraft:impossible");
        JsonObject criteria = new JsonObject();
        criteria.add("toast", trigger);
        root.add("criteria", criteria);
        root.add("display", display);
        return root;
    }

    private static JsonObject icon(String item, @Nullable String model) {
        JsonObject icon = new JsonObject();
        icon.addProperty("id", item);
        if (model != null) {
            JsonObject components = new JsonObject();
            components.addProperty("minecraft:item_model", model);
            icon.add("components", components);
        }
        return icon;
    }

    private static JsonElement text(Component component) {
        return GsonComponentSerializer.gson().serializeToTree(component);
    }
}
