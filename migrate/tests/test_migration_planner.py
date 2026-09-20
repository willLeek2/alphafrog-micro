import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from migrate.migrate import DatabaseConfig, MigrationPlanner, find_env_file


class MigrationPlannerCurrentTest(unittest.TestCase):

    def test_excludes_start_version_and_older_directories(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            upgrades = root / "migrations" / "upgrades"
            for version in ("v0.6", "v1.0", "v1.1", "v1.2"):
                version_dir = upgrades / version
                version_dir.mkdir(parents=True)
                (version_dir / f"001_{version.replace('.', '_')}.sql").write_text(
                    "SELECT 1;\n", encoding="utf-8"
                )

            manifest = root / "version_manifest.json"
            manifest.write_text(json.dumps({"versions": [
                {"tag": "v0.6", "services": [], "infra": [], "upgrades": ["v0.6/"]},
                {"tag": "v1.0", "services": [], "infra": [], "upgrades": ["v1.0/"]},
                {"tag": "v1.1", "services": [], "infra": [], "upgrades": ["v1.1/"]},
            ]}), encoding="utf-8")

            planner = MigrationPlanner(manifest, root / "migrations")
            migrations, changes = planner.plan_current("v1.0")

            self.assertEqual(
                ["001_v1_1.sql", "001_v1_2.sql"],
                [migration.filename for migration in migrations],
            )
            self.assertEqual(["v1.1", "v1.2"], [change["to"] for change in changes])

    def test_accepts_development_directory_as_start_version(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            upgrades = root / "migrations" / "upgrades"
            for version in ("v1.0", "v1.1", "v1.2", "v1.3"):
                version_dir = upgrades / version
                version_dir.mkdir(parents=True)
                (version_dir / "001_change.sql").write_text("SELECT 1;\n", encoding="utf-8")

            manifest = root / "version_manifest.json"
            manifest.write_text(json.dumps({"versions": [
                {"tag": "v1.0", "services": [], "infra": [], "upgrades": ["v1.0/"]},
                {"tag": "v1.1", "services": [], "infra": [], "upgrades": ["v1.1/"]},
            ]}), encoding="utf-8")

            planner = MigrationPlanner(manifest, root / "migrations")
            migrations, changes = planner.plan_current("v1.2")

            self.assertEqual(["v1.3"], [migration.module for migration in migrations])
            self.assertEqual(["v1.3"], [change["to"] for change in changes])


class ExplicitEnvFileTest(unittest.TestCase):

    def test_requires_absolute_path(self):
        self.assertIsNone(find_env_file("relative/.env"))

    def test_accepts_absolute_env_path_and_parses_env_format(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            env_file = Path(temp_dir) / "beta.env"
            env_file.write_text(
                "AF_DB_MAIN_HOST=db.example\n"
                "AF_DB_MAIN_PORT=5544\n"
                "AF_DB_MAIN_DATABASE=alphafrog_beta\n"
                "AF_DB_MAIN_USER=beta_user\n"
                "AF_DB_MAIN_PASSWORD=secret\n",
                encoding="utf-8",
            )

            resolved = find_env_file(str(env_file))
            config = DatabaseConfig.from_env(resolved)

            self.assertEqual(env_file, resolved)
            self.assertEqual("db.example", config.host)
            self.assertEqual(5544, config.port)
            self.assertEqual("alphafrog_beta", config.name)
            self.assertEqual("beta_user", config.user)


if __name__ == "__main__":
    unittest.main()
