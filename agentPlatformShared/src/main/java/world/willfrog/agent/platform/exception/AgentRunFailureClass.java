package world.willfrog.agent.platform.exception;

/**
 * Run 级失败四分类。与细分类（如 {@code LangchainFailureCategory}）分开：
 * 细分类进既有失败事件字段；本枚举是更粗的上报取值，失败事件写入放到后续批次。
 */
public enum AgentRunFailureClass {
    /** 规则或状态不允许这次继续。 */
    BUSINESS_REJECTION("business_rejection"),
    /** 额度、名额、条件更新等资源条件不满足。 */
    RESOURCE_SIGNAL("resource_signal"),
    /** 请上层改走挂起、取消或暂停。 */
    CONTROL_FLOW("control_flow"),
    /** 编程错误，或未列入上三类的意外。 */
    UNKNOWN_DEFECT("unknown_defect");

    private final String wireName;

    AgentRunFailureClass(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * 沿 cause 链是否碰到 {@link Error}。异步边界要用它把编程错误标成未知缺陷，
     * 同时仍把异步结果做完，避免 {@code CompletableFuture} 挂死。
     */
    public static boolean containsError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof Error) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
