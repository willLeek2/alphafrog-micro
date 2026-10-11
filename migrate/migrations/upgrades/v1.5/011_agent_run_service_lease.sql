-- Run 的服务所有权租约：哪一代进程此刻可以动这条 Run。
--
-- 为什么需要一张独立的事实：进程可能在两个窗口里消失——一是 Run 已经提交、还没交给执行入口；
-- 二是执行进行到一半。这两种情况都必须有人接手，而「接」的前提是先能证明原来那一代已经不在了。
-- 内存里的登记只能说明本进程自己在做什么，另一个进程什么状态它看不到；按「多久没被改过」推断
-- 更不行：模型调用、工具等待、长节点执行期间本来就不写数据库，合法地静默很久。
--
-- 所以所有权写成一行会过期的事实：谁领到谁可以动，过期之后别人可以按条件接走。接手时把
-- fencing_token 加一，于是「旧主人手里那个号」立刻作废——迟到的写入拿旧号进来会一行都写不动。
--
-- 一行只属于一条 Run；同一代进程重复领到的是同一行、同一个号，只有换主人时才加一。
CREATE TABLE IF NOT EXISTS alphafrog_agent_run_service_lease (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    -- 持有者标识：应用名 + 主机 + 进程号 + 本次启动的一个短标识，同机重启也不会与前一代撞号。
    owner_instance_id VARCHAR(160) NOT NULL,
    -- 换主人时加一；同一位主人重复领取不变。用来拒绝迟到写入。
    fencing_token BIGINT NOT NULL DEFAULT 1,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    renewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 过期之后这一行可以被别人按条件接走；持有者必须在自己活着的时候不断把它推后。
    -- 主动让出就是把它置为「此刻已经到期」：这一行留着，代际号跟着留下来继续往上涨。
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT alphafrog_agent_run_service_lease_owner_check
        CHECK (btrim(owner_instance_id) <> '' AND fencing_token > 0)
);

COMMENT ON TABLE alphafrog_agent_run_service_lease IS
    'Run 的服务所有权租约：可跨进程条件领取、会过期、换主人时代际加一。';

-- 按过期时间找可以接走的行：接手扫描按它取有界的一页。
CREATE INDEX IF NOT EXISTS idx_agent_run_service_lease_expiry
    ON alphafrog_agent_run_service_lease (expires_at);
