package wiki.creeper.harbor.common.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class YamlDefaultsTest {

    private static final String DEFAULTS =
            """
            # 서버
            server:
              id: lobby
              # 역할
              role: LOBBY
              port: 25565
            redis:
              host: localhost
            # 새 섹션
            pack:
              build: false
              list:
                - a
                - b
            note: |
              여러 줄
              key: 이건 키가 아님
            """;

    @Test
    void addsOnlyMissingKeysKeepingUserValuesAndComments() {
        String user =
                """
                # 내 설정
                server:
                  id: play-1   # 바꾼 값
                redis:
                  host: 10.0.0.2

                """;
        YamlDefaults.Result result = YamlDefaults.fill(user, DEFAULTS, k -> true);
        assertEquals(List.of("server.role", "server.port", "pack", "note"), result.added());
        String text = result.text();
        assertTrue(text.contains("  id: play-1   # 바꾼 값"));
        assertTrue(text.contains("  host: 10.0.0.2"));
        assertTrue(text.contains("  # 역할\n  role: LOBBY\n  port: 25565\nredis:"), text);
        assertTrue(text.contains("# 새 섹션\npack:\n  build: false\n  list:\n    - a"), text);
        assertTrue(text.contains("note: |\n  여러 줄\n  key: 이건 키가 아님"), text);
        // 다시 돌리면 더할 것이 없다
        assertFalse(YamlDefaults.fill(text, DEFAULTS, k -> true).changed());
    }

    @Test
    void respectsFilterAndIndentation() {
        String user = "server:\n    id: a\n";
        YamlDefaults.Result result =
                YamlDefaults.fill(user, DEFAULTS, k -> k.equals("server.port"));
        assertEquals(List.of("server.port"), result.added());
        assertEquals("server:\n    id: a\n    port: 25565\n", result.text());
    }

    @Test
    void keepsCrlfAndSkipsListFiles() {
        String user = "server:\r\n  id: a\r\n";
        assertTrue(
                YamlDefaults.fill(user, DEFAULTS, k -> true)
                        .text()
                        .contains("\r\n  role: LOBBY\r\n"));
        String list = "- {id: fish/a}\n";
        assertFalse(
                YamlDefaults.fill(list, "- {id: fish/a}\n- {id: fish/b}\n", k -> true).changed());
    }

    @Test
    void listsKeyPaths() {
        Set<String> keys = YamlDefaults.keys(DEFAULTS);
        assertTrue(
                keys.containsAll(
                        List.of("server", "server.id", "pack.build", "pack.list", "note")));
        assertFalse(keys.stream().anyMatch(k -> k.contains("이건")));
    }
}
