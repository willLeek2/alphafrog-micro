# run-driver-014data04（014-data-04 节点 d 的 c6-c8 run 驱动器）

独立 maven 项目（不进根 pom modules）。作用：Dubbo 直连泳道 agent（agentLangchainService），
用直通身份创建一条真实 run，让模型（deepseek/deepseek-v4.1-flash）按四步提示词依次调
listMyData → executePython（仅 ds_）→ executePython（数字+ds_ 混传）→ promoteDatasetToPlatform，
轮询到终态后把全部应答原文落盘 evidence。

## 构建（机器侧，JDK 17 + 机器 maven）

```bash
cd <本仓检出根目录>
JAVA_HOME="$(/usr/libexec/java_home -v 17)" mvn -q -f agentApi/pom.xml install -DskipTests   # 一次性
JAVA_HOME="$(/usr/libexec/java_home -v 17)" mvn -q -f run-driver-014data04/pom.xml clean package
# 产物 run-driver-014data04/target/run-driver-014data04.jar
```

## 真跑（泳道 lane-014data04m 的 agent）

```bash
STAMP=$(date +%Y%m%d%H%M%S)
java -Ddubbo.application.serialize-check-status=DISABLE -jar run-driver-014data04/target/run-driver-014data04.jar \
  --agent-url dubbo://172.27.49.47:53400 \
  --ds-id ds_d036f04f \
  --name "acc04-b-$STAMP" \
  --description "014-data-04 节点d 提升回查对象B（run内提升产生，源=对象A挂载副本）" \
  --semantic-ref ah_premium@1 \
  --primary-key trade_date \
  --coverage-scope "e2e04-promoted-$STAMP" \
  --coverage-start 2026-01-05 \
  --coverage-end 2026-01-26 \
  --timeout-seconds 900
echo "exit=$?"
```

要点：
- **协议用 dubbo:// 不是 tri://**（该端口 tri 握手会被重置——开发机实测）。
- **-Ddubbo.application.serialize-check-status=DISABLE 必须带**（消费端 STRICT 模式拦 protobuf 类，
  开发机实测被 SerializedForm allow list 拒）。
- 退出码：0=COMPLETED 且工具核对全过；1=COMPLETED 但核对有 FAIL；2=连接/创建中断；3=非 COMPLETED 终态；4=超时。
- evidence 落在 out-014data04/<时间戳>/（params/create-response/poll.log/events.jsonl/result.txt/
  run-message.txt/run-credits.txt/evidence-summary.txt），跑完打 tar 回传 wang（走你的附件上传通道）。
- 预期核对门（evidence-summary）：listMyData.started、executePython.started、executePython.ds_only_step2、
  executePython.dataset_ids_mixed、promoteDatasetToPlatform.started、promoteDatasetToPlatform.finished_success。
- 成功后把 promote 产生的新 ds_ id 告诉 wang（phase 4 的 --object-id 用），或从 events.jsonl 里取。
