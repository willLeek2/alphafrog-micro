package world.willfrog.agent.platform.wait;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真库探针的连接串守卫：连接串里不许夹账号口令。
 *
 * <p>这一条不需要数据库，所以常态化跑，而不是等有了外部连接串才跑。要挡的是两类写法：主机前面带
 * userinfo（{@code user:pass@host}），以及查询参数里的 {@code user=}/{@code password=}——后者会
 * 一路交给驱动，等于把口令写进了连接。</p>
 *
 * <p>大小写不敏感：{@code USER=}、{@code Password=} 同样挡；参数名带百分号编码的（{@code u%73er}）
 * 先解码再比。反过来，与凭证无关的查询参数要原样留下，编码也不能在解析时被改掉：先解码整串会把
 * {@code %26} 拆成新的参数分隔符，参数边界就变了。</p>
 */
class Stage3DsnCredentialGuardTest {

    private static final String REFUSAL_HINT = "AF_STAGE3_PG_USER";

    @Test
    void userInfoInEitherWritingIsRejected() {
        for (String dsn : List.of(
                "postgresql://user:secret@db.example.com:5432/af",
                "postgres://user:secret@db.example.com:5432/af",
                "jdbc:postgresql://user:secret@db.example.com:5432/af")) {
            assertThatThrownBy(() -> Stage3WaitContractPostgresTest.resolveTarget(dsn))
                    .as("连接串里带账号口令必须失败关闭：" + dsn)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(REFUSAL_HINT);
        }
    }

    @Test
    void credentialQueryParametersAreRejectedWhateverTheirCase() {
        for (String query : List.of(
                "user=af",
                "USER=af",
                "User=af",
                "u%73er=af",
                "password=secret",
                "PASSWORD=secret",
                "Password=secret",
                "ssl=true&Password=secret",
                "ssl=true&user=af&password=secret")) {
            assertThatThrownBy(() -> Stage3WaitContractPostgresTest
                    .resolveTarget("postgresql://db.example.com:5432/af?" + query))
                    .as("查询参数里带账号口令必须失败关闭：?" + query)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(REFUSAL_HINT);
            assertThatThrownBy(() -> Stage3WaitContractPostgresTest
                    .resolveTarget("jdbc:postgresql://db.example.com:5432/af?" + query))
                    .as("jdbc 写法同样要挡：?" + query)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(REFUSAL_HINT);
        }
    }

    @Test
    void harmlessQueryParametersSurviveWithTheirEncoding() {
        String dsn = "postgresql://db.example.com:5432/af?ssl=true&options=-c%20search_path%3Daf";
        Stage3WaitContractPostgresTest.Target target = Stage3WaitContractPostgresTest.resolveTarget(dsn);

        assertThat(target.jdbcUrl())
                .as("查询参数要原样保留编码，不在解析时解码再拼回去")
                .isEqualTo("jdbc:postgresql://db.example.com:5432/af"
                        + "?ssl=true&options=-c%20search_path%3Daf");
    }

    @Test
    void jdbcFormIsHandedToTheDriverVerbatim() {
        String dsn = "jdbc:postgresql://db.example.com:5432/af?ssl=true&ApplicationName=stage3";
        assertThat(Stage3WaitContractPostgresTest.resolveTarget(dsn).jdbcUrl())
                .as("已经是 JDBC 写法：原样交给驱动，只做凭证检查")
                .isEqualTo(dsn);
    }

    @Test
    void unparsableDsnIsReportedWithoutEchoingIt() {
        String dsn = "postgresql://[bad::host/af";
        assertThatThrownBy(() -> Stage3WaitContractPostgresTest.resolveTarget(dsn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析不了")
                .hasMessageNotContaining("[bad::host");
    }
}
