# 静态检查报告比较

`static_check_report.py` 读取维护者提供的检查清单、基准执行记录和候选执行记录，列出每项检查的覆盖情况。它只依赖 Python 3 标准库，不启动检查命令、模型、数据库或服务。

每项结果区分通过、失败、跳过和未执行。“未执行”表示没有取得完整对应记录，不能仅据此证明检查实际没有运行。两侧身份、固定输入、结果参数、运行时和执行器可比时，报告才判断新增失败、已有失败、恢复通过或保持通过。恢复通过的输出仍保留基准失败原文及来源。

## 调用

```bash
python3 devtools/static_check_report.py \
  --manifest retained-checks.json \
  --baseline baseline-execution.json \
  --candidate candidate-execution.json \
  --json-output comparison.json \
  --markdown-output comparison.md
```

未指定 `--json-output` 时，JSON 输出到标准输出；只有明确指定路径才写文件。输出目录需要事先存在。成功生成报告返回0，即使报告含失败或不可比项；清单结构无效、执行记录结构无效或输出无法写入时返回2。维护者需要阅读报告中的状态与问题，不能把进程退出0解释成所有检查通过。

## 检查清单

以下内容仅说明输入格式。实际清单需要填写已经确认的保留检查、源码身份和报告对应关系，示例不代表实际检查已经完成。

```json
{
  "schema_version": 1,
  "checks": [
    {
      "check_id": "example-repo:calculator:example.SumTest:add",
      "repo": "example-repo",
      "module": "calculator",
      "source": {
        "file": "calculator/src/test/java/example/SumTest.java",
        "class": "example.SumTest",
        "method": "add"
      },
      "exists_in_baseline": true,
      "exists_in_candidate": true,
      "definition_count": 1,
      "report_matches": [
        {
          "report_module": "calculator",
          "report_class": "example.SumTest",
          "report_case": "add"
        }
      ],
      "fixed_inputs": {"baseline": [1, 2], "candidate": [1, 2]},
      "result_params": {"baseline": {"scale": 2}, "candidate": {"scale": 2}}
    }
  ]
}
```

`check_id` 是由仓库、模块、类和方法构成的稳定编号。`source` 分别保存文件、类、方法字符串；通常指基准中的定义，只有候选存在的新检查则指候选定义。两项 `exists_in_*` 使用布尔值，说明定义是否存在。

每条定义的 `definition_count` 固定为1。同一仓库、模块内的同一源类方法只允许一条定义，不能通过不同编号重复计数。参数化检查在 `report_matches` 中逐项列出实际用例名，仍只算一条定义；每个对应项的模块、类和用例名都是字符串。脚本保留 XML 的 `classname` 原值，不按名称前缀、参数序号或相似类名猜测对应关系。一条报告记录不能同时属于多个定义。

`fixed_inputs` 和 `result_params` 保存两侧明确的结构化JSON值。两侧均有值且相同才允许比较；缺失时省略该侧键或填写 `null`，不能为了比较而填入空对象。真实输入为空对象时可以明确填写 `{}`。数组顺序有意义，对象键顺序不影响比较。

`expanded_count` 不写入清单，由输出分别统计两侧实际报告记录数。仓库分别汇总；一个仓库的检查缺口不会被另一个仓库的通过数量抵消。

## 执行记录与报告路径

基准和候选各提供一份记录，可以同时列出多个仓库。下例中的提交和版本只是格式示意，应替换为实际内容。

```json
{
  "repositories": [
    {
      "repo": "example-repo",
      "commit": "本次实际执行的完整提交标识",
      "command": "mvn -pl calculator test",
      "environment": {"runtime": "JDK17的实际版本", "runner": "Maven及Surefire的实际版本"},
      "selection": {"modules": ["calculator"], "test": null},
      "compile_status": "passed",
      "package_status": "not_requested",
      "root": "/absolute/path/to/report-archive",
      "reports": ["calculator/target/surefire-reports/TEST-example.SumTest.xml"]
    }
  ]
}
```

`root` 是该仓库的报告根目录，可以是源码目录，也可以是保留了 Maven 模块层级的报告归档目录。相对 `root` 按执行记录JSON所在目录解析；`reports` 中相对路径按 `root` 解析。绝对报告路径也需要位于 `root` 下，才能确定模块。

首版支持 `模块/target/surefire-reports/文件.xml`，模块是 `target` 之前的相对路径；根项目的模块使用 `.`。XML本身没有模块字段，`testsuite` 名称不能替代模块路径。盘点获得实际 pytest 报告后再明确其路径约定，当前不把普通 pytest 输出路径自动当作 Maven 模块。

`commit`、`command`、编译与打包状态、`selection`（实际筛选条件）及 `environment` 都来自维护者的执行记录。`environment.runtime` 和 `environment.runner` 必须明确；其他影响结果的环境条件也应写入该对象。脚本比较整个环境对象，不仅比较命令字符串。命令选择范围不同但同一检查实际完整执行、输入及环境相同，仍然可以比较。

脚本记录读取文件的 SHA-256 内容摘要，但XML和摘要不能自行证明报告来自所声明的提交。执行命令、报告完整性和提交来源仍需要维护者保存实际证据。未请求打包应如实填写 `not_requested`，不能填写为打包通过。

## 输出与边界

JSON保留原清单、执行记录、文件路径和摘要、每条记录的原始XML、失败或错误原文，以及缺少的对应项。Markdown列出检查状态、比较结论、分仓数量与原失败；完整细节可从同次JSON查询，不需要额外历史数据库。

| 情况 | 输出 |
| --- | --- |
| 对应记录出现 `failure` 或 `error` | 失败；两种原文均保留。 |
| 没有失败但有明确 `skipped` | 跳过，不算恢复通过。 |
| 所有明确对应项都出现，且每条记录均可确认通过 | 通过。 |
| 没有取得记录，或部分参数化记录缺失 | 列出缺口；已有失败仍显示失败，其次保留跳过，其余标为未执行。 |
| 重复记录、冲突状态或无法解释的结果元素 | 保留记录和输入问题，取消受影响检查的版本比较。 |
| 只在候选存在的新定义 | 单列候选新增检查，不归为同一检查的新增失败。 |
| 输入、结果参数或环境缺失、不同 | 展示实际结果并说明无法比较。 |

同一检查有多条展开记录时，失败优先，其次跳过，其次未执行；实际完整通过才计为通过。`coverage_complete` 表示所有明确对应项齐全且没有输入问题。基准没有覆盖时，比较原因包含“基础未覆盖”。

清单未列出的报告记录保存在 `unmapped_records`，不计入保留定义的通过数量。单个报告无法读取、无法解析或汇总数字与逐条记录不符时，所属仓库的版本比较保留不确定性，其他完整报告仍展示实际结果。仓库缺少执行资料时也保留比较缺口。

分仓汇总的 `definition_count` 是本次清单定义数，两侧都按这份清单统计四种状态；`existing_definition_count` 另行表示该侧实际存在的定义数。`expanded_count` 是对应的实际报告记录数，重复记录也保留在这个数字中，不能用来代替定义数。

本工具不证明真实数据库、网络、并发、容器或用户查询可用，也不根据构建成功推断业务验收通过。它提供检查覆盖和结果变化的材料，真实业务验证保留独立证据。
