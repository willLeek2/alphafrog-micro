package world.willfrog.agentlangchain.orchestration;

/** 第一次工具失败后交给 LLM 的一次性修正上下文。 */
record TodoRetryContext(String toolName,
                        String previousArguments,
                        String failureCategory,
                        String failureSummary,
                        ToolRetrySafety safety) {
}
