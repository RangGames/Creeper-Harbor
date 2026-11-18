package wiki.creeper.harbor.common.content.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

class SoundTableTest {

    private static SoundTable parse(String yaml, ValidationErrors errors) {
        YamlNode node = YamlNode.parse("sounds.yml", new StringReader(yaml), errors).orElseThrow();
        return SoundTable.parse(node, new ParseContext());
    }

    @Test
    void readsEventsAndCues() {
        ValidationErrors errors = new ValidationErrors();
        SoundTable table =
                parse(
                        """
                        events:
                          fishing.reel_loop: {source: player, volume: 0.7, loop: 40}
                          ui.coin: {source: ui, volume: 0.6, fallback: "minecraft:entity.experience_orb.pickup", fallback_pitch: 1.4}
                        currency:
                          gold: {sound: "harbor:ui.coin", reasons: [FISH_SELL, SHOP_BUY]}
                          mulbineul: {sound: "harbor:ui.mulbineul", gain_only: true, except: [LEVEL_UP]}
                        """,
                        errors);
        assertTrue(errors.isEmpty(), errors.format());

        SoundTable.Event reel = table.event("fishing.reel_loop").orElseThrow();
        assertEquals("player", reel.source());
        assertEquals(40, reel.loopTicks());
        assertNull(reel.fallback());
        assertEquals(0.7f, reel.fallbackVolume(), 1e-6); // 대체 음량은 volume을 따른다

        SoundTable.Event coin = table.event("ui.coin").orElseThrow();
        assertEquals("minecraft:entity.experience_orb.pickup", coin.fallback());
        assertEquals(1.4f, coin.fallbackPitch(), 1e-6);
        assertEquals(1.0f, coin.pitch(), 1e-6);

        SoundTable.CurrencyCue gold = table.cue(Currency.GOLD).orElseThrow();
        assertTrue(gold.matches(LedgerReason.FISH_SELL, 120));
        assertTrue(gold.matches(LedgerReason.SHOP_BUY, -50));
        assertFalse(gold.matches(LedgerReason.QUEST_REWARD, 100));

        SoundTable.CurrencyCue mul = table.cue(Currency.MULBINEUL).orElseThrow();
        assertTrue(mul.matches(LedgerReason.CODEX_REWARD, 1));
        assertFalse(mul.matches(LedgerReason.LEVEL_UP, 1));
        assertFalse(mul.matches(LedgerReason.SKILL_INVEST, -3));
        assertTrue(table.cue(Currency.PEARL).isEmpty());
    }

    @Test
    void reportsBadValues() {
        ValidationErrors errors = new ValidationErrors();
        parse(
                """
                events:
                  Fishing.Cast: {}
                  ui.click: {source: gui, pitch: 3, fallback: "not a key"}
                currency:
                  gem: {sound: "harbor:ui.coin"}
                  gold: {sound: "harbor:ui.coin", reasons: [NOT_A_REASON]}
                """,
                errors);
        // 이벤트 이름, 분류, 피치, 대체 소리, 재화, 사유
        assertEquals(6, errors.size(), errors.format());
    }

    @Test
    void emptyFileIsEmptyTable() {
        ValidationErrors errors = new ValidationErrors();
        SoundTable table = parse("{}", errors);
        assertTrue(errors.isEmpty());
        assertTrue(table.events().isEmpty());
        assertTrue(table.event("fishing.cast").isEmpty());
    }
}
