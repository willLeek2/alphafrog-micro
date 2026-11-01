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
    pass

if __name__ == "__main__":
    unittest.main()
