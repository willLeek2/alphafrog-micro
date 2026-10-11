package world.willfrog.agent.platform.childrun;

/** 预留成功或同一父工具调用重放时返回同一组数据库身份。 */
public record ChildRunReservation(Outcome outcome, Long intentId, String childRunId,
                                  String operationId, Long outboxId) {
    public enum Outcome { CREATED, REPLAYED, LIMIT_EXCEEDED }

    public static ChildRunReservation limitExceeded() {
        return new ChildRunReservation(Outcome.LIMIT_EXCEEDED, null, null, null, null);
    }
}
