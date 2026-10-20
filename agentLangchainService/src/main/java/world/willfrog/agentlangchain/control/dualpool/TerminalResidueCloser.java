package world.willfrog.agentlangchain.control.dualpool;

/**
 * 运行已经是终态时，取消还没做完的分段，并放开本进程占用的名额。
 *
 * <p>读路径和协调路径都能把运行写成终态。分段取消只走这里，不在每个写手里各写一段。</p>
 */
public interface TerminalResidueCloser {

    /**
     * @return 未完成分段已经清掉。运行不是终态、不归本进程、或服务所有权不在本进程时返回 false。
     */
    boolean closeIfTerminal(String runId);
}
