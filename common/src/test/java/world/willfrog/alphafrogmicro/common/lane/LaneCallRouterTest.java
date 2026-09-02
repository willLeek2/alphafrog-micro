package world.willfrog.alphafrogmicro.common.lane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaneCallRouterTest {

    private static final String SCOPE = "main-beta";
    private static final String SERVICE = "agent-service";
    private static final String OLD_GEN = "gen-" + "a".repeat(64);
    private static final String NEW_GEN = "gen-" + "b".repeat(64);

    private final AtomicLaneRoutePointer pointer = new AtomicLaneRoutePointer();
    private final LaneCallRouter router = new LaneCallRouter(pointer);

    @AfterEach
    void clearThreadState() {
        LaneContext.clear();
        LaneRoutingSupport.reset();
    }

    @Test
    void bindNewCall_shouldReadPointerOnceAndKeepInFlightBindingAfterSwitch() {
        pointer.replaceAll(LaneRouteTable.of(List.of(oldRoute())));

        LaneCallBinding boundBeforeSwitch = router.bindNewCall(SCOPE, SERVICE);
        pointer.replaceAll(LaneRouteTable.of(List.of(newRoute())));
        LaneCallBinding firstAfterSwitch = router.bindNewCall(SCOPE, SERVICE);
        LaneCallBinding secondAfterSwitch = router.bindNewCall(SCOPE, SERVICE);

        assertThat(boundBeforeSwitch.instanceId()).isEqualTo("instance-old");
        assertThat(boundBeforeSwitch.routeVersion()).isEqualTo(7L);
        assertThat(boundBeforeSwitch.endpoint()).isEqualTo(new LaneEndpoint("10.0.0.8", 28080));
        assertThat(firstAfterSwitch.instanceId()).isEqualTo("instance-new");
        assertThat(secondAfterSwitch.instanceId()).isEqualTo("instance-new");
        assertThat(firstAfterSwitch.deploymentGenerationId()).isEqualTo(NEW_GEN);
        assertThat(router.readCount()).isEqualTo(3);
        assertThat(boundBeforeSwitch.instanceId()).isEqualTo("instance-old");
        assertThat(boundBeforeSwitch.routeVersion()).isEqualTo(7L);
    }

    @Test
    void bindNewCall_shouldFailClosedAfterDefaultRouteRemoved() {
        pointer.replaceAll(LaneRouteTable.of(List.of(oldRoute())));
        router.bindNewCall(SCOPE, SERVICE);
        pointer.replaceAll(LaneRouteTable.empty());

        assertThatThrownBy(() -> router.bindNewCall(SCOPE, SERVICE))
                .isInstanceOf(LaneRouteUnavailableException.class)
                .hasMessage(LaneRouteUnavailableException.CODE);
    }

    @Test
    void bindNewCall_shouldNotUsePreviousTableWhenPointerCannotBeRead() {
        pointer.replaceAll(LaneRouteTable.of(List.of(oldRoute())));
        LaneCallRouter failing = new LaneCallRouter(() -> {
            throw new IllegalStateException("disk missing");
        });

        assertThatThrownBy(() -> failing.bindNewCall(SCOPE, SERVICE))
                .isInstanceOf(LaneRouteUnavailableException.class)
                .hasMessage(LaneRouteUnavailableException.CODE);
    }

    @Test
    void concurrentReaders_shouldNotMixFieldsFromTwoSnapshots() throws Exception {
        pointer.replaceAll(LaneRouteTable.of(List.of(oldRoute())));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CopyOnWriteArrayList<LaneCallBinding> seen = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < 40; n++) {
                        seen.add(router.bindNewCall(SCOPE, SERVICE));
                    }
                    return null;
                });
            }
            start.countDown();
            for (int n = 0; n < 20; n++) {
                pointer.replaceAll(LaneRouteTable.of(List.of(n % 2 == 0 ? oldRoute() : newRoute())));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(seen).isNotEmpty();
        for (LaneCallBinding binding : seen) {
            if ("instance-old".equals(binding.instanceId())) {
                assertThat(binding.releaseId()).isEqualTo("release-1");
                assertThat(binding.deploymentGenerationId()).isEqualTo(OLD_GEN);
                assertThat(binding.endpoint().port()).isEqualTo(28080);
                assertThat(binding.routeVersion()).isEqualTo(7L);
            } else {
                assertThat(binding.instanceId()).isEqualTo("instance-new");
                assertThat(binding.releaseId()).isEqualTo("release-2");
                assertThat(binding.deploymentGenerationId()).isEqualTo(NEW_GEN);
                assertThat(binding.endpoint().port()).isEqualTo(28081);
                assertThat(binding.routeVersion()).isEqualTo(8L);
            }
        }
    }

    private static LaneServiceRoute oldRoute() {
        return new LaneServiceRoute(
                SCOPE,
                SERVICE,
                "com.alphafrog.AgentService:1.0@@providers",
                "instance-old",
                "release-1",
                OLD_GEN,
                7L,
                "2026-09-01T00:00:00Z",
                new LaneEndpoint("10.0.0.8", 28080));
    }

    private static LaneServiceRoute newRoute() {
        return new LaneServiceRoute(
                SCOPE,
                SERVICE,
                "com.alphafrog.AgentService:1.0@@providers",
                "instance-new",
                "release-2",
                NEW_GEN,
                8L,
                "2026-09-01T00:02:00Z",
                new LaneEndpoint("10.0.0.8", 28081));
    }
}
