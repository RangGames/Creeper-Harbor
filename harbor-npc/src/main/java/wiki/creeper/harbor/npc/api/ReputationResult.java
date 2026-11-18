package wiki.creeper.harbor.npc.api;

/**
 * 평판 변경 결과.
 *
 * @param applied 실제로 더한 값 (보정자·하루 상한 적용 후)
 * @param value 변경 후 평판
 * @param tier 변경 후 등급
 */
public record ReputationResult(Status status, int applied, int value, int tier) {

    public enum Status {
        APPLIED,
        /** 하루 상한에 걸림 (applied는 상한까지 더한 값, 0일 수 있음). */
        CAPPED,
        /** 같은 refId가 이번 시즌에 이미 들어옴. */
        DUPLICATE,
        /** 이 서버에 접속 중이 아님. */
        OFFLINE,
        /** delta가 0이거나 처리 중 오류. */
        IGNORED
    }

    public boolean ok() {
        return status == Status.APPLIED || (status == Status.CAPPED && applied != 0);
    }
}
