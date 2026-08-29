package world.willfrog.agentlangchain.orchestration;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.PlanExecutionMode;
import world.willfrog.agent.workflow.TodoItem;
import world.willfrog.agentlangchain.planning.LangchainTodoPlan;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionModeResolverTest {

    @Test
    void requestedLinearFreezesPlannerDagAndExplainsWhy() {
        LangchainTodoPlan planned = dagPlanWithDependency();

        ExecutionModeResolver.Decision decision =
                ExecutionModeResolver.resolve(PlanExecutionMode.LINEAR, planned);

        assertThat(decision.requested()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(decision.planned()).isEqualTo(PlanExecutionMode.DAG);
        assertThat(decision.effective()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(decision.useDag()).isFalse();
        assertThat(decision.effectivePlan().getItems()).allSatisfy(item -> {
            assertThat(item.getDependsOn()).isEmpty();
            assertThat(item.getGroupKey()).isNull();
            assertThat(item.isParallelizable()).isFalse();
        });
        assertThat(decision.reason()).isEqualTo("用户指定线性执行，规划结果按线性冻结");
    }

    @Test
    void requestedDagKeepsPlannerShape() {
        LangchainTodoPlan planned = linearPlanWithoutDependency();

        ExecutionModeResolver.Decision decision =
                ExecutionModeResolver.resolve(PlanExecutionMode.DAG, planned);

        assertThat(decision.effective()).isEqualTo(PlanExecutionMode.DAG);
        assertThat(decision.useDag()).isTrue();
        assertThat(decision.effectivePlan().getItems()).isEqualTo(planned.getItems());
        assertThat(decision.reason()).isEqualTo("用户指定 DAG 执行，保留规划里的节点依赖");
    }

    @Test
    void autoWithDependsOnBecomesDag() {
        LangchainTodoPlan planned = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.AUTO)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("a").build(),
                        TodoItem.builder().id("t2").sequence(2).description("b")
                                .dependsOn(List.of("t1")).build()))
                .build();

        ExecutionModeResolver.Decision decision =
                ExecutionModeResolver.resolve(PlanExecutionMode.AUTO, planned);

        assertThat(decision.planned()).isEqualTo(PlanExecutionMode.AUTO);
        assertThat(decision.effective()).isEqualTo(PlanExecutionMode.DAG);
        assertThat(decision.useDag()).isTrue();
        assertThat(decision.reason()).isEqualTo("用户未指定模式，计划含有依赖，按 DAG 执行");
    }

    @Test
    void autoWithoutDependsOnBecomesLinear() {
        LangchainTodoPlan planned = linearPlanWithoutDependency();

        ExecutionModeResolver.Decision decision =
                ExecutionModeResolver.resolve(PlanExecutionMode.AUTO, planned);

        assertThat(decision.effective()).isEqualTo(PlanExecutionMode.LINEAR);
        assertThat(decision.useDag()).isFalse();
        assertThat(decision.reason()).isEqualTo("用户未指定模式，按线性执行");
    }

    @Test
    void inspectFrozenDoesNotReDecideAuto() {
        LangchainTodoPlan frozen = LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("a").build()))
                .build();

        ExecutionModeResolver.Decision decision = ExecutionModeResolver.inspectFrozen(frozen);

        assertThat(decision.effectivePlan()).isSameAs(frozen);
        assertThat(decision.effective()).isEqualTo(PlanExecutionMode.DAG);
        assertThat(decision.useDag()).isTrue();
        assertThat(decision.reason()).isEqualTo("使用已冻结计划，不再重新裁决");
    }

    private static LangchainTodoPlan dagPlanWithDependency() {
        return LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.DAG)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("a")
                                .parallelizable(true).groupKey("g1").build(),
                        TodoItem.builder().id("t2").sequence(2).description("b")
                                .dependsOn(List.of("t1")).parallelizable(true).groupKey("g1").build()))
                .build();
    }

    private static LangchainTodoPlan linearPlanWithoutDependency() {
        return LangchainTodoPlan.builder()
                .executionMode(PlanExecutionMode.LINEAR)
                .items(List.of(
                        TodoItem.builder().id("t1").sequence(1).description("a").build(),
                        TodoItem.builder().id("t2").sequence(2).description("b").build()))
                .build();
    }
}
