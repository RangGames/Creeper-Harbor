package wiki.creeper.harbor.common.content;

import java.util.regex.Pattern;

/** '/' 구분 경로용 glob. 운영체제와 상관없이 같은 결과를 내도록 직접 구현한다. */
final class Glob {

    private Glob() {}

    /** {@code *}: 한 마디 안의 아무 문자열, {@code **}: 여러 마디, {@code ?}: 한 문자. */
    static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
