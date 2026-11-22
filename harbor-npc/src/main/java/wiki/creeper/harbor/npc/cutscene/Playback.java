package wiki.creeper.harbor.npc.cutscene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.task.Sequence;
import wiki.creeper.harbor.npc.api.CutsceneResult;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.data.RestoreInfo;
import wiki.creeper.harbor.npc.entity.MannequinBody;
import wiki.creeper.harbor.npc.logic.CameraPlan;

/** One cutscene being played (an independent instance per player). */
final class Playback {
    final Player player;
    final CutsceneDef def;
    final RestoreInfo restore;
    final CompletableFuture<CutsceneResult> future = new CompletableFuture<>();
    final List<Entity> entities = new ArrayList<>();
    final Map<String, MannequinBody> actors = new LinkedHashMap<>();
    final Map<TextDisplay, Integer> lineDisplays = new HashMap<>();
    @Nullable ItemDisplay camera;
    List<CameraPlan.Move> moves = List.of();
    Sequence.@Nullable Handle handle;
    CutsceneResult cancelResult = CutsceneResult.CANCELLED;
    boolean begun;
    boolean finished;
    boolean skipAllowed;
    boolean sneaking;
    int lastSneakPress = -1_000;
    int tick;
    int moveIndex;
    int lineIndex;
    int titleIndex;
    int soundIndex;
    @Nullable Component subtitle;
    int subtitleUntil;

    Playback(Player player, CutsceneDef def, RestoreInfo restore) {
        this.player = player;
        this.def = def;
        this.restore = restore;
    }
}
