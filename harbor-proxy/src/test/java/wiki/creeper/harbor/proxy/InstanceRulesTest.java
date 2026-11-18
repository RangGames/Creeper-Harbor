package wiki.creeper.harbor.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstanceRulesTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void rejoinValueFormats() {
        assertEquals(Optional.of("raid-1"), InstanceRules.parseRejoin("raid-1"));
        assertEquals(Optional.of("raid-1"), InstanceRules.parseRejoin(" raid-1|0190-abc "));
        assertEquals(
                Optional.of("dungeon_2"),
                InstanceRules.parseRejoin("{\"server\":\"dungeon_2\",\"encounter\":\"x\"}"));
        assertTrue(InstanceRules.parseRejoin(null).isEmpty());
        assertTrue(InstanceRules.parseRejoin("").isEmpty());
        assertTrue(InstanceRules.parseRejoin("{\"slot\":1}").isEmpty());
        assertTrue(InstanceRules.parseRejoin("{broken").isEmpty());
        assertTrue(InstanceRules.parseRejoin("Raid 1").isEmpty());
        assertEquals("harbor:boss:rejoin:" + A, InstanceRules.rejoinKey(A));
    }

    @Test
    void summaryReportsPartialFailures() {
        assertEquals(
                "ok",
                InstanceRules.summarize(
                        List.of(
                                new InstanceRules.MemberResult(A, true, "ok"),
                                new InstanceRules.MemberResult(B, true, "ok"))));
        assertEquals(
                "partial:1/2:" + B + "=busy",
                InstanceRules.summarize(
                        List.of(
                                new InstanceRules.MemberResult(A, true, "ok"),
                                new InstanceRules.MemberResult(B, false, "busy"))));
        assertEquals(
                "partial:0/2:" + A + "=offline;" + B + "=prepare_failed",
                InstanceRules.summarize(
                        List.of(
                                new InstanceRules.MemberResult(A, false, "offline"),
                                new InstanceRules.MemberResult(B, false, "prepare_failed"))));
    }
}
