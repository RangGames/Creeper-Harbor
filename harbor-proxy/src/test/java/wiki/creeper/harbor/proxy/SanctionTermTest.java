package wiki.creeper.harbor.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SanctionTermTest {

    private static Duration length(String raw) {
        return SanctionTerm.parse(raw).orElseThrow().length();
    }

    @Test
    void parsesSingleUnits() {
        assertEquals(Duration.ofSeconds(90), length("90s"));
        assertEquals(Duration.ofMinutes(30), length("30m"));
        assertEquals(Duration.ofHours(12), length("12h"));
        assertEquals(Duration.ofDays(7), length("7d"));
        assertEquals(Duration.ofDays(14), length("2w"));
        assertEquals(Duration.ofDays(3), length("3D"));
    }

    @Test
    void parsesCombinedAndKoreanUnits() {
        assertEquals(Duration.ofDays(1).plusHours(12), length("1d12h"));
        assertEquals(Duration.ofHours(2).plusMinutes(30), length("2시간30분"));
        assertEquals(Duration.ofDays(7), length("1주"));
        assertEquals(Duration.ofDays(3), length("3일"));
    }

    @Test
    void permanentWords() {
        for (String raw : new String[] {"perm", "Permanent", "영구"}) {
            SanctionTerm term = SanctionTerm.parse(raw).orElseThrow();
            assertTrue(term.permanent(), raw);
            assertNull(term.expiresAt(Instant.EPOCH));
        }
    }

    @Test
    void rejectsMalformedZeroAndTooLong() {
        for (String raw :
                new String[] {"", "7", "d7", "7x", "10mo", "1d 2h", "0m", "abc", "3650d1s"}) {
            assertTrue(SanctionTerm.parse(raw).isEmpty(), raw);
        }
        assertTrue(SanctionTerm.parse("3650d").isPresent());
    }

    @Test
    void expiresAtAddsLength() {
        Instant now = Instant.parse("2027-01-01T00:00:00Z");
        assertEquals(
                Instant.parse("2027-01-08T00:00:00Z"),
                SanctionTerm.parse("7d").orElseThrow().expiresAt(now));
    }

    @Test
    void argsSplitTermAndReason() {
        SanctionTerm.Args args = SanctionTerm.parseArgs("7d 도배 반복", SanctionTerm.PERMANENT, "기본");
        assertEquals(Duration.ofDays(7), args.term().length());
        assertEquals("도배 반복", args.reason());

        SanctionTerm.Args reasonOnly =
                SanctionTerm.parseArgs("  욕설 사용 ", SanctionTerm.PERMANENT, "기본");
        assertTrue(reasonOnly.term().permanent());
        assertEquals("욕설 사용", reasonOnly.reason());

        SanctionTerm.Args termOnly = SanctionTerm.parseArgs("30m", SanctionTerm.PERMANENT, "기본");
        assertEquals(Duration.ofMinutes(30), termOnly.term().length());
        assertEquals("기본", termOnly.reason());

        SanctionTerm.Args empty = SanctionTerm.parseArgs("", SanctionTerm.PERMANENT, "기본");
        assertTrue(empty.term().permanent());
        assertEquals("기본", empty.reason());
    }

    @Test
    void looksLikeTermFlagsTyposButNotKoreanReasons() {
        assertTrue(SanctionTerm.looksLikeTerm("7x"));
        assertTrue(SanctionTerm.looksLikeTerm("99999d"));
        assertFalse(SanctionTerm.looksLikeTerm("7d"));
        assertFalse(SanctionTerm.looksLikeTerm("3회"));
        assertFalse(SanctionTerm.looksLikeTerm("욕설"));
        assertEquals("7x", SanctionTerm.firstToken(" 7x 사유 "));
    }

    @Test
    void describesHumanReadable() {
        assertEquals("1일 12시간", SanctionTerm.parse("1d12h").orElseThrow().describe("영구"));
        assertEquals("30분", SanctionTerm.parse("30m").orElseThrow().describe("영구"));
        assertEquals("영구", SanctionTerm.PERMANENT.describe("영구"));
    }
}
