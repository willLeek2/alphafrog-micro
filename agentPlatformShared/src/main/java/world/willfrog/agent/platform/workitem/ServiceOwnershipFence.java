package world.willfrog.agent.platform.workitem;

/**
 * 一条 Run 的服务所有权凭据：谁在服务它、以及第几次服务。
 *
 * <p>一条 Run 同一时刻只由一个进程服务，这件事在数据库里的凭据就是 {@code alphafrog_agent_run_service_lease}
 * 上那一行：持有人与代际号。代际号在持有人换人时加一，所以「同一个进程后来又拿回这条 Run」也会拿到
 * 新的号——写操作只认号，不认人，否则旧的生命周期在重新取得之后就又能写了。</p>
 *
 * <p>把这两个值当成一条语句的条件（{@code owner = ? AND fencing_token = ? AND expires_at > now()}），
 * 「先查再写」中间那个时间窗就不存在了：租约只要在两次操作之间到期并被别人接管，这条写就会影响 0 行。</p>
 *
 * @param ownerInstanceId 本进程的实例标识
 * @param fencingToken    取得这条 Run 的服务所有权时拿到的代际号
 */
public record ServiceOwnershipFence(String ownerInstanceId, long fencingToken) {

    public ServiceOwnershipFence {
        if (ownerInstanceId == null || ownerInstanceId.isBlank()) {
            throw new IllegalArgumentException("owner_instance_id_required");
        }
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencing_token_must_be_positive");
        }
    }

    public String describe() {
        return "owner=" + ownerInstanceId + " token=" + fencingToken;
    }
}
