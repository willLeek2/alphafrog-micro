# 灰度规则 v1 合同

这里固定 `gray-rules.json` 的文档结构和稳定分桶算法，供后续 Java 灰度模块、Nacos 配置接入和 Python 验收工具共同使用。本目录只定义合同，不实现业务灰度逻辑，也不代表 Nacos 已经开启认证或真实环境已经发布过规则。

## 1. 文件与版本

- `gray-rules.schema.json`：JSON Schema draft-07，`$id` 末尾的 `gray-rules-v1.schema.json` 是结构版本。结构发生不兼容变化时必须新增 v2，不能原地改变 v1 的含义。
- `gray-rules.example.json`：可发布文档的最小完整示例。示例里的 `bucketSalt` 使用权威方案给出的 `s1`，所以 Schema 不额外发明“至少 8 个字符”的限制。
- `gray-bucket-test-vectors.json`：Java 与 Python 必须逐条读取并断言的共同测试数据。大于 JavaScript 安全整数范围的无符号 64 位中间值用十进制字符串保存，避免 JSON 消费方先丢精度。
- `verify_gray_contract.py`：只依赖 Python 标准库的仓库自检。它检查示例的结构语义、全部哈希中间值、空身份决策和扩大百分比的集合包含关系。

文档里的 `ruleVersion` 不是 Schema 版本。它只表示这一次配置发布的传播与审计身份，加载成功后供每个实例写入限频日志，并可与落盘的 `gray-rules.local.json` 对照。`ruleVersion` 不进入分桶输入；只改版本不能改变任何用户的桶号。

`bucketSalt` 是分桶盐。显式改盐表示有意让全部规则重新分桶，需要单独审批；它不能偷偷跟着 `ruleVersion` 变化。

## 2. 整份加载合同

加载器必须先校验整份文档，再一次性替换内存快照。下列任一问题都要拒绝整份新文档并继续使用上一个已成功加载的快照，不能只跳过坏规则后上线半份配置：

1. JSON 语法、Schema 类型、必填字段或取值范围不合法；
2. 两条规则使用相同 `ruleId`；JSON Schema draft-07 的 `uniqueItems` 不能按对象内某个字段判重，所以这是必须单独执行的语义校验；
3. `ruleVersion` 或 `owner` 只含空白；
4. `expiresAt` 不是带明确 UTC 偏移的 RFC 3339 时间；Schema 的 `format` 在部分校验器里只是提示，加载器仍须严格解析；
5. 未声明字段存在。v1 使用 `additionalProperties: false`，避免发布者拼错字段名后以为规则已经生效。

进程首次启动、尚未成功加载过任何文档时，内存里必须只有一个**空快照**：它不包含规则，也不包含 `ruleVersion`。在首份完整合法文档成功通过结构和语义校验之前，任何规则查询都返回 `false`；加载失败时继续保持这个空快照，不能用空字符串、`unknown` 或其他占位值伪造一个规则版本。只有首份合法文档通过全部校验后，加载器才能原子替换空快照并开始对外报告真实 `ruleVersion`。后续加载失败时则继续使用最近一次成功快照。

每条规则的 `userFilter` 是必填数组；没有定向用户时明确写空数组 `[]`，不让不同语言自行猜测缺省值。规则过期后仍保留在快照里供审计，但查询时视为关闭。判定时刻满足 `now >= expiresAt` 就算过期。

## 3. 唯一判定顺序

`GrayDecider` 必须按下面顺序返回，前面的条件优先级更高：

1. 没有这个 `ruleId`：返回 `false`；
2. `enabled=false`：返回 `false`；
3. 当前时刻已经到达或超过 `expiresAt`：返回 `false`；
4. `userId` 缺失或是长度为零的字符串：返回 `false`；
5. `userId` 与 `userFilter` 中某一项逐字符完全相等：返回 `true`；
6. 计算稳定桶号，只有 `bucket < percent` 时返回 `true`。

这里不做 trim、大小写折叠或 Unicode 归一化。空字符串与只有空格的字符串不是同一个输入；身份产生方应当在更早的身份合同里拒绝不合法标识。Java 与 Python 若各自增加隐式规范化，会让同一用户落到不同桶。

## 4. 分桶算法逐字节定义

对非空 `userId`：

1. 按原值拼接 `ruleId + ":" + bucketSalt + ":" + userId`；三个字段不 trim、不转义、不做大小写或 Unicode 归一化；
2. 把拼接结果按 UTF-8 编码；
3. 计算 32 字节 SHA-256；
4. 取摘要前 8 字节，按无符号、大端顺序解释为 64 位整数；
5. 对该无符号整数取模 100，得到 `0..99` 的桶号；
6. 桶号严格小于 `percent` 才命中。`percent=0` 无分桶用户命中，`percent=100` 所有非空用户命中。

Java 不能把前 8 字节直接当有符号 `long` 后用 `% 100`，否则最高位为 1 的向量会得到负数。等价、安全的写法是 `new BigInteger(1, firstEightBytes).mod(BigInteger.valueOf(100)).intValue()`。Python 的等价写法是 `int.from_bytes(first_eight_bytes, "big", signed=False) % 100`。

`gray-bucket-test-vectors.json` 中 V1 保存了完整调试链：

- 输入：`demo-rule:alpha-salt-260831:user-0001`
- SHA-256：`a132d56d31ed277557b77340c413eafacb419526ac09bbce4183dde4d3975854`
- 前 8 字节：`a132d56d31ed2775`
- 无符号十进制：`11615581053907707765`
- 桶号：`65`

V8 证明改盐会给同一规则、同一用户重新分桶；V9 证明不同规则独立分桶；V10 证明非 ASCII 身份按 UTF-8 字节计算；V11 给 10%→40% 扩量提供一个原本已经命中的非空集合，避免“空集合自然是子集”的假通过。`versionIndependenceCases` 另外钉住“只改 `ruleVersion` 不重新分桶”。

## 5. 扩大百分比的稳定承诺

桶号只依赖 `ruleId`、`bucketSalt` 和 `userId`，与 `percent`、`ruleVersion` 无关。在规则、盐和身份不变时，如果百分比从较小值 `P1` 扩到较大值 `P2`，所有满足 `bucket < P1` 的用户必然继续满足 `bucket < P2`。测试必须同时检查：

- 同一用户扩量前后桶号不变；
- 旧命中集合是新命中集合的子集；
- 固定向量里的预期集合与实际计算完全一致。

缩小百分比不提供“原命中仍命中”承诺；改 `bucketSalt` 也不提供。定向名单的命中受 `enabled`、过期和非空身份三项更高优先级约束。

## 6. 本地验证

在仓库根目录执行：

```bash
python3 deploy/gray/verify_gray_contract.py
python3 -m json.tool deploy/gray/gray-rules.schema.json >/dev/null
python3 -m json.tool deploy/gray/gray-rules.example.json >/dev/null
python3 -m json.tool deploy/gray/gray-bucket-test-vectors.json >/dev/null
```

后续 2-1 的 Java 单元测试必须直接读取同一个 `gray-bucket-test-vectors.json`，不能在 Java 测试里另抄一份数值。Python 验收也读取同一文件。修改 v1 算法或任何预期向量时，Java 与 Python 两边必须在同一个变更中重新验证；单独改某一语言的“正确答案”不算合同变更完成。

2-1 还必须单独测试首次加载边界：首份文档到达前所有规则都返回 `false`、对外没有 `ruleVersion`；首份坏文档不会产生半份规则或伪造版本；首份合法文档才原子替换空快照。Schema 测试必须至少包含一个没有时区的负例 `2026-09-15T00:00:00`，并确认即使校验器忽略 `format`，结尾时区 `pattern` 仍会拒绝它。
