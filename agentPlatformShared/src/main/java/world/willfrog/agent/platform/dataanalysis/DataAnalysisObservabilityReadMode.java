package world.willfrog.agent.platform.dataanalysis;

/** Data-analysis observability 的生命周期读取策略。 */
public enum DataAnalysisObservabilityReadMode {
    /** 运行中优先 Redis，miss 或坏缓存时回退 PostgreSQL。 */
    RUNNING_CACHE_FIRST,
    /** 终态以 PostgreSQL snapshot 为准，禁止合法但陈旧的 Redis 覆盖 DB。 */
    TERMINAL_DB_ONLY
}
