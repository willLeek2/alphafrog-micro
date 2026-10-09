package world.willfrog.agent.platform.dataanalysis;

/** 只记录当前SQL线程曾延迟中断，供原worker可靠写退出回执；不保存业务状态。 */
public final class MemberPreparingInterruption {
    private static final ThreadLocal<Boolean> DEFERRED = new ThreadLocal<>();
    private MemberPreparingInterruption() {}
    public static void mark() { DEFERRED.set(true); }
    public static boolean consume() {
        boolean interrupted = Boolean.TRUE.equals(DEFERRED.get());
        DEFERRED.remove();
        return interrupted;
    }
}
