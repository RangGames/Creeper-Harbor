package wiki.creeper.harbor.common.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Minimal zip writer for the non-standard levels of {@link ZipConformance} (PackSquash
 * zip_spec_conformance_level, proposed).
 *
 * <p>Minecraft opens packs with {@code java.util.zip.ZipFile}, which reads the central directory,
 * takes each entry's data offset from it, only uses the local header's name/extra lengths to skip
 * to the data, and does not verify CRC-32. That is what makes the tricks below safe for the game
 * while other tools may refuse the file:
 *
 * <ul>
 *   <li>{@link ZipConformance#BALANCED}: entries with identical content share one copy of the data
 *       (several central directory entries point at the same local header)
 *   <li>{@link ZipConformance#DISREGARD}: also blank local header names and zero CRC-32 fields, so
 *       stream extractors see nameless entries and checking extractors report corruption; and the
 *       end record says this disk holds 0 entries (only the total is right), so tools that take the
 *       archive as split or spanned refuse it. That catches the extractors that read only the
 *       central directory and skip CRC checks (Windows Explorer and .NET ZipFile extracted the
 *       first two tricks cleanly). {@code java.util.zip.ZipFile} reads only the total, the
 *       directory size and offset from the end record, so the game is unaffected
 * </ul>
 *
 * Plain zip32 only (≤ 65535 entries, &lt; 4 GiB); larger packs fail with an IOException.
 * Deterministic: fixed timestamps, no extra fields.
 */
final class PackZipWriter implements PackBuilder.EntrySink {

    private static final int LOC_SIG = 0x04034b50;
    private static final int CEN_SIG = 0x02014b50;
    private static final int END_SIG = 0x06054b50;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;

    /** Bit 11: names are UTF-8. */
    private static final int UTF8_FLAG = 0x0800;

    /** 2000-01-01 00:00 in DOS date/time. */
    private static final int DOS_DATE = (20 << 9) | (1 << 5) | 1;

    private static final int DOS_TIME = 0;

    private record Central(String name, int method, long crc, long size, long csize, long offset) {}

    private final OutputStream out;
    private final boolean dedup;
    private final boolean protect;
    private final List<Central> central = new ArrayList<>();
    private final Map<String, Central> byContent = new HashMap<>();
    private long position;
    private int shared;

    PackZipWriter(OutputStream out, ZipConformance level) {
        this.out = out;
        this.dedup = level != ZipConformance.PEDANTIC;
        this.protect = level == ZipConformance.DISREGARD;
    }

    /** Entries whose data was shared with an earlier identical entry. */
    int shared() {
        return shared;
    }

    @Override
    public void put(String name, byte[] data, boolean storeIfIncompressible) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 0xffff) {
            throw new IOException("파일 이름이 너무 깁니다: " + name);
        }
        String key = dedup ? sha256(data) : null;
        Central same = key == null ? null : byContent.get(key);
        if (same != null) {
            central.add(
                    new Central(
                            name,
                            same.method(),
                            same.crc(),
                            same.size(),
                            same.csize(),
                            same.offset()));
            shared++;
            return;
        }
        byte[] deflated = rawDeflate(data);
        boolean store = deflated.length >= data.length;
        byte[] body = store ? data : deflated;
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        long crc = protect ? 0 : crc32.getValue();
        Central entry =
                new Central(
                        name, store ? STORED : DEFLATED, crc, data.length, body.length, position);
        if (position + body.length > 0xffffffffL) {
            throw new IOException("4GiB가 넘는 팩은 zip: pedantic만 지원합니다");
        }
        byte[] localName = protect ? new byte[0] : nameBytes;
        ByteBuffer loc = ByteBuffer.allocate(30 + localName.length).order(ByteOrder.LITTLE_ENDIAN);
        loc.putInt(LOC_SIG)
                .putShort((short) 20)
                .putShort((short) UTF8_FLAG)
                .putShort((short) entry.method())
                .putShort((short) DOS_TIME)
                .putShort((short) DOS_DATE)
                .putInt((int) crc)
                .putInt((int) entry.csize())
                .putInt((int) entry.size())
                .putShort((short) localName.length)
                .putShort((short) 0)
                .put(localName);
        write(loc.array());
        write(body);
        central.add(entry);
        if (key != null) {
            byContent.put(key, entry);
        }
    }

    @Override
    public void close() throws IOException {
        if (central.size() > 0xffff) {
            throw new IOException("항목이 65535개를 넘는 팩은 zip: pedantic만 지원합니다");
        }
        long cenStart = position;
        for (Central c : central) {
            byte[] nameBytes = c.name().getBytes(StandardCharsets.UTF_8);
            ByteBuffer cen =
                    ByteBuffer.allocate(46 + nameBytes.length).order(ByteOrder.LITTLE_ENDIAN);
            cen.putInt(CEN_SIG)
                    .putShort((short) 20)
                    .putShort((short) 20)
                    .putShort((short) UTF8_FLAG)
                    .putShort((short) c.method())
                    .putShort((short) DOS_TIME)
                    .putShort((short) DOS_DATE)
                    .putInt((int) c.crc())
                    .putInt((int) c.csize())
                    .putInt((int) c.size())
                    .putShort((short) nameBytes.length)
                    .putShort((short) 0)
                    .putShort((short) 0)
                    .putShort((short) 0)
                    .putShort((short) 0)
                    .putInt(0)
                    .putInt((int) c.offset())
                    .put(nameBytes);
            write(cen.array());
        }
        long cenSize = position - cenStart;
        ByteBuffer end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        end.putInt(END_SIG)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) (protect ? 0 : central.size()))
                .putShort((short) central.size())
                .putInt((int) cenSize)
                .putInt((int) cenStart)
                .putShort((short) 0);
        write(end.array());
        out.close();
    }

    private void write(byte[] bytes) throws IOException {
        out.write(bytes);
        position += bytes.length;
    }

    private static byte[] rawDeflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] chunk = new byte[64 * 1024];
            while (!deflater.finished()) {
                int n = deflater.deflate(chunk);
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
