package wiki.creeper.harbor.common.content;

import java.util.List;
import java.util.function.Function;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * 콘텐츠 형식. 각 모듈이 자기 형식을 정의해서 Core의 로더에 등록한다 (예: Fishing의 fish, Quest의 quest).
 *
 * <ul>
 *   <li>{@link Entries}: 파일 안에 ID가 있는 항목 목록이 들어 있는 형식 (fish/*.yml, items/*.yml, regions.yml)
 *   <li>{@link Single}: 파일 하나가 통째로 값 하나인 형식 (balance.yml, messages_ko.yml)
 * </ul>
 */
public sealed interface ContentType<T> permits ContentType.Entries, ContentType.Single {

    /** 형식 이름. 참조 검사에서 대상 형식을 가리킬 때도 쓴다. 예: {@code fish}. */
    String name();

    /** 콘텐츠 폴더 기준 파일 패턴 (glob, '/' 구분). 예: {@code fish/*.yml}. */
    List<String> files();

    /** 항목 하나를 레코드로 바꾼다. 오류는 노드에 기록하고, 참조는 ctx에 등록한다. */
    @FunctionalInterface
    interface Parser<T> {
        T parse(YamlNode node, ParseContext ctx);
    }

    /**
     * ID가 있는 항목들의 형식. 파일 최상위는 항목 목록이고, 항목마다 {@code id}가 있어야 한다.
     *
     * @param idPrefix ID 경로 접두사 (예: {@code fish/}). 다른 접두사의 ID는 오류.
     * @param idOf 레코드에서 ID를 꺼내는 함수
     */
    record Entries<T>(
            String name,
            List<String> files,
            String idPrefix,
            Parser<T> parser,
            Function<T, HarborId> idOf)
            implements ContentType<T> {

        public Entries {
            files = List.copyOf(files);
        }
    }

    /**
     * 파일 하나짜리 형식.
     *
     * @param required 파일이 없을 때 오류로 볼지. 아니면 빈 맵으로 파싱한다.
     */
    record Single<T>(String name, String file, boolean required, Parser<T> parser)
            implements ContentType<T> {

        @Override
        public List<String> files() {
            return List.of(file);
        }
    }
}
