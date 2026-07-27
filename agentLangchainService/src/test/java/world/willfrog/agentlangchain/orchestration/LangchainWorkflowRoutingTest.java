package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangchainWorkflowRoutingTest {

    @Test
    void shouldUseDag_whenDependsOnPresentInAutoMode() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.AUTO)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("a").build(),
                        TodoItem.builder().id("t2").sequence(2).description("b").dependsOn(List.of("t1")).build()
                ))
                .build();
        assertThat(LangchainWorkflowRouting.shouldUseDag(plan)).isTrue();
    }

    @Test
    void shouldNotUseDag_forExplicitLinear() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(
                        TodoItem.builder().id("t2").sequence(2).description("b").dependsOn(List.of("t1")).build()
                ))
                .build();
        assertThat(LangchainWorkflowRouting.shouldUseDag(plan)).isFalse();
    }

    @Test
    void durableToolBuildsPersistableLinearPlanBeforeExecution() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("a")
                                .parallelizable(true).groupKey("g1").build(),
                        TodoItem.builder().id("t2").sequence(2).description("b")
                                .dependsOn(List.of("t1")).parallelizable(true).groupKey("g1").build()))
                .build();

        LangchainTodoPlan effective = LangchainWorkflowRouting.effectivePlan(plan, true);

        assertThat(effective).isNotSameAs(plan);
        assertThat(effective.getExecutionMode()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(effective.getItems()).allSatisfy(item -> {
            assertThat(item.getDependsOn()).isEmpty();
            assertThat(item.getGroupKey()).isNull();
            assertThat(item.isParallelizable()).isFalse();
        });
        assertThat(LangchainWorkflowRouting.shouldUseDag(effective)).isFalse();
        assertThat(LangchainWorkflowRouting.effectivePlan(effective, true)).isSameAs(effective);
    }
}
