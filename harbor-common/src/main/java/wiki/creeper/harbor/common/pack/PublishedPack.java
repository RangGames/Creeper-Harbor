package wiki.creeper.harbor.common.pack;

import java.util.List;
import wiki.creeper.harbor.common.redis.Channel;

/**
 * 팩을 만든 서버가 CDN에 올린 뒤 모든 서버에 알리는 현재 리소스팩 (추가 제안).
 *
 * <p>Redis 키 {@value #KEY}에 JSON으로, {@value #SHA1_KEY}에 SHA-1만 두고, 바뀌면 {@link #CHANNEL}로 SHA-1만 알린다
 * (모델 목록이 커서 메시지에 싣지 않는다). 받은 서버는 키를 읽는다. lobby는 이 주소·SHA-1로 팩을 보내고, 모든 서버는 models·sounds 목록으로 커스텀
 * 모델·소리를 쓸지 정한다.
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

    /** 지금 팩의 SHA-1만 (서버들이 주기적으로 싸게 비교한다). */
    public static final String SHA1_KEY = "harbor:pack:sha1";

    /** 팩이 바뀌었다는 알림. */
    public record Changed(String sha1) {}

    public static final Channel<Changed> CHANNEL = new Channel<>(KEY, Changed.class);

    public PublishedPack {
        models = List.copyOf(models);
        sounds = List.copyOf(sounds);
    }
}
