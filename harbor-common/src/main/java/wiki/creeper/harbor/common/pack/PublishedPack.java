package wiki.creeper.harbor.common.pack;

import java.util.List;
import wiki.creeper.harbor.common.redis.Channel;

/**
 * 팩을 만든 서버가 CDN에 올린 뒤 모든 서버에 알리는 현재 리소스팩 (추가 제안). Redis 키 {@value #KEY}에 JSON으로 두고, 바뀌면 {@link
 * #CHANNEL}로 알린다. lobby는 이 주소·SHA-1로 팩을 보내고, 모든 서버는 models·sounds 목록으로 커스텀 모델·소리를 쓸지 정한다.
 *
 * @param builtAt 만든 시각 (epoch ms)
 */
public record PublishedPack(
        String url,
        String sha1,
        long sizeBytes,
        List<String> models,
        List<String> sounds,
        long builtAt) {

    public static final String KEY = "harbor:pack";

    public static final Channel<PublishedPack> CHANNEL = new Channel<>(KEY, PublishedPack.class);

    public PublishedPack {
        models = List.copyOf(models);
        sounds = List.copyOf(sounds);
    }
}
