package world.willfrog.agentlangchain.execution;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void requestedLinearBuildsPersistableLinearPlanBeforeExecution() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("a")
                                .parallelizable(true).groupKey("g1").build(),
                        TodoItem.builder().id("t2").sequence(2).description("b")
                                .dependsOn(List.of("t1")).parallelizable(true).groupKey("g1").build()))
                .build();

        LangchainTodoPlan effective = LangchainWorkflowRouting.effectivePlan(
                plan, PlanExecutionMode.LINEAR);

        assertThat(effective).isNotSameAs(plan);
        assertThat(effective.getExecutionMode()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(effective.getItems()).allSatisfy(item -> {
            assertThat(item.getDependsOn()).isEmpty();
            assertThat(item.getGroupKey()).isNull();
            assertThat(item.isParallelizable()).isFalse();
        });
        assertThat(LangchainWorkflowRouting.shouldUseDag(effective)).isFalse();
        assertThat(LangchainWorkflowRouting.effectivePlan(
                effective, PlanExecutionMode.LINEAR)).isSameAs(effective);
    }

    @Test
    void requestedLinearTopologicallySortsAndRenumbersPlannerDag() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(
                        TodoItem.builder().id("join").sequence(1).description("汇总")
                                .dependsOn(List.of("left", "right")).build(),
                        TodoItem.builder().id("right").sequence(3).description("右分支")
                                .dependsOn(List.of("root")).build(),
                        TodoItem.builder().id("root").sequence(4).description("入口").build(),
                        TodoItem.builder().id("left").sequence(2).description("左分支")
                                .dependsOn(List.of("root")).build()))
                .build();

        LangchainTodoPlan effective = LangchainWorkflowRouting.effectivePlan(
                plan, PlanExecutionMode.LINEAR);

        assertThat(effective.getItems()).extracting(TodoItem::getId)
                .containsExactly("root", "left", "right", "join");
        assertThat(effective.getItems()).extracting(TodoItem::getSequence)
                .containsExactly(1, 2, 3, 4);
        assertThat(effective.getItems()).allSatisfy(item -> assertThat(item.getDependsOn()).isEmpty());
    }

    @Test
    void requestedLinearFailsClosedForMissingDependency() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(TodoItem.builder()
                        .id("todo-1")
                        .sequence(1)
                        .description("查询")
                        .dependsOn(List.of("missing"))
                        .build()))
                .build();

        assertThatThrownBy(() -> LangchainWorkflowRouting.effectivePlan(
                plan, PlanExecutionMode.LINEAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("linear_plan_not_linearizable:missing_dependency:todo-1->missing");
    }

    @Test
    void requestedLinearFailsClosedForDependencyCycle() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(
                        TodoItem.builder().id("todo-1").sequence(1).description("一")
                                .dependsOn(List.of("todo-2")).build(),
                        TodoItem.builder().id("todo-2").sequence(2).description("二")
                                .dependsOn(List.of("todo-1")).build()))
                .build();

        assertThatThrownBy(() -> LangchainWorkflowRouting.effectivePlan(
                plan, PlanExecutionMode.LINEAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("linear_plan_not_linearizable:dependency_cycle");
    }

    @Test
    void requestedDagOverridesPlannerLinearModeWithoutDroppingNodes() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("a").build(),
                        TodoItem.builder().id("t2").sequence(2).description("b").build()))
                .build();

        LangchainTodoPlan effective = LangchainWorkflowRouting.effectivePlan(
                plan, PlanExecutionMode.DAG);

        assertThat(effective.getExecutionMode()).isEqualTo(PlanExecutionMode.DAG);
        assertThat(effective.getItems()).containsExactlyElementsOf(plan.getItems());
        assertThat(LangchainWorkflowRouting.shouldUseDag(effective)).isTrue();
    }

    @Test
    void requestedAutoFreezesPlannerShapeIntoConcreteEffectiveMode() {
        LangchainTodoPlan linear = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.AUTO)
                .items(List.of(TodoItem.builder().id("t1").sequence(1).description("a").build()))
                .build();
        LangchainTodoPlan dag = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.AUTO)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("a").build(),
                        TodoItem.builder().id("t2").sequence(2).description("b")
                                .dependsOn(List.of("t1")).build()))
                .build();

        LangchainTodoPlan effectiveLinear = LangchainWorkflowRouting.effectivePlan(
                linear, PlanExecutionMode.AUTO);
        LangchainTodoPlan effectiveDag = LangchainWorkflowRouting.effectivePlan(
                dag, PlanExecutionMode.AUTO);

        assertThat(effectiveLinear.getExecutionMode()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(LangchainWorkflowRouting.shouldUseDag(effectiveLinear)).isFalse();
        assertThat(effectiveDag.getExecutionMode()).isEqualTo(PlanExecutionMode.DAG);
        assertThat(LangchainWorkflowRouting.shouldUseDag(effectiveDag)).isTrue();
    }

    @Test
    void restartRejectsAutoPlanBecauseItsExecutionModeWasNotFrozen() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.AUTO)
                .items(List.of(TodoItem.builder().id("t1").sequence(1).description("a").build()))
                .build();

        assertThatThrownBy(() -> LangchainWorkflowRouting.validateFrozenPlan(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("workflow_restart_plan_mode_not_frozen");
    }

    @Test
    void restartRejectsLinearPlanThatWouldNeedRenumberingOrDependencyRepair() {
        LangchainTodoPlan plan = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(
                        TodoItem.builder().id("t2").sequence(2).description("b")
                                .dependsOn(List.of("t1")).build(),
                        TodoItem.builder().id("t1").sequence(1).description("a").build()))
                .build();

        assertThatThrownBy(() -> LangchainWorkflowRouting.validateFrozenPlan(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("workflow_restart_linear_plan_not_canonical");
    }
}
