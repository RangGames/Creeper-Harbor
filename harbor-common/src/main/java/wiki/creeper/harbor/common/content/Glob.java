package wiki.creeper.harbor.common.content;

import java.util.regex.Pattern;

/** Glob for '/'-separated paths. Implemented by hand so it gives the same result on every OS. */
final class Glob {

    private Glob() {}

    /**
     * {@code *}: any string within one segment, {@code **}: multiple segments, {@code ?}: one
     * character.
     */
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
