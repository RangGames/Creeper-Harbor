package wiki.creeper.harbor.npc.entity;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.profile.PlayerTextures;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.npc.Worlds;
import wiki.creeper.harbor.npc.content.Skin;

/**
 * Mannequin (1.21.9 vanilla entity) body. Not saved; no AI, gravity, collision or damage. The name
 * is shown separately via a Text Display.
 */
public final class MannequinBody implements NpcBody {

    private final Mannequin mannequin;
    private final float defaultYaw;
    private final float defaultPitch;
    private boolean looking;

    private MannequinBody(Mannequin mannequin, float yaw, float pitch) {
        this.mannequin = mannequin;
        this.defaultYaw = yaw;
        this.defaultPitch = pitch;
    }

    /**
     * @param configure extra setup right after creation (before being added to the world): PDC
     *     tags, hidden by default, etc.
     */
    public static MannequinBody spawn(
            Location at,
            @Nullable Skin skin,
            @Nullable String pose,
            java.util.function.Consumer<Mannequin> configure) {
        Mannequin m =
                at.getWorld()
                        .spawn(
                                at,
                                Mannequin.class,
                                e -> {
                                    e.setPersistent(false);
                                    e.setAI(false);
                                    e.setGravity(false);
                                    e.setInvulnerable(true);
                                    e.setSilent(true);
                                    e.setCollidable(false);
                                    e.setImmovable(true);
                                    e.setCustomNameVisible(false);
                                    e.setDescription(Component.empty());
                                    e.setRotation(at.getYaw(), at.getPitch());
                                    e.setBodyYaw(at.getYaw());
                                    if (skin != null) {
                                        e.setProfile(profile(skin));
                                    }
                                    Pose p = pose(pose);
                                    if (p != null) {
                                        e.setPose(p, true);
                                    }
                                    configure.accept(e);
                                });
        return new MannequinBody(m, at.getYaw(), at.getPitch());
    }

    /** Pose name to Pose (only those Mannequin supports). */
    public static @Nullable Pose pose(@Nullable String name) {
        if (name == null) {
            return null;
        }
        try {
            Pose p = Pose.valueOf(name.toUpperCase(Locale.ROOT));
            return Mannequin.validPoses().contains(p) ? p : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static ResolvableProfile profile(Skin skin) {
        String player = skin.player();
        if (player != null) {
            // Name-only profile: the server looks up the UUID and skin from Mojang and fills them
            // in
            return ResolvableProfile.resolvableProfile().name(player).build();
        }
        ResolvableProfile.Builder builder =
                ResolvableProfile.resolvableProfile()
                        .uuid(
                                UUID.nameUUIDFromBytes(
                                        key(skin)
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
        String value = skin.value();
        if (value != null) {
            String signature = skin.signature();
            builder.addProperty(
                    signature == null
                            ? new ProfileProperty("textures", value)
                            : new ProfileProperty("textures", value, signature));
        }
        String texture = skin.texture();
        if (texture != null) {
            builder.skinPatch(
                    patch ->
                            patch.body(Key.key(texture))
                                    .model(
                                            skin.slim()
                                                    ? PlayerTextures.SkinModel.SLIM
                                                    : PlayerTextures.SkinModel.CLASSIC));
        }
        return builder.build();
    }

    private static String key(Skin skin) {
        String texture = skin.texture();
        String value = skin.value();
        return "harbor-npc:"
                + (texture != null
                        ? texture
                        : value != null ? value : String.valueOf(skin.player()));
    }

    @Override
    public Entity entity() {
        return mannequin;
    }

    @Override
    public void lookAt(Location target) {
        Location eye = Worlds.eye(mannequin);
        double dx = target.getX() - eye.getX();
        double dy = target.getY() - eye.getY();
        double dz = target.getZ() - eye.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mannequin.setRotation(yaw, Math.clamp(pitch, -60f, 60f));
        looking = true;
    }

    @Override
    public void resetLook() {
        if (looking) {
            mannequin.setRotation(defaultYaw, defaultPitch);
            mannequin.setBodyYaw(defaultYaw);
            looking = false;
        }
    }

    @Override
    public void moveTo(Location location) {
        mannequin.teleport(location);
    }

    @Override
    public void remove() {
        mannequin.remove();
    }

    @Override
    public boolean isValid() {
        return mannequin.isValid();
    }
}
