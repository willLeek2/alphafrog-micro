package world.willfrog.agent.platform.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunDeploymentIdentityMigrationContractTest {

    @Test
    void upgradeBackfillsLegacyIdentityAndMakesItImmutable() throws Exception {
        String sql = Files.readString(findRepositoryRoot().resolve(
                "migrate/migrations/upgrades/v1.5/001_agent_run_deployment_identity.sql"));

        assertThat(sql)
                .contains("deployment_id VARCHAR(64) NOT NULL DEFAULT 'stable'")
                .contains("deployment_generation_id VARCHAR(68) NOT NULL DEFAULT 'legacy-stable'")
                .contains("ALTER COLUMN deployment_id DROP DEFAULT")
                .contains("ALTER COLUMN deployment_generation_id DROP DEFAULT")
                .contains("deployment_generation_id = 'legacy-stable'")
                .contains("legacy_deployment_generation_inactive")
                .contains("tool_job_anchor_json = CAST('{}' AS jsonb)")
                .contains("^gen-[0-9a-f]{64}$")
                .contains("BEFORE UPDATE OF deployment_id, deployment_generation_id")
                .contains("Agent Run deployment identity is immutable")
                .contains("idx_agent_run_deployment_generation_status");
    }

    @Test
    void freshSchemaContainsIdentityAndAllCurrentRunStates() throws Exception {
        String sql = Files.readString(findRepositoryRoot().resolve(
                "migrate/migrations/init/004_agent.sql"));

        assertThat(sql)
                .contains("deployment_id VARCHAR(64) NOT NULL")
                .contains("deployment_generation_id VARCHAR(68) NOT NULL")
                .doesNotContain("deployment_id VARCHAR(64) NOT NULL DEFAULT")
                .doesNotContain("deployment_generation_id VARCHAR(68) NOT NULL DEFAULT")
                .contains("'WAITING_TOOL_JOB'")
                .contains("'PARTIAL'")
                .contains("'CANCELING'")
                .contains("lane_tag VARCHAR(96)")
                .contains("BEFORE UPDATE OF deployment_id, deployment_generation_id, lane_tag");
    }

    @Test
    void laneTagUpgradeIsNullableValidatedAndImmutable() throws Exception {
        String sql = Files.readString(findRepositoryRoot().resolve(
                "migrate/migrations/upgrades/v1.5/002_agent_run_lane_tag.sql"));

        assertThat(sql)
                .contains("ADD COLUMN IF NOT EXISTS lane_tag VARCHAR(96)")
                .contains("alphafrog_agent_run_lane_tag_check")
                .contains("NEW.lane_tag IS DISTINCT FROM OLD.lane_tag")
                .contains("BEFORE UPDATE OF deployment_id, deployment_generation_id, lane_tag");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("migrate"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
