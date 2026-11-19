package wiki.creeper.harbor.common.content.type;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * help.yml: 플레이어 /도움말 목록 (추가 제안). Core가 이 순서대로 보여 주고, 서버에 없는 명령(꺼진 모듈)이나 쓸 권한이 없는 명령은 감춘다. 파일이 없으면
 * 빈 목록.
 *
 * @param sections 분류 (적은 순서대로)
 */
public record HelpBook(List<Section> sections) {

    public static final ContentType.Single<HelpBook> TYPE =
            new ContentType.Single<>("help", "help.yml", false, HelpBook::parse);

    /** 명령 줄 (앞의 / 없이, 공백으로 나눈 단어). 예: {@code 낚시 설정}, {@code 기록 <어종>}. */
    private static final Pattern COMMAND = Pattern.compile("[^\\s/][^\\s]*( [^\\s]+)*");

    public static final HelpBook EMPTY = new HelpBook(List.of());

    public HelpBook {
        sections = List.copyOf(sections);
    }

    /**
     * 분류 하나.
     *
     * @param title 제목 (MiniMessage)
     * @param entries 명령 목록
     */
    public record Section(String title, List<Entry> entries) {

        public Section {
            entries = List.copyOf(entries);
        }
    }

    /**
     * 명령 하나.
     *
     * @param command 클릭 때 넣을 명령 (앞의 / 없이). 첫 단어가 서버에 등록된 명령이어야 보인다
     * @param text 설명 (MiniMessage)
     * @param run true면 클릭 즉시 실행, false면 입력창에 넣기만 한다 (인자가 필요한 명령). 비우면 &lt;가 들어 있을 때 false
     */
    public record Entry(String command, String text, boolean run) {

        /** 서버 명령 이름 (첫 단어). */
        public String root() {
            int space = command.indexOf(' ');
            return space < 0 ? command : command.substring(0, space);
        }

        /** 클릭 때 넣을 명령 문자열. 입력용이면 &lt;인자&gt; 앞까지 자르고 공백을 붙인다. */
        public String clickText() {
            if (run) {
                return "/" + command;
            }
            int arg = command.indexOf(" <");
            return "/" + (arg < 0 ? command : command.substring(0, arg)) + " ";
        }
    }

    static HelpBook parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("sections"));
        List<Section> sections = new ArrayList<>();
        for (YamlNode s : node.list("sections")) {
            s.allowOnly(Set.of("title", "commands"));
            String title = s.string("title", "");
            List<Entry> entries = new ArrayList<>();
            for (YamlNode e : s.list("commands")) {
                e.allowOnly(Set.of("command", "text", "run"));
                String command = e.string("command", "").strip();
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                if (!COMMAND.matcher(command).matches()) {
                    e.error("command는 앞의 / 없이 쓴 명령이어야 합니다: '" + command + "'");
                    continue;
                }
                boolean run = e.bool("run", !command.contains("<"));
                entries.add(new Entry(command.toLowerCase(Locale.ROOT), e.string("text", ""), run));
            }
            if (entries.isEmpty()) {
                s.error("commands가 비어 있습니다");
                continue;
            }
            sections.add(new Section(title, entries));
        }
        return new HelpBook(sections);
    }
}
