package world.willfrog.agentlangchain.tooljob;

/**
 * 恢复任务在 launcher 进程内的去重键，与数据库 LAUNCHING 租约一一对应。
 * 包内可见：launcher 用它做 putIfAbsent 去重，heartbeat 用它做窄续租。
 */
record ToolJobResumeClaimKey(String runId, String token, long version, String ownerId) {
}
