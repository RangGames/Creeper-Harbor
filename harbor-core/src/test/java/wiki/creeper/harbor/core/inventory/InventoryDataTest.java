package wiki.creeper.harbor.core.inventory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryDataTest {

    private static InventoryData sample(int level) {
        return new InventoryData(
                new byte[] {1, 2, 3},
                new byte[] {4},
                new byte[] {},
                level,
                0.5f,
                123,
                17.5,
                18,
                3.5f,
                0.25f,
                4,
                List.of(new InventoryData.Effect("minecraft:speed", 200, 1, false, true, true)));
    }

    @Test
    void roundTrips() throws IOException {
        InventoryData data = sample(7);
        InventoryData back = InventoryData.decode(data.encode());
        assertArrayEquals(data.contents(), back.contents());
        assertArrayEquals(data.ender(), back.ender());
        assertEquals(7, back.level());
        assertEquals(17.5, back.health());
        assertEquals(4, back.heldSlot());
        assertEquals(data.effects(), back.effects());
        assertArrayEquals(data.encode(), back.encode());
    }

    @Test
    void hashFollowsContent() {
        assertEquals(
                InventoryData.hash(sample(1).encode()), InventoryData.hash(sample(1).encode()));
        assertNotEquals(
                InventoryData.hash(sample(1).encode()), InventoryData.hash(sample(2).encode()));
    }

    @Test
    void rejectsUnknownFormat() {
        byte[] bad = sample(1).encode();
        bad[3] = 99;
        assertThrows(IOException.class, () -> InventoryData.decode(bad));
    }

    @Test
    void arraysAreCopied() {
        byte[] raw = {9};
        InventoryData data =
                new InventoryData(raw, raw, raw, 0, 0f, 0, 20, 20, 5f, 0f, 0, List.of());
        raw[0] = 1;
        assertEquals(9, data.contents()[0]);
    }
}
