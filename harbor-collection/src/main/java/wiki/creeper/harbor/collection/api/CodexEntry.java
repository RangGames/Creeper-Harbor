package wiki.creeper.harbor.collection.api;

import java.time.Instant;

/** 도감 한 칸: 첫 포획 시각, 최대 크기, 최고 품질, 포획 수. */
public record CodexEntry(Instant firstCaught, double maxSize, int bestQuality, int count) {

    /** 새 포획을 반영한 값 (큰 값 유지, 첫 포획 시각은 그대로). */
    public CodexEntry merge(double size, int quality) {
        return new CodexEntry(
                firstCaught, Math.max(maxSize, size), Math.max(bestQuality, quality), count + 1);
    }

    public static CodexEntry first(Instant at, double size, int quality) {
        return new CodexEntry(at, size, quality, 1);
    }
}
