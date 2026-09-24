package world.willfrog.agentlangchain.control.dualpool;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** 有界提示队列：先轮转根调用树，再轮转同一树内的 Run。 */
final class TreeFairHintQueue<T> {

    private final int capacity;
    private final Map<String, RootBucket<T>> roots = new LinkedHashMap<>();
    private final ArrayDeque<String> rootRotation = new ArrayDeque<>();
    private int size;

    TreeFairHintQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    synchronized boolean offer(String rootRunId, String runId, T hint) {
        if (rootRunId == null || rootRunId.isBlank() || runId == null || runId.isBlank()
                || hint == null || size >= capacity) {
            return false;
        }
        RootBucket<T> root = roots.get(rootRunId);
        if (root == null) {
            root = new RootBucket<>();
            roots.put(rootRunId, root);
            rootRotation.addLast(rootRunId);
        }
        root.offer(runId, hint);
        size++;
        return true;
    }

    synchronized T poll() {
        String rootId = rootRotation.pollFirst();
        if (rootId == null) {
            return null;
        }
        RootBucket<T> root = roots.get(rootId);
        T hint = root.poll();
        size--;
        if (root.empty()) {
            roots.remove(rootId);
        } else {
            rootRotation.addLast(rootId);
        }
        return hint;
    }

    synchronized int size() {
        return size;
    }

    synchronized int remainingCapacity() {
        return capacity - size;
    }

    synchronized int runBucketCount() {
        return roots.values().stream().mapToInt(RootBucket::runCount).sum();
    }

    private static final class RootBucket<T> {
        private final Map<String, ArrayDeque<T>> runs = new LinkedHashMap<>();
        private final ArrayDeque<String> runRotation = new ArrayDeque<>();

        private void offer(String runId, T hint) {
            ArrayDeque<T> queue = runs.get(runId);
            if (queue == null) {
                queue = new ArrayDeque<>();
                runs.put(runId, queue);
                runRotation.addLast(runId);
            }
            queue.addLast(hint);
        }

        private T poll() {
            String runId = runRotation.removeFirst();
            ArrayDeque<T> queue = runs.get(runId);
            T hint = queue.removeFirst();
            if (queue.isEmpty()) {
                runs.remove(runId);
            } else {
                runRotation.addLast(runId);
            }
            return hint;
        }

        private boolean empty() {
            return runs.isEmpty();
        }

        private int runCount() {
            return runs.size();
        }
    }
}
