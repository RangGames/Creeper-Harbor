package wiki.creeper.harbor.core.furniture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FurnitureSnapTest {

    @Test
    void snapsToRotationSteps() {
        assertEquals(0f, FurnitureManager.snap(10f, 4));
        assertEquals(90f, FurnitureManager.snap(80f, 4));
        assertEquals(270f, FurnitureManager.snap(-95f, 4));
        assertEquals(45f, FurnitureManager.snap(40f, 8));
        assertEquals(0f, FurnitureManager.snap(359f, 8));
        assertEquals(22.5f, FurnitureManager.snap(20f, 16));
    }
}
