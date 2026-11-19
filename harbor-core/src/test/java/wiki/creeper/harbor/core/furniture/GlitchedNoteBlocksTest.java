package wiki.creeper.harbor.core.furniture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class GlitchedNoteBlocksTest {

    @Test
    void freshStatesAreOnlyTheNewOnes() {
        assertEquals(List.of(1, 2), GlitchedNoteBlocks.fresh(new int[] {1, 2}, null));
        assertEquals(List.of(3), GlitchedNoteBlocks.fresh(new int[] {1, 2, 3}, new int[] {1, 2}));
        assertEquals(List.of(), GlitchedNoteBlocks.fresh(new int[] {1}, new int[] {1, 2}));
    }

    @Test
    void packsPositions() {
        for (int[] p : new int[][] {{0, -64, 0}, {15, 319, 15}, {7, 0, 3}, {3, -2048, 12}}) {
            long packed = GlitchedNoteBlocks.pos(p[0], p[1], p[2]);
            assertEquals(p[0], GlitchedNoteBlocks.x(packed));
            assertEquals(p[1], GlitchedNoteBlocks.y(packed));
            assertEquals(p[2], GlitchedNoteBlocks.z(packed));
        }
    }
}
