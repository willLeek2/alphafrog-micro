package world.willfrog.agent.tools.sandboxjob;

/** createTask 响应的裁决：创建被证实、权威不存在、还是结果不确定。 */
public enum SandboxCreateVerdict {

    /** 沙箱返回了任务身份，创建被证实。 */
    CONFIRMED,

    /** 按 operationId 回查确认任务不存在，可以安全重放创建。 */
    ABSENT,

    /** 响应丢失或证据矛盾，创建结果不确定；调用方必须先写取消墓碑再谈别的。 */
    UNKNOWN
}
