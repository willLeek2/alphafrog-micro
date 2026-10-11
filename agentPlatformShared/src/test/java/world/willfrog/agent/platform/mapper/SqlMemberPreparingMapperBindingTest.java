package world.willfrog.agent.platform.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlMemberPreparingMapperBindingTest {
    @Test void ownerReadBindsFrozenIdentityAndNeverGrantsRepeatedCreate() throws Exception {
        Configuration config = mapper(WaitGroupMapper.class, "mapper/WaitGroupMapper.xml");
        var owner = config.getMappedStatement(WaitGroupMapper.class.getName() + ".countPreparingSqlMemberOwner")
                .getBoundSql(Map.of("runId", "run", "groupId", 7, "memberIdentity", "member", "anchorJson", "{}", "proofJson", "{}"));
        assertThat(owner.getSql()).contains("r.status = 'EXECUTING'", "g.state = 'WAITING'", "m.state = 'PENDING'",
                "m.tool_name = 'executeQuery'", "b.state = 'CONFIRMED'", "wi.state = 'RESULT_COMMITTED'",
                "'workItemPlanGeneration'", "'workItemNodeId'", "'workItemNodeAttempt'", "'workItemSegmentSequence'",
                "'workItemRunControlVersion'", "'workItemContextVersion'", "'workItemClaimEpoch'", "'workItemClaimedBy'");
        assertThat(config.getMappedStatement(WaitGroupMapper.class.getName() + ".lockPreparingSqlMemberContext")
                .getBoundSql(Map.of("runId", "run", "groupId", 7, "memberIdentity", "member")).getSql())
                .contains("FOR UPDATE OF m, wi", "g.run_id = ?", "m.member_identity = ?");
        Configuration workItems = mapper(NodeWorkItemMapper.class, "mapper/NodeWorkItemMapper.xml");
        String claimSql = workItems.getMappedStatement(NodeWorkItemMapper.class.getName() + ".claim")
                .getBoundSql(Map.of()).getSql();
        assertThat(claimSql).contains("state IN ('RUNNABLE', 'RESUMABLE')").doesNotContain("'RESULT_COMMITTED'");
        String firstWrite = config.getMappedStatement(WaitGroupMapper.class.getName() + ".recordMemberPreparing")
                .getBoundSql(Map.of("groupId", 7, "memberIdentity", "member", "externalOperationId", "op", "dispatchProofJson", "{}"))
                .getSql();
        assertThat(firstWrite).contains("AND m.dispatch_proof_json IS NULL").doesNotContain("OR m.dispatch_proof_json");
    }

    @Test void lockedReadWaitsOnRunAndNormalCompletionCannotOverwriteSqlPreparingProof() throws Exception {
        Configuration runs = mapper(AgentRunMapper.class, "mapper/AgentRunMapper.xml");
        assertThat(runs.getMappedStatement(AgentRunMapper.class.getName() + ".findByIdForUpdate")
                .getBoundSql(Map.of("id", "run")).getSql()).contains("FOR UPDATE");
        Configuration members = mapper(WaitGroupMapper.class, "mapper/WaitGroupMapper.xml");
        var sql = members.getMappedStatement(WaitGroupMapper.class.getName() + ".completeMember")
                .getBoundSql(Map.of()).getSql().replaceAll("\\s+", " ");
        assertThat(sql).contains("m.state = 'PENDING' AND m.tool_name IN ('executePython', 'executeQuery')");
    }

    private Configuration mapper(Class<?> type, String path) throws Exception {
        Configuration config = new Configuration(); config.addMapper(type);
        try (var input = Resources.getResourceAsStream(path)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
        return config;
    }
}
