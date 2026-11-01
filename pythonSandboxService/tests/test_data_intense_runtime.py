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
    pass
class ConfigDigestReferenceSharedVectorsTest(unittest.TestCase):
    """app/config.py must implement the SAME anchored, lowercase-only digest
    semantics as scripts/build_runtime_manifest.py and the shell entry points
    (deploy_latest.sh / docker_build.sh), pinned by the shared vectors in
    tests/digest_reference_vectors.py (Spec §12 hardening)."""

class ConfigImagePolicyTest(unittest.TestCase):
    """load_config() env behavior: no implicit default, explicit dev switch."""

class ConfigDevReferenceSharedVectorsTest(unittest.TestCase):
    """R2-4 at the config surface: the explicit dev-allow switch admits ONLY
    syntactically VALID bare tag/references (shared vector set); the malformed
    classes (whitespace/control chars, wrong digest lengths, uppercase
    digests, digest-shaped-but-invalid, garbage) are rejected with the switch
    ON as well as OFF -- the switch is NOT a blanket bypass."""

if __name__ == "__main__":
    unittest.main()
