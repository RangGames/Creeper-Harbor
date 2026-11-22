package wiki.creeper.harbor.npc.cutscene;

import java.time.Duration;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.npc.NpcKeys;
import wiki.creeper.harbor.npc.Worlds;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.content.Spot;
import wiki.creeper.harbor.npc.entity.MannequinBody;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.logic.CameraPlan;

/** Per-tick playback of a cutscene: camera, actors, lines, titles, and sounds. */
final class CutsceneTicker {

    private final Plugin plugin;
    private final HarborApi api;
    private final NpcManager npcs;

    CutsceneTicker(Plugin plugin, HarborApi api, NpcManager npcs) {
        this.plugin = plugin;
        this.api = api;
        this.npcs = npcs;
    }

    private NpcBalance balance() {
        return new NpcBalance(api.content().snapshot().single(Balance.TYPE));
    }

    static Location location(World world, Spot spot) {
        return new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch());
    }

    void attach(Playback pb) {
        ItemDisplay camera = pb.camera;
        if (camera != null && !camera.equals(pb.player.getSpectatorTarget())) {
            pb.player.setSpectatorTarget(camera);
        }
    }

    /** Advances one tick (camera · actors · lines · titles · sounds). */
    void step(Playback pb) {
        int t = pb.tick++;
        Player player = pb.player;
        CutsceneDef def = pb.def;
        ItemDisplay camera = pb.camera;
        if (camera == null) {
            return;
        }
        World world = camera.getWorld();
        attach(pb);
        // camera: send moves that are due, and set the interpolation length of the next tick's move
        // one
        // tick ahead
        while (pb.moveIndex < pb.moves.size() && pb.moves.get(pb.moveIndex).tick() <= t) {
            camera.teleport(location(world, pb.moves.get(pb.moveIndex).target()));
            pb.moveIndex++;
        }
        if (pb.moveIndex < pb.moves.size() && pb.moves.get(pb.moveIndex).tick() == t + 1) {
            camera.setTeleportDuration(Math.clamp(pb.moves.get(pb.moveIndex).duration(), 0, 59));
        }
        // actor paths (interpolated every tick)
        for (CutsceneDef.Actor actor : def.actors()) {
            MannequinBody body = pb.actors.get(actor.id());
            if (body != null && actor.path().size() > 1 && t <= actor.path().getLast().t()) {
                body.moveTo(location(world, CameraPlan.at(actor.path(), t)));
            }
        }
        // lines
        while (pb.lineIndex < def.lines().size() && def.lines().get(pb.lineIndex).t() <= t) {
            line(pb, def.lines().get(pb.lineIndex++), t);
        }
        if (pb.subtitle != null) {
            if (t >= pb.subtitleUntil) {
                pb.subtitle = null;
            } else if ((pb.subtitleUntil - t) % 40 == 0) {
                api.ui()
                        .actionBar(
                                player,
                                pb.subtitle); // the action bar fades after a few seconds, so resend
            }
        }
        pb.lineDisplays
                .entrySet()
                .removeIf(
                        e -> {
                            if (t >= e.getValue()) {
                                e.getKey().remove();
                                pb.entities.remove(e.getKey());
                                return true;
                            }
                            return false;
                        });
        // titles
        while (pb.titleIndex < def.titles().size() && def.titles().get(pb.titleIndex).t() <= t) {
            CutsceneDef.TitleCue cue = def.titles().get(pb.titleIndex++);
            String sub = cue.subtitle();
            api.ui()
                    .title(
                            player,
                            api.messages().get(cue.title()),
                            sub == null ? Component.empty() : api.messages().get(sub),
                            Duration.ofMillis(cue.fadeIn() * 50L),
                            Duration.ofMillis(cue.stay() * 50L),
                            Duration.ofMillis(cue.fadeOut() * 50L));
        }
        // sounds
        while (pb.soundIndex < def.sounds().size() && def.sounds().get(pb.soundIndex).t() <= t) {
            CutsceneDef.SoundCue cue = def.sounds().get(pb.soundIndex++);
            // harbor: sounds fall back depending on pack use (Core HarborSounds, '15. Sound')
            HarborSounds.play(
                    player,
                    cue.sound(),
                    HarborSounds.Options.DEFAULT.withVolume(cue.volume()).withPitch(cue.pitch()));
        }
    }

    private void line(Playback pb, CutsceneDef.Line line, int t) {
        Player player = pb.player;
        Component text =
                api.messages().get(line.text(), Placeholder.unparsed("player", player.getName()));
        String actorId = line.actor();
        Component subtitle;
        if (actorId == null) {
            subtitle =
                    api.messages()
                            .get("npc.cutscene.narration", Placeholder.component("text", text));
        } else {
            Component name = actorName(pb.def, actorId, player);
            subtitle =
                    api.messages()
                            .get(
                                    "npc.cutscene.subtitle",
                                    Placeholder.component("name", name),
                                    Placeholder.component("text", text));
            MannequinBody body = pb.actors.get(actorId);
            if (body != null && pb.entities.size() < balance().cutsceneMaxEntities()) {
                Location above = Worlds.loc(body.entity()).add(0, 2.2, 0);
                TextDisplay display =
                        above.getWorld()
                                .spawn(
                                        above,
                                        TextDisplay.class,
                                        d -> {
                                            d.setPersistent(false);
                                            d.setVisibleByDefault(false);
                                            d.setBillboard(Display.Billboard.CENTER);
                                            d.text(text);
                                            d.setLineWidth(200);
                                            NpcKeys.tag(
                                                    d, NpcKeys.CUTSCENE, pb.def.id().toString());
                                        });
                player.showEntity(plugin, display);
                pb.entities.add(display);
                pb.lineDisplays.put(display, t + line.ticks());
            }
        }
        pb.subtitle = subtitle;
        pb.subtitleUntil = t + line.ticks();
        api.ui().actionBar(player, subtitle);
    }

    private Component actorName(CutsceneDef def, String actorId, Player viewer) {
        Optional<CutsceneDef.Actor> actor = def.actor(actorId);
        if (actor.isPresent()) {
            String name = actor.get().name();
            if (name != null) {
                return MiniMessage.miniMessage().deserialize(name);
            }
            wiki.creeper.harbor.common.id.NpcId npc = actor.get().npc();
            if (npc != null) {
                return npcs.displayName(viewer, npc);
            }
        }
        return Component.text(actorId);
    }
}
