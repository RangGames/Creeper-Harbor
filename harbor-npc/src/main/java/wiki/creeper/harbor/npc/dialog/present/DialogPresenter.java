package wiki.creeper.harbor.npc.dialog.present;

import org.bukkit.entity.Player;

/**
 * One way of drawing the dialog screen ({@link wiki.creeper.harbor.npc.api.DialogStyle}). Main
 * thread only.
 */
public interface DialogPresenter {

    /** Shows one page. Overwrites the same player's previous page. */
    void show(Player player, Frame frame);

    /** Removes leftover display when the session ends (speech bubble, title, etc.). */
    default void clear(Player player) {}

    /** On every watch timer tick (resend fading displays, check speech bubble position). */
    default void refresh(Player player) {}
}
