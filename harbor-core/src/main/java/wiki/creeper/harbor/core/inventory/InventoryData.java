package wiki.creeper.harbor.core.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 동기화하는 플레이어 상태 한 벌 (서버 API 없음, 추가 제안). 아이템·PDC는 Paper가 직렬화한 바이트를 그대로 담는다. 변경 불가 (배열은 꺼낼 때 복사).
 *
 * <p>바이너리 형식 {@value #FORMAT}: format(int) · 인벤토리 · 엔더 상자 · PDC (각 길이+바이트) · 레벨 · 경험치 진행 · 총 경험치 ·
 * 체력 · 배고픔 · 포화 · 피로 · 선택 칸 · 효과 수와 효과들.
 */
public final class InventoryData {

    public static final int FORMAT = 1;

    /**
     * 포션 효과 하나.
     *
     * @param type 효과 키 (minecraft:speed)
     */
    public record Effect(
            String type,
            int duration,
            int amplifier,
            boolean ambient,
            boolean particles,
            boolean icon) {}

    private final byte[] contents;
    private final byte[] ender;
    private final byte[] pdc;
    private final int level;
    private final float exp;
    private final int totalExp;
    private final double health;
    private final int food;
    private final float saturation;
    private final float exhaustion;
    private final int heldSlot;
    private final List<Effect> effects;

    public InventoryData(
            byte[] contents,
            byte[] ender,
            byte[] pdc,
            int level,
            float exp,
            int totalExp,
            double health,
            int food,
            float saturation,
            float exhaustion,
            int heldSlot,
            List<Effect> effects) {
        this.contents = contents.clone();
        this.ender = ender.clone();
        this.pdc = pdc.clone();
        this.level = level;
        this.exp = exp;
        this.totalExp = totalExp;
        this.health = health;
        this.food = food;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
        this.heldSlot = heldSlot;
        this.effects = List.copyOf(effects);
    }

    public byte[] contents() {
        return contents.clone();
    }

    public byte[] ender() {
        return ender.clone();
    }

    public byte[] pdc() {
        return pdc.clone();
    }

    public int level() {
        return level;
    }

    public float exp() {
        return exp;
    }

    public int totalExp() {
        return totalExp;
    }

    public double health() {
        return health;
    }

    public int food() {
        return food;
    }

    public float saturation() {
        return saturation;
    }

    public float exhaustion() {
        return exhaustion;
    }

    public int heldSlot() {
        return heldSlot;
    }

    public List<Effect> effects() {
        return effects;
    }

    public byte[] encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT);
            writeBytes(out, contents);
            writeBytes(out, ender);
            writeBytes(out, pdc);
            out.writeInt(level);
            out.writeFloat(exp);
            out.writeInt(totalExp);
            out.writeDouble(health);
            out.writeInt(food);
            out.writeFloat(saturation);
            out.writeFloat(exhaustion);
            out.writeInt(heldSlot);
            out.writeInt(effects.size());
            for (Effect e : effects) {
                out.writeUTF(e.type());
                out.writeInt(e.duration());
                out.writeInt(e.amplifier());
                out.writeBoolean(e.ambient());
                out.writeBoolean(e.particles());
                out.writeBoolean(e.icon());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * @throws IOException 형식이 틀리거나 모르는 format 번호
     */
    public static InventoryData decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int format = in.readInt();
            if (format != FORMAT) {
                throw new IOException("모르는 인벤토리 형식: " + format);
            }
            byte[] contents = readBytes(in);
            byte[] ender = readBytes(in);
            byte[] pdc = readBytes(in);
            int level = in.readInt();
            float exp = in.readFloat();
            int totalExp = in.readInt();
            double health = in.readDouble();
            int food = in.readInt();
            float saturation = in.readFloat();
            float exhaustion = in.readFloat();
            int heldSlot = in.readInt();
            int count = in.readInt();
            if (count < 0 || count > 256) {
                throw new IOException("효과 수가 이상합니다: " + count);
            }
            List<Effect> effects = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                effects.add(
                        new Effect(
                                in.readUTF(),
                                in.readInt(),
                                in.readInt(),
                                in.readBoolean(),
                                in.readBoolean(),
                                in.readBoolean()));
            }
            return new InventoryData(
                    contents,
                    ender,
                    pdc,
                    level,
                    exp,
                    totalExp,
                    health,
                    food,
                    saturation,
                    exhaustion,
                    heldSlot,
                    effects);
        }
    }

    /** 내용 해시 (기록 중복 판단). */
    public static String hash(byte[] encoded) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) {
            throw new IOException("길이가 이상합니다: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }
}
