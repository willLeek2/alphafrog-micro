package world.willfrog.agent.platform.credit;

/**
 * Stable cost attribution for per-LLM-call credit settlement.
 */
public enum CostSource {
    OPENROUTER_ACTUAL,
    PER_CALL,
    OPENROUTER_FALLBACK
}
