from __future__ import annotations

import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

llm_sandbox = types.ModuleType("llm_sandbox")
llm_sandbox.SandboxSession = object
llm_sandbox_exceptions = types.ModuleType("llm_sandbox.exceptions")
llm_sandbox_exceptions.SandboxTimeoutError = TimeoutError
sys.modules.setdefault("llm_sandbox", llm_sandbox)
sys.modules.setdefault("llm_sandbox.exceptions", llm_sandbox_exceptions)

from app.config import (  # noqa: E402
    is_digest_reference,
    is_valid_dev_reference,
    load_config,
    validate_sandbox_image,
)
from app.sandbox_runner import (  # noqa: E402
    _atomic_copy_text_to_runtime,
    _build_agent_run_metadata_documents,
    create_sandbox_session,
)

# Shared accept/reject vectors (single source of truth pinning identical
# semantics at every digest-validation entry point, Spec §12 hardening).
sys.path.insert(0, str(Path(__file__).resolve().parent))
from digest_reference_vectors import (  # noqa: E402
    ACCEPT_REFS,
    HEX64,
    MALFORMED_UNDER_DEV_REFS,
    REPO,
    REJECT_REFS,
    VALID_DEV_REFERENCES,
)


class DataIntenseRuntimeTest(unittest.TestCase):
    def test_default_runtime_is_one_task_per_container_with_two_memory_classes(self) -> None:
        # Spec §12: AF_SANDBOX_IMAGE has no implicit default; a digest
        # reference is always accepted (no dev-allow switch needed).
        with patch.dict(
            "os.environ",
            {"AF_SANDBOX_IMAGE": "registry.local/alphafrog/runtime@sha256:" + "ab" * 32,
             "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release"},
            clear=True,
        ):
            config = load_config()

        self.assertFalse(config.pool_enabled)
        self.assertEqual(config.container_max_concurrency, 1)
        self.assertEqual(config.standard_memory_limit_bytes, 512 * 1024 * 1024)
        self.assertEqual(config.heavy_memory_limit_bytes, 1536 * 1024 * 1024)
        self.assertEqual(config.task_store_path, Path("/data/sandbox_tasks/state.json"))

    def test_metadata_materialization_is_versioned_path_free_and_partial_safe(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            csv_path = root / "prices.csv"
            csv_path.write_text("ts_code,trade_date,close\n000001.SZ,20240101,10.0\n", encoding="utf-8")
            csv_path.with_suffix(".meta.json").write_text(json.dumps({
                "rowCount": 1,
                "columns": ["ts_code", "trade_date", "close"],
                "recommendedUsecols": ["ts_code", "trade_date", "close"],
                "recommendedDtype": {"trade_date": "Int64", "close": "float64"},
                "readProfiles": {"price_volume": ["ts_code", "trade_date", "close"]},
            }), encoding="utf-8")
            dataset_csv = (
                "agent_run_dataset_id,dataset_file_path,from_ts_code,source_path\n"
                f"7,/__AF_INPUT__/_run_dataset_7/prices.csv,000001.SZ,{csv_path}\n"
            )
            manifest_csv = (
                "agent_run_manifest_id,manifest_file_path,related_dataset_ids,source_path\n"
                "3,NONE,7,\n"
            )

            dataset_meta, manifest_meta = _build_agent_run_metadata_documents(dataset_csv, manifest_csv)

        self.assertEqual(dataset_meta["schema_version"], "agent_run_dataset_meta_v1")
        self.assertEqual(dataset_meta["datasets"]["7"]["metadataStatus"], "complete")
        self.assertEqual(manifest_meta["schema_version"], "agent_run_manifest_meta_v1")
        self.assertEqual(manifest_meta["manifests"]["3"]["memberNumbers"], [7])
        encoded = json.dumps([dataset_meta, manifest_meta])
        self.assertNotIn(str(csv_path), encoded)
        self.assertNotIn("sourcePath", encoded)
        self.assertNotIn("originalId", encoded)

    def test_session_uses_per_task_heavy_memory_hard_limit(self) -> None:
        captured = {}

        class FakeSession:
            def __init__(self, **kwargs):
                captured.update(kwargs)

            def open(self):
                return None

        # Spec §12: AF_SANDBOX_IMAGE has no implicit default; a digest
        # reference is always accepted (no dev-allow switch needed).
        with patch.dict(
            "os.environ",
            {"AF_SANDBOX_IMAGE": "registry.local/alphafrog/runtime@sha256:" + "ab" * 32,
             "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release"},
            clear=True,
        ):
            config = load_config()
        with patch("app.sandbox_runner.SandboxSession", FakeSession):
            create_sandbox_session(
                config,
                execution_timeout=60,
                memory_limit_bytes=config.heavy_memory_limit_bytes,
            )

        self.assertEqual(captured["runtime_configs"]["mem_limit"], 1536 * 1024 * 1024)
        self.assertEqual(captured["runtime_configs"]["memswap_limit"], 1536 * 1024 * 1024)

    def test_public_metadata_write_uses_temp_file_then_atomic_rename(self) -> None:
        class Output:
            exit_code = 0
            stdout = ""
            stderr = ""

        class FakeSession:
            def __init__(self):
                self.destinations = []
                self.commands = []

            def copy_to_runtime(self, _source: str, dest_path: str):
                self.destinations.append(dest_path)

            def execute_command(self, command: str):
                self.commands.append(command)
                return Output()

        session = FakeSession()
        _atomic_copy_text_to_runtime(
            session,
            '{"schema_version":"agent_run_dataset_meta_v1"}',
            "/sandbox/paths_dataset_meta.json",
        )

        self.assertEqual(session.destinations, ["/sandbox/paths_dataset_meta.json.tmp"])
        self.assertIn(
            "mv /sandbox/paths_dataset_meta.json.tmp /sandbox/paths_dataset_meta.json",
            session.commands,
        )


class ConfigDigestReferenceSharedVectorsTest(unittest.TestCase):
    """app/config.py must implement the SAME anchored, lowercase-only digest
    semantics as scripts/build_runtime_manifest.py and the shell entry points
    (deploy_latest.sh / docker_build.sh), pinned by the shared vectors in
    tests/digest_reference_vectors.py (Spec §12 hardening)."""

    def test_shared_accept_vectors_fullmatch(self) -> None:
        for ref in ACCEPT_REFS:
            self.assertTrue(is_digest_reference(ref), f"accept vector rejected: {ref!r}")
            validate_sandbox_image(ref, allow_dev_tag=False)

    def test_shared_reject_vectors_fullmatch(self) -> None:
        for value, reason in REJECT_REFS:
            self.assertFalse(
                is_digest_reference(value),
                f"reject vector accepted ({reason}): {value!r}",
            )

    def test_shared_reject_vectors_fail_validation_without_dev_switch(self) -> None:
        for value, reason in REJECT_REFS:
            with self.assertRaises(ValueError, msg=f"not rejected ({reason}): {value!r}"):
                validate_sandbox_image(value, allow_dev_tag=False)


class ConfigImagePolicyTest(unittest.TestCase):
    """load_config() env behavior: no implicit default, explicit dev switch."""

    def test_load_config_rejects_uppercase_digest_reference(self) -> None:
        with patch.dict(
            "os.environ",
            {"AF_SANDBOX_IMAGE": f"{REPO}@sha256:{HEX64.upper()}",
             "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release"},
            clear=True,
        ):
            with self.assertRaises(ValueError):
                load_config()

    def test_load_config_rejects_trailing_hex_after_digest(self) -> None:
        with patch.dict(
            "os.environ",
            {"AF_SANDBOX_IMAGE": f"{REPO}@sha256:{HEX64}a",
             "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release"},
            clear=True,
        ):
            with self.assertRaises(ValueError):
                load_config()

    def test_load_config_rejects_undigested_ref_without_dev_switch(self) -> None:
        with patch.dict(
            "os.environ",
            {"AF_SANDBOX_IMAGE": "alphafrog-sandbox-runtime:latest",
             "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release"},
            clear=True,
        ):
            with self.assertRaises(ValueError):
                load_config()

    def test_load_config_accepts_undigested_ref_only_with_explicit_dev_switch(self) -> None:
        for switch_value in ("true", "1"):
            with patch.dict(
                "os.environ",
                {
                    "AF_SANDBOX_IMAGE": "alphafrog-sandbox-runtime:latest",
                    "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG": switch_value,
                    "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release",
                },
                clear=True,
            ):
                config = load_config()
            self.assertEqual(config.sandbox_image, "alphafrog-sandbox-runtime:latest")

    def test_load_config_rejects_implicit_dev_switch_values(self) -> None:
        # Only exact true/1 count; anything else stays fail-closed.
        for switch_value in ("yes", "on", "TRUEISH", "0", ""):
            with patch.dict(
                "os.environ",
                {
                    "AF_SANDBOX_IMAGE": "alphafrog-sandbox-runtime:latest",
                    "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG": switch_value,
                    "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release",
                },
                clear=True,
            ):
                with self.assertRaises(ValueError, msg=f"switch {switch_value!r} must not allow a bare tag"):
                    load_config()

    def test_load_config_missing_image_fails_without_implicit_default(self) -> None:
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaises(ValueError):
                load_config()


class ConfigDevReferenceSharedVectorsTest(unittest.TestCase):
    """R2-4 at the config surface: the explicit dev-allow switch admits ONLY
    syntactically VALID bare tag/references (shared vector set); the malformed
    classes (whitespace/control chars, wrong digest lengths, uppercase
    digests, digest-shaped-but-invalid, garbage) are rejected with the switch
    ON as well as OFF -- the switch is NOT a blanket bypass."""

    def test_valid_dev_references_accepted_only_with_switch(self) -> None:
        for ref in VALID_DEV_REFERENCES:
            self.assertTrue(
                is_valid_dev_reference(ref), f"valid dev reference rejected: {ref!r}"
            )
            validate_sandbox_image(ref, allow_dev_tag=True)
            with self.assertRaises(
                ValueError, msg=f"bare reference admitted WITHOUT the switch: {ref!r}"
            ):
                validate_sandbox_image(ref, allow_dev_tag=False)

    def test_malformed_under_dev_rejected_with_switch_on_and_off(self) -> None:
        for value, reason in MALFORMED_UNDER_DEV_REFS:
            self.assertFalse(
                is_valid_dev_reference(value),
                f"malformed dev reference passed the grammar ({reason}): {value!r}",
            )
            for switch in (True, False):
                with self.assertRaises(
                    ValueError,
                    msg=f"not rejected ({reason}), allow_dev_tag={switch}: {value!r}",
                ):
                    validate_sandbox_image(value, allow_dev_tag=switch)

    def test_digest_shaped_reject_refs_rejected_even_under_dev_switch(self) -> None:
        # Anything '@'-bearing must satisfy the anchored lowercase digest
        # grammar; malformed digest-shaped values never ride the dev switch.
        for value, reason in REJECT_REFS:
            if "@" not in value:
                continue
            with self.assertRaises(
                ValueError,
                msg=f"digest-shaped value admitted under dev switch ({reason}): {value!r}",
            ):
                validate_sandbox_image(value, allow_dev_tag=True)

    def test_load_config_accepts_valid_dev_reference_with_switch(self) -> None:
        for ref in VALID_DEV_REFERENCES:
            with patch.dict(
                "os.environ",
                {"AF_SANDBOX_IMAGE": ref,
             "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG": "true",
             "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release"},
                clear=True,
            ):
                config = load_config()
            self.assertEqual(config.sandbox_image, ref)

    def test_load_config_rejects_malformed_ref_even_with_dev_switch(self) -> None:
        for value, reason in MALFORMED_UNDER_DEV_REFS:
            if "\x00" in value:
                continue  # env-var surface cannot carry NUL bytes
            with patch.dict(
                "os.environ",
                {
                    "AF_SANDBOX_IMAGE": value,
                    "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG": "true",
                },
                clear=True,
            ):
                with self.assertRaises(
                    ValueError, msg=f"malformed ref admitted by load_config ({reason}): {value!r}"
                ):
                    load_config()

    def test_load_config_rejects_empty_ref_even_with_dev_switch(self) -> None:
        with patch.dict(
            "os.environ",
            {"AF_SANDBOX_IMAGE": "", "AF_SANDBOX_IMAGE_ALLOW_DEV_TAG": "true",
             "AF_SANDBOX_IMAGE_VERIFY_MODE": "strict-release"},
            clear=True,
        ):
            with self.assertRaises(ValueError):
                load_config()


if __name__ == "__main__":
    unittest.main()
