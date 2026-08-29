package world.willfrog.agentlangchain.execution.dag;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.workflow.TodoItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LangchainDagExecutionGraphTest {

    @Test
    void from_shouldDetectCycle() {
        LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(List.of(
                TodoItem.builder().id("a").sequence(1).description("a").dependsOn(List.of("b")).build(),
                TodoItem.builder().id("b").sequence(2).description("b").dependsOn(List.of("a")).build()
        ));
        assertThat(graph.hasCycle()).isTrue();
    }

    @Test
    void from_shouldIgnoreUnknownDependsOn() {
        LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(List.of(
                TodoItem.builder().id("a").sequence(1).description("a").dependsOn(List.of("missing")).build()
        ));
        assertThat(graph.hasCycle()).isFalse();
        assertThat(graph.getDependencies("a")).isEmpty();
    }

    @Test
    void maxDependencyDepth_shouldBeZeroWhenGraphIsEmpty() {
        LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(List.of());
        assertThat(graph.maxDependencyDepth()).isZero();
    }

    @Test
    void maxDependencyDepth_shouldBeOneForIndependentNodes() {
        LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(List.of(
                TodoItem.builder().id("a").sequence(1).description("a").build(),
                TodoItem.builder().id("b").sequence(2).description("b").build()
        ));
        assertThat(graph.maxDependencyDepth()).isEqualTo(1);
    }

    @Test
    void maxDependencyDepth_shouldCountLongestDependsOnChain() {
        LangchainDagExecutionGraph graph = LangchainDagExecutionGraph.from(List.of(
                TodoItem.builder().id("a").sequence(1).description("a").build(),
                TodoItem.builder().id("b").sequence(2).description("b").dependsOn(List.of("a")).build(),
                TodoItem.builder().id("c").sequence(3).description("c").dependsOn(List.of("a")).build(),
                TodoItem.builder().id("d").sequence(4).description("d").dependsOn(List.of("b", "c")).build()
        ));
        assertThat(graph.maxDependencyDepth()).isEqualTo(3);
    }
}
