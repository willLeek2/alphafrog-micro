package world.willfrog.agentlangchain.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import world.willfrog.agent.platform.mapper.AgentRunMapper;
import world.willfrog.agent.platform.mapper.WaitGroupMapper;

/** 沙箱确认删盘后，在一个数据库事务中落过期事实并清掉已结清调用的请求正文。 */
@Service
@RequiredArgsConstructor
public class PythonWorkspaceExpirationService {
    private final AgentRunMapper runs;
    private final WaitGroupMapper waitGroups;

    @Transactional
    public void confirmDeleted(String runId) {
        // SQL 再次核对终态和所有异步责任；竞争者若先改动，事务不能写入过期事实。
        if (runs.markWorkspaceExpired(runId) != 1) {
            throw new IllegalStateException("沙箱已删除工作区，但 Run 过期事实尚未写入: " + runId);
        }
        // 成员证明中的 operation/task/fingerprint/容量审计保留；只删除不能再重放的请求正文。
        waitGroups.compactExpiredWaitMemberProofs(runId);
    }
}
