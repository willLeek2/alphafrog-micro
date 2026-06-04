package world.willfrog.agent.platform.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe sliding window of recent LLM call durations for p90 estimation.
 *
 * <p>Used by the adaptive concurrency controller in agent-langchain-service.
 * Because LLM calls happen inside agentPlatformShared, the window lives here
 * and gets fed from {@code OpenRouterProviderRoutedChatModel} /
 * {@code DashScopeChatModel}.</p>
 */
@Component
@Slf4j
public class LangchainLlmLatencyWindow {

    private final ConcurrentLinkedDeque<Entry> deque = new ConcurrentLinkedDeque<>();
    private volatile int maxSize = 200;
    private volatile long windowTtlMs = 300_000; // 5 min

    /** Record a single LLM call duration in milliseconds. */
    public void record(long durationMs) {
        if (durationMs <= 0) return;
        deque.addLast(new Entry(System.currentTimeMillis(), durationMs));
        while (deque.size() > maxSize) {
            deque.pollFirst();
        }
        expireStale();
    }

    /** Estimated p90 latency over the current window, or 0 if insufficient data. */
    public long p90() {
        expireStale();
        List<Long> values = snapshotValues();
        if (values.size() < 3) return 0;
        Collections.sort(values);
        int idx = (int) Math.ceil(values.size() * 0.9) - 1;
        return values.get(Math.min(Math.max(idx, 0), values.size() - 1));
    }

    /** Arithmetic mean over the current window. */
    public long avg() {
        expireStale();
        List<Long> values = snapshotValues();
        if (values.isEmpty()) return 0;
        long sum = 0;
        for (long v : values) sum += v;
        return sum / values.size();
    }

    /** Current window entry count (for diagnostics). */
    public int size() {
        expireStale();
        return deque.size();
    }

    public void clear() {
        deque.clear();
    }

    private void expireStale() {
        long cutoff = System.currentTimeMillis() - windowTtlMs;
        while (!deque.isEmpty()) {
            Entry head = deque.peekFirst();
            if (head != null && head.atMillis < cutoff) {
                deque.pollFirst();
            } else {
                break;
            }
        }
    }

    private List<Long> snapshotValues() {
        List<Long> values = new ArrayList<>(deque.size());
        for (Entry e : deque) values.add(e.durationMs);
        return values;
    }

    private record Entry(long atMillis, long durationMs) {}
}
