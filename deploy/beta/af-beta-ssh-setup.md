# Beta 接待命令的 SSH 受限访问配置（运维侧）

目标：开发机 Agent 对 Beta 的访问能力被压缩为「只能执行 af-beta 的白名单子命令」——拿不到 shell、不能端口转发、不能传文件。机制是 OpenSSH 的强制命令（forced command），与 git 服务器限制用户只能做 git 操作（git-shell）相同。

## 步骤

1. 安装包装脚本（本目录 `af-beta-shell`）：

   ```bash
   install -m 755 af-beta-shell /opt/alphafrog-beta/bin/af-beta-shell
   ```

   白名单子命令写在脚本顶部的 `ALLOWED_SUBCOMMANDS`（当前 `status retry how-to-test lane main`），按《03-beta-机开发入口》增删。

2. 收集各开发机的**公钥**。开发机各自生成专用钥匙，私钥不出机器：

   ```bash
   ssh-keygen -t ed25519 -f ~/.ssh/af-beta-ed25519 -C dev-agent-<机器名>
   ```

3. 在 Beta 机接待账户的 `~/.ssh/authorized_keys` 里，为每把公钥加一行（整行一段，选项在前）：

   ```text
   command="/opt/alphafrog-beta/bin/af-beta-shell",restrict ssh-ed25519 AAAA...公钥内容... dev-agent-<机器名>
   ```

   - `command=...`：客户端请求的任何命令都会被忽略，sshd 只运行这个脚本；客户端原本想执行的命令进入环境变量 `SSH_ORIGINAL_COMMAND`，由脚本按白名单校验。
   - `restrict`（OpenSSH 7.8+，等价于同时禁 pty/端口转发/X11/agent 转发等全部附加能力）。OpenSSH 版本更旧时改用显式组合 `no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty`。
   - 可选：同一行再加 `from="开发机出口IP"` 限制来源地址。

4. 验证：从开发机执行 `bash deploy/beta/af-beta-remote.sh status` 应返回 af-beta 的状态输出；直接 `ssh -i ~/.ssh/af-beta-ed25519 <beta> 任意命令` 应被拒绝并提示「只允许执行 af-beta 子命令」；`ssh ... `（不带命令，要 shell）同样被拒。

## 开发机侧（无需运维，随仓库提供）

- `deploy/beta/af-beta-remote.sh`：读取 `AF_BETA_SSH`（SSH 目标）与 `AF_BETA_KEY`（专用私钥，默认 `~/.ssh/af-beta-ed25519`），只以专用钥匙发起连接并请求 `af-beta <参数>`。

## 参考资源

- OpenSSH 官方手册 AUTHORIZED_KEYS（command= 与 restrict 选项）: https://man.openbsd.org/sshd.8#AUTHORIZED_KEYS_FILE_FORMAT
- SSH Academy — OpenSSH authorized_keys 配置说明: https://www.ssh.com/academy/ssh/authorized-keys-openssh
- git-shell 先例（限制用户只能 git 操作）: https://man.openbsd.org/git-shell 、https://superuser.com/questions/299927/can-you-specify-git-shell-in-ssh-authorized-keys-to-restrict-access-to-only-git
- SSH_ORIGINAL_COMMAND 白名单校验实践: https://www.thomas-krenn.com/en/wiki/Restrict_executable_SSH-commands_via_authorized_keys
