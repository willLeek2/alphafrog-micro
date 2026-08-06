from __future__ import annotations

import hashlib
import unittest

from app.canonical_fingerprint import (
    CanonicalFingerprintMismatch,
    CanonicalSandboxCreateSpec,
    verify_request_fingerprint,
)
from app.models import ExecuteRequest


class CanonicalFingerprintTest(unittest.TestCase):
    def test_matches_java_length_prefix_golden_vector(self) -> None:
        spec = CanonicalSandboxCreateSpec(
            schema_version="sandbox_create_v1",
            operation_id="run-1:call-1:1",
            code_hash="a" * 64,
            immutable_dataset_snapshot_digest="a" * 64,
            resource_class="STANDARD",
            memory_limit_bytes=536_870_912,
            timeout_millis=60_000,
            runtime_environment_version="python-3.12-image-v1",
            libraries_digest="b" * 64,
            sandbox_options_digest="c" * 64,
        )

        self.assertEqual(
            spec.request_fingerprint(),
            "sha256:bb9cae49af46c9abd9c78ffdebd579266b666dc7ae994bd8afdd8b4e8c7e643c",
        )

    def test_utf8_length_prefix_counts_bytes_not_characters(self) -> None:
        spec = CanonicalSandboxCreateSpec(
            schema_version="sandbox_create_v1",
            operation_id="运行:调用:1",
            code_hash="a" * 64,
            immutable_dataset_snapshot_digest="a" * 64,
            resource_class="STANDARD",
            memory_limit_bytes=1,
            timeout_millis=1,
            runtime_environment_version="蟒蛇",
            libraries_digest="b" * 64,
            sandbox_options_digest="c" * 64,
        )

        canonical = spec.canonical_bytes()
        self.assertIn("operationId:15:运行:调用:1\n".encode("utf-8"), canonical)
        self.assertIn("runtimeEnvironmentVersion:6:蟒蛇\n".encode("utf-8"), canonical)

    def test_request_recomputes_outer_fingerprint_and_code_hash(self) -> None:
        code = "print('ok')"
        code_hash = "sha256:" + hashlib.sha256(code.encode("utf-8")).hexdigest()
        spec = CanonicalSandboxCreateSpec(
            schema_version="sandbox_create_v1",
            operation_id="run-1:call-1:1",
            code_hash=code_hash,
            immutable_dataset_snapshot_digest="sha256:" + "a" * 64,
            resource_class="STANDARD",
            memory_limit_bytes=536_870_912,
            timeout_millis=60_000,
            runtime_environment_version="python-3.12-image-v1",
            libraries_digest="sha256:" + "b" * 64,
            sandbox_options_digest="sha256:" + "c" * 64,
        )
        request = ExecuteRequest(
            dataset_id="dataset-1",
            code=code,
            operation_id=spec.operation_id,
            request_fingerprint=spec.request_fingerprint(),
            resource_class=spec.resource_class,
            memory_limit_bytes=spec.memory_limit_bytes,
            timeout_millis=spec.timeout_millis,
            runtime_environment_version=spec.runtime_environment_version,
            canonical_spec_schema_version=spec.schema_version,
            code_hash=spec.code_hash,
            immutable_dataset_snapshot_digest=spec.immutable_dataset_snapshot_digest,
            libraries_digest=spec.libraries_digest,
            sandbox_options_digest=spec.sandbox_options_digest,
        )

        self.assertEqual(verify_request_fingerprint(request), spec.request_fingerprint())
        request.code = "print('changed')"
        with self.assertRaises(CanonicalFingerprintMismatch):
            verify_request_fingerprint(request)
        request.code = code
        request.request_fingerprint = "sha256:" + "f" * 64
        with self.assertRaises(CanonicalFingerprintMismatch):
            verify_request_fingerprint(request)


if __name__ == "__main__":
    unittest.main()
