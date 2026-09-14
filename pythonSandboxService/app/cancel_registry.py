"""Cancel-handle registry for D11 (task #108) RUNNING-task cancellation.

260809-26Q3-stage1-w2 D11: the cancel endpoint (POST /tasks/cancel) only
records the cancel intent inside the store lock; the ACTUAL stop signal for
a RUNNING task flows through this registry, entirely outside the store lock:

  endpoint (store lock: cancel_requested=True, outcome CANCEL_INTENT_RECORDED)
      -> registry.request_stop(task_id)          [outside any store lock]
          -> CancelHandle.request_stop()
              -> pool Future.cancel()            [task not yet picked up]
              -> marker writer dispatch          [task already executing]

The handle exists purely as a rendezvous point.  A task's execution path
registers a handle before it does anything else and unregisters it in a
finally block; the pool path attaches the pool Future between constructing
it and enqueueing the job (so a stop that arrives in that gap still cancels
the future); the sandbox execution path attaches a marker writer as soon as
it owns a live session.  Whatever arrives second — the stop request or the
attachable object — always wins: `set_future` / `set_marker_writer` check
the stop flag and act immediately when the stop came first.

d6841a2e rule 4 boundary (kill_issued alone is NOT valid forced-CANCELED
evidence): everything in this module only DELIVERS stop requests.  Whether a
task ends up CANCELED is decided exclusively by what the execution layer
OBSERVED (pool Future canceled before start -> CANCELED_BEFORE_START;
wrapper observed the cancel marker and killed its child -> MARKER_OBSERVED),
recorded by task_store.complete_execution from the CompletionCandidate the
worker computes.  A request_stop() that nobody observes produces no CANCELED.

Marker writing is deliberately NOT done inline in the endpoint: the marker
file lives inside the sandbox container (the task control dir under
/sandbox, owned by the same unprivileged user the whole container runs as —
260818 non-root simplification), so writing it means one container exec
command.  That can take seconds and must
never block the HTTP endpoint, so it runs on a small dedicated thread pool.
A failed marker write is logged and swallowed on purpose: per rule 3, if the
child finishes normally before the marker could be written, the task keeps
its genuine result — a failed cancel attempt is honest silence, never a
forced CANCELED.
"""

from __future__ import annotations

import logging
import threading
from concurrent.futures import Future, ThreadPoolExecutor
from typing import Callable, Dict, Optional

logger = logging.getLogger("app.cancel_registry")


class CancelHandle:
    """Per-task rendezvous between a stop request and the stoppable objects.

    All state transitions happen under the handle's own lock, but the two
    side effects (Future.cancel() and marker-writer dispatch) run OUTSIDE
    that lock so the handle never holds a lock while calling into the pool
    or the write pool.
    """

    def __init__(self, task_id: str) -> None:
        self._task_id = task_id
        self._lock = threading.Lock()
        self._stop_requested = False
        self._future: Optional[Future] = None
        self._marker_writer: Optional[Callable[[], None]] = None
        # D11 (task #108, codex c6c49248 review): the marker dispatch state
        # is NOT a permanent one-shot fuse.  "idle" → "dispatching" on each
        # stop request that starts a write; the completion callback resets it
        # to "idle" so a later replay can re-dispatch.  Concurrent replays
        # while a dispatch is in-flight are coalesced (skip submit).
        self._marker_dispatch_idle = True

    def stop_requested(self) -> bool:
        with self._lock:
            return self._stop_requested

    def set_future(self, future: Future) -> None:
        """Attach the pool Future; cancel it immediately if stop came first."""
        cancel_now = False
        with self._lock:
            self._future = future
            if self._stop_requested:
                cancel_now = True
        if cancel_now:
            future.cancel()
            logger.info(
                "CANCEL_HANDLE_FUTURE_CANCELLED_ON_ATTACH task_id=%s", self._task_id
            )

    def set_marker_writer(self, writer: Callable[[], None]) -> None:
        """Attach the marker writer; dispatch immediately if stop came first."""
        dispatch_now = False
        with self._lock:
            self._marker_writer = writer
            if self._stop_requested and self._marker_dispatch_idle:
                self._marker_dispatch_idle = False
                dispatch_now = True
        if dispatch_now:
            self._submit_marker_write()
            logger.info(
                "CANCEL_HANDLE_MARKER_DISPATCHED_ON_ATTACH task_id=%s", self._task_id
            )

    def request_stop(self) -> bool:
        """Record the stop request; act on whatever is already attached.

        Returns True iff THIS call newly recorded the stop (the first one);
        later calls return False.
        """
        future_to_cancel: Optional[Future] = None
        dispatch_now = False
        with self._lock:
            if self._stop_requested:
                newly_requested = False
            else:
                self._stop_requested = True
                newly_requested = True
            # Future.cancel is idempotent — always re-attempt.
            if self._future is not None and not self._future.done():
                future_to_cancel = self._future
            # Marker: coalesce an in-flight write (skip), but re-dispatch
            # once the previous write finished (success or failure) so a
            # replay or a different cancelRequestId can push again.
            if self._marker_writer is not None and self._marker_dispatch_idle:
                self._marker_dispatch_idle = False
                dispatch_now = True
        # Side effects OUTSIDE the handle lock.
        if future_to_cancel is not None:
            future_to_cancel.cancel()
            logger.info("CANCEL_HANDLE_FUTURE_CANCELED task_id=%s", self._task_id)
        if dispatch_now:
            self._submit_marker_write()
        return newly_requested

    # --- internal --------------------------------------------------------

    def _submit_marker_write(self) -> None:
        writer = self._marker_writer
        task_id = self._task_id

        def _run() -> None:
            try:
                writer()
            except Exception:
                logger.exception("CANCEL_MARKER_WRITE_FAILED task_id=%s", task_id)
            finally:
                with self._lock:
                    self._marker_dispatch_idle = True

        try:
            _get_write_pool().submit(_run)
        except RuntimeError:
            logger.warning(
                "CANCEL_MARKER_WRITE_SKIPPED_POOL_SHUTDOWN task_id=%s", task_id
            )
            with self._lock:
                self._marker_dispatch_idle = True


# --- Marker write pool -------------------------------------------------------
# Deliberately LAZY and small: marker writes only happen on cancel of a
# RUNNING task (rare), so the pool must not exist at import time (tests
# import the app without any execution happening), and it must stay bounded
# so a storm of cancel requests can never spawn unbounded threads.
_MARKER_POOL_MAX_WORKERS = 4
_marker_pool: Optional[ThreadPoolExecutor] = None
_marker_pool_lock = threading.Lock()


def _get_write_pool() -> ThreadPoolExecutor:
    global _marker_pool
    with _marker_pool_lock:
        if _marker_pool is None:
            _marker_pool = ThreadPoolExecutor(
                max_workers=_MARKER_POOL_MAX_WORKERS,
                thread_name_prefix="af-cancel-marker",
            )
        return _marker_pool


def dispatch_marker_write(writer: Callable[[], None], task_id: str) -> None:
    """Run the marker writer on the dedicated pool, never inline.

    The writer itself is best-effort (it swallows its own errors); this
    wrapper guards the submission too: if the pool is shut down (service
    shutdown race) the marker is simply not written — the task then keeps
    whatever genuine result the child produces (d6841a2e rule 3).
    """
    def _run() -> None:
        try:
            writer()
        except Exception:
            logger.exception("CANCEL_MARKER_WRITE_FAILED task_id=%s", task_id)

    try:
        _get_write_pool().submit(_run)
    except RuntimeError:
        # ThreadPoolExecutor.submit raises RuntimeError after shutdown.
        logger.warning(
            "CANCEL_MARKER_WRITE_SKIPPED_POOL_SHUTDOWN task_id=%s", task_id
        )


def shutdown_marker_write_pool() -> None:
    """Service-shutdown hook: stop accepting marker writes.

    wait=False on purpose: marker writes are best-effort by contract, and a
    slow container exec during shutdown must never hang the lifespan exit.
    """
    global _marker_pool
    with _marker_pool_lock:
        if _marker_pool is not None:
            _marker_pool.shutdown(wait=False)
            _marker_pool = None


# --- Registry ----------------------------------------------------------------


class _CancelRegistry:
    """task_id -> CancelHandle map with register/unregister lifecycle.

    register() returns the EXISTING handle when one is already present: a
    cancel request that arrived before the execution path registered must
    still find the same handle it acted on (the endpoint creates none — it
    only requests stops on existing handles — so in practice the execution
    path always creates; but returning the existing handle keeps the map
    total under any interleaving).
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._handles: Dict[str, CancelHandle] = {}

    def register(self, task_id: str) -> CancelHandle:
        with self._lock:
            handle = self._handles.get(task_id)
            if handle is None:
                handle = CancelHandle(task_id)
                self._handles[task_id] = handle
            return handle

    def unregister(self, task_id: str) -> None:
        with self._lock:
            self._handles.pop(task_id, None)

    def get(self, task_id: str) -> Optional[CancelHandle]:
        with self._lock:
            return self._handles.get(task_id)

    def attach_future(self, task_id: str, future: Future) -> None:
        handle = self.get(task_id)
        if handle is not None:
            handle.set_future(future)

    def request_stop(self, task_id: str) -> bool:
        """Returns True iff a handle existed AND this call newly recorded
        the stop.  False means either unknown task (already unregistered or
        never started) or a repeated stop request."""
        handle = self.get(task_id)
        if handle is None:
            return False
        return handle.request_stop()


registry = _CancelRegistry()
