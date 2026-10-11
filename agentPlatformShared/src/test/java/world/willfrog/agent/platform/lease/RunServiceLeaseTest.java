package world.willfrog.agent.platform.lease;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunServiceLeaseTest {

    @Test
    void boxedFencingTokenMatchesMyBatisConstructorLookup() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-22T16:03:37.357334Z");
        Constructor<RunServiceLease> constructor = RunServiceLease.class.getConstructor(
                String.class, String.class, Long.class,
                OffsetDateTime.class, OffsetDateTime.class, OffsetDateTime.class);
        RunServiceLease lease = constructor.newInstance(
                "e9d123ec44c246549a23d986400a5eeb",
                "agentLangchainService@b6b1474f58e0@1@3999a794",
                Long.valueOf(1L),
                now, now, now);
        assertEquals(1L, lease.fencingToken());
        assertEquals("e9d123ec44c246549a23d986400a5eeb", lease.runId());
    }
}
