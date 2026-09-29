package world.willfrog.agent.tools.sandboxjob;

/** createTask 响应的裁决：创建被证实、权威不存在、还是结果不确定。 */
public sealed interface SandboxCreateVerdict {

    /** 沙箱返回的任务身份与请求指纹对上了，创建被证实。 */
    record Confirmed(String taskId) implements SandboxCreateVerdict {
    }

    /** 按 operationId 回查确认任务不存在，可以安全重放创建。 */
    record Absent(String detail) implements SandboxCreateVerdict {
    }

    /** 响应丢失或证据矛盾，创建结果不确定；调用方必须先写取消墓碑再谈别的。 */
    record Unknown(String detail) implements SandboxCreateVerdict {
    }
}
