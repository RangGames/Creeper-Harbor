package wiki.creeper.harbor.common.id;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 네임스페이스:경로 형식의 ID. 예: {@code harbor:fish/harbor_mackerel}.
 *
 * <p>소문자 영문·숫자·밑줄만 쓴다. 네임스페이스를 생략하면 {@code harbor}로 본다. ID는 한 번 정하면 바꾸지 않는다.
 */
public record HarborId(String namespace, String path) implements Comparable<HarborId> {

    public static final String DEFAULT_NAMESPACE = "harbor";

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    public HarborId {
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("잘못된 네임스페이스: " + namespace);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("잘못된 ID 경로: " + path);
        }
    }

    /** {@code harbor:fish/x} 또는 {@code fish/x}를 읽는다. 형식이 틀리면 IllegalArgumentException. */
    public static HarborId parse(String raw) {
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return new HarborId(DEFAULT_NAMESPACE, raw);
        }
        return new HarborId(raw.substring(0, colon), raw.substring(colon + 1));
    }

    public static Optional<HarborId> tryParse(String raw) {
        try {
            return Optional.of(parse(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 경로의 첫 마디. {@code fish/harbor_mackerel} → {@code fish}. */
    public String category() {
        int slash = path.indexOf('/');
        return slash < 0 ? path : path.substring(0, slash);
    }

    /** 경로의 마지막 마디. {@code item/rod/coral_carbon} → {@code coral_carbon}. */
    public String name() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public boolean hasPrefix(String prefix) {
        return path.startsWith(prefix);
    }

    @Override
    public int compareTo(HarborId other) {
        int c = namespace.compareTo(other.namespace);
        return c != 0 ? c : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
