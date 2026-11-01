import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from migrate.migrate import DatabaseConfig, MigrationPlanner, find_env_file


class MigrationPlannerCurrentTest(unittest.TestCase):
    pass

class ExplicitEnvFileTest(unittest.TestCase):
    pass

if __name__ == "__main__":
    unittest.main()
