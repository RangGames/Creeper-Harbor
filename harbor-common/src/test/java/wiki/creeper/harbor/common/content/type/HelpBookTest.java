package wiki.creeper.harbor.common.content.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

class HelpBookTest {

    private static HelpBook parse(String yaml, ValidationErrors errors) {
        YamlNode node = YamlNode.parse("help.yml", new StringReader(yaml), errors).orElseThrow();
        return HelpBook.parse(node, new ParseContext());
    }

    @Test
    void keepsOrderAndPicksClickMode() {
        ValidationErrors errors = new ValidationErrors();
        HelpBook book =
                parse(
                        """
                        sections:
                          - title: "<aqua>낚시"
                            commands:
                              - {command: "/낚시 설정", text: "설정"}
                              - {command: "기록 <어종>", text: "최대어"}
                              - {command: "파티 create", text: "만들기", run: false}
                          - title: "<gold>거래"
                            commands:
                              - {command: "판매", text: "팔기"}
                        """,
                        errors);
        assertTrue(errors.isEmpty(), errors.format());
        assertEquals(2, book.sections().size());
        assertEquals("<aqua>낚시", book.sections().getFirst().title());

        HelpBook.Entry settings = book.sections().getFirst().entries().get(0);
        assertEquals("낚시 설정", settings.command()); // 앞의 /는 떼고 저장
        assertEquals("낚시", settings.root());
        assertTrue(settings.run());
        assertEquals("/낚시 설정", settings.clickText());

        HelpBook.Entry records = book.sections().getFirst().entries().get(1);
        assertFalse(records.run()); // <인자>가 있으면 입력창에 넣기만
        assertEquals("/기록 ", records.clickText());

        HelpBook.Entry party = book.sections().getFirst().entries().get(2);
        assertFalse(party.run());
        assertEquals("/파티 create ", party.clickText());
    }

    @Test
    void reportsBadEntries() {
        ValidationErrors errors = new ValidationErrors();
        HelpBook book =
                parse(
                        """
                        sections:
                          - title: "빈 분류"
                            commands:
                              - {command: "", text: "이름 없음"}
                        """,
                        errors);
        assertEquals(2, errors.size()); // 빈 명령 + 빈 commands
        assertTrue(book.sections().isEmpty());
    }

    @Test
    void emptyFileIsEmptyBook() {
        ValidationErrors errors = new ValidationErrors();
        assertTrue(parse("{}", errors).sections().isEmpty());
        assertTrue(errors.isEmpty());
    }
}
