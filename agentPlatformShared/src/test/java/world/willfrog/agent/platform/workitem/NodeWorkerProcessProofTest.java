package world.willfrog.agent.platform.workitem;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NodeWorkerProcessProofTest {
    private static final String MACHINE = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_MACHINE = "fedcba9876543210fedcba9876543210";
    private static final String BOOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String NEXT_BOOT = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String NAMESPACE = "1xk4qga";
    private static final Instant STARTED = Instant.parse("2026-09-25T08:00:00Z");
    private static final String OLD = "dual-pool-node:v3@" + MACHINE + "@" + BOOT
            + "@1@" + STARTED.toEpochMilli() + "@" + NAMESPACE;
    private static final String OLD_CONTAINER = "dual-pool-node:v4@instance-7@abcdef012345@" + BOOT;








}
