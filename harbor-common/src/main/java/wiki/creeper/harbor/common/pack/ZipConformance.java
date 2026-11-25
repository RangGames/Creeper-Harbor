package wiki.creeper.harbor.common.pack;

import java.util.Locale;
import java.util.Optional;

/**
 * How strictly the client pack zip follows the zip specification (PackSquash
 * zip_spec_conformance_level, proposed). Every level is read correctly by Minecraft; the lower
 * levels trade compatibility with other zip tools for size or extraction protection.
 */
public enum ZipConformance {
    /** Standard zip that every tool opens (default). */
    PEDANTIC,
    /** Identical files share one copy of their data (smaller; some tools warn). */
    BALANCED,
    /**
     * Also extraction protection: blank local names and zeroed CRC-32. Minecraft reads it; common
     * extractors fail or report corruption. Not real security (PackSquash says the same), and some
     * hosts or antivirus may flag such zips.
     */
    DISREGARD;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ZipConformance> parse(String raw) {
        for (ZipConformance z : values()) {
            if (z.key().equals(raw.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(z);
            }
        }
        return Optional.empty();
    }
}
