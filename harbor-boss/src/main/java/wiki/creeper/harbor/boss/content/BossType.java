package wiki.creeper.harbor.boss.content;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Boss type ('7. Balance design' chapter 9). Defaults for HP increase per player, time limit,
 * weekly count, clear gold and telegraph time come from balance.yml {@code boss.types.<type>}
 * ({@link BossBalance}).
 */
public enum BossType implements Keyed {
    /** Intro boss (Gnome). */
    INTRO,
    /** Sea boss (moray eel, sturgeon, giant squid). */
    SEA,
    /** Dimension boss (Flame King Carp, Void Nebula Fish). */
    DIMENSION,
    /** Season raid (Lord of the Trench, 'Wrath of the Lord'). */
    RAID,
    /** World boss (Verdigris Thunderfish). */
    WORLD;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
