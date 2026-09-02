package world.willfrog.alphafrogmicro.common.lane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaneAutoConfigurationTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LaneAutoConfiguration.class));
    }

    @AfterEach
    void reset() {
        LaneRoutingSupport.reset();
        LaneContext.clear();
    }

    @Test
    void disabledByDefault_shouldNotReadControllerStateOrPinInstances() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LaneCallRouter.class);
            assertThat(context).doesNotHaveBean(LaneRoutePointer.class);
            assertThat(LaneRoutingSupport.enabled()).isFalse();
            LaneContext.setTrafficScopeId("main-beta");
            assertThatThrownBy(() -> context.getBean(LaneCallRouter.class)
                    .bindNewCall("main-beta", "agent-service"))
                    .isInstanceOf(LaneRouteUnavailableException.class);
        });
    }

    @Test
    void enabledFalseOrInvalid_shouldStayDisabled() {
        runner().withPropertyValues("alphafrog.lane.routing.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(LaneRoutePointer.class);
            assertThat(LaneRoutingSupport.enabled()).isFalse();
        });
        runner().withPropertyValues("alphafrog.lane.routing.enabled=unexpected").run(context -> {
            assertThat(context).doesNotHaveBean(LaneRoutePointer.class);
            assertThat(LaneRoutingSupport.enabled()).isFalse();
        });
    }

    @Test
    void enabledTrue_shouldUseCustomPointerAndFailClosedOnEmptyTable() {
        runner()
                .withUserConfiguration(CustomPointerConfig.class)
                .withPropertyValues("alphafrog.lane.routing.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(LaneRoutePointer.class);
                    assertThat(LaneRoutingSupport.enabled()).isTrue();
                    assertThatThrownBy(() -> context.getBean(LaneCallRouter.class)
                            .bindNewCall("main-beta", "agent-service"))
                            .isInstanceOf(LaneRouteUnavailableException.class);
                    AtomicLaneRoutePointer pointer = context.getBean(AtomicLaneRoutePointer.class);
                    pointer.replaceAll(LaneRouteTable.of(List.of(new LaneServiceRoute(
                            "main-beta",
                            "agent-service",
                            new LaneDubboServiceKey("langchain", "com.alphafrog.AgentService", ""),
                            "providers:com.alphafrog.AgentService::langchain",
                            "instance-new",
                            "release-2",
                            "gen-" + "d".repeat(64),
                            8L,
                            "2026-09-01T00:02:00Z",
                            new LaneEndpoint("10.0.0.8", 28081)))));
                    LaneCallBinding binding = context.getBean(LaneCallRouter.class)
                            .bindNewCall("main-beta", "agent-service");
                    assertThat(binding.instanceId()).isEqualTo("instance-new");
                });
    }

    @Configuration
    static class CustomPointerConfig {
        @Bean
        AtomicLaneRoutePointer laneRoutePointer() {
            return new AtomicLaneRoutePointer();
        }
    }
}
