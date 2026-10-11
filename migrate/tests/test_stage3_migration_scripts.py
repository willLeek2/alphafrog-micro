"""阶段三数据合同脚本在迁移框架里的可发现性与可重复执行。

这份测试不连数据库：它盯的是「脚本还没被应用就发现它有问题」的那一类错误——文件名不符合框架规则、
序号不连续、脚本里自己开了事务、或者规划器根本没把它排进本次计划。
"""

import json
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from migrate.migrate import Migration, MigrationPlanner

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "migrate" / "migrations"
MANIFEST_PATH = REPO_ROOT / "migrate" / "version_manifest.json"
STAGE3_SCRIPT = "007_agent_run_dag_wait_group.sql"
STAGE3_VERSION_DIR = "v1.5"


class Stage3ScriptDiscoveryTest(unittest.TestCase):

    def test_filename_matches_framework_pattern(self):
        parsed = Migration.from_file(MIGRATIONS_DIR / "upgrades" / STAGE3_VERSION_DIR / STAGE3_SCRIPT)
        self.assertIsNotNone(parsed, "文件名必须符合框架的 序号_描述.sql 规则")
        self.assertEqual(STAGE3_VERSION_DIR, parsed.module)
        self.assertTrue(parsed.description)

    def test_sequence_numbers_in_version_dir_are_contiguous(self):
        numbers = sorted(
            int(re.match(r"^(\d+)_", path.name).group(1))
            for path in (MIGRATIONS_DIR / "upgrades" / STAGE3_VERSION_DIR).glob("[0-9]*_*.sql")
            if re.match(r"^(\d+)_", path.name)
        )
        self.assertEqual(
            list(range(1, len(numbers) + 1)),
            numbers,
            "同一个版本目录里的序号必须从 1 开始连续，插入新脚本只能顺延最大号",
        )

    def test_planner_includes_stage3_script_for_current_versions(self):
        planner = MigrationPlanner(MANIFEST_PATH, MIGRATIONS_DIR)
        migrations, _ = planner.plan_current("v1.1")
        filenames = [migration.filename for migration in migrations]
        self.assertIn(
            STAGE3_SCRIPT,
            filenames,
            "规划器必须把阶段三脚本排进计划，否则它在真实环境里不会被执行",
        )
        stage3 = next(migration for migration in migrations if migration.filename == STAGE3_SCRIPT)
        self.assertEqual(STAGE3_VERSION_DIR, stage3.module)
        self.assertTrue(stage3.checksum, "校验和要在规划阶段就能算出来")

    def test_script_does_not_control_transactions_itself(self):
        text = (MIGRATIONS_DIR / "upgrades" / STAGE3_VERSION_DIR / STAGE3_SCRIPT).read_text(
            encoding="utf-8")
        for token in ("BEGIN;", "COMMIT;", "ROLLBACK;", "\\"):
            self.assertNotIn(
                token,
                text,
                f"迁移管理器按整份文件执行并且自己管事务，脚本里不允许出现 {token!r}",
            )

    def test_manifest_still_ends_before_stage3_version_dir(self):
        # 阶段三沿用当前的开发中版本目录。开新版本号由负责人决定，脚本自己不能造版本号。
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        tags = [version["tag"] for version in manifest["versions"]]
        self.assertNotIn(
            STAGE3_VERSION_DIR,
            tags,
            "版本号由负责人开，脚本不许自己往清单里加版本",
        )


if __name__ == "__main__":
    unittest.main()
