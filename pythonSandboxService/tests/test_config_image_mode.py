"""Tests for AF_SANDBOX_IMAGE_VERIFY_MODE dispatch in app/config.py (260814 scheduler-03).

Only the image-reference part of load_config is exercised; all other env vars
use the module defaults. No Docker daemon is touched -- these are pure config
validation vectors.
"""

import os
import unittest

from app.config import (
    _VERIFY_MODE_LOCAL_IMAGE_ID,
    _VERIFY_MODE_STRICT_RELEASE,
    is_valid_dev_reference,
    load_config,
    validate_local_image_id,
)

GOOD_LOCAL_ID = "sha256:" + "ab" * 32
GOOD_DIGEST = "registry.local/alphafrog/runtime@sha256:" + "ab" * 32


def env(**overrides):
    for key in list(os.environ):
        if key.startswith("AF_SANDBOX_IMAGE"):
            os.environ.pop(key, None)
    for key, value in overrides.items():
        os.environ[key] = value
    return overrides


class ValidateLocalImageIdTest(unittest.TestCase):
    pass
class EnvAwareTestCase(unittest.TestCase):
    """Save/restore every AF_SANDBOX_IMAGE* env key around each test so this
    module never permanently mutates the process environment (unittest
    discover runs all modules in ONE process; leaking env breaks later
    modules that import app.main / call load_config)."""

    def setUp(self):
        self._saved = {
            key: os.environ.pop(key)
            for key in list(os.environ)
            if key.startswith("AF_SANDBOX_IMAGE")
        }

    def tearDown(self):
        for key in list(os.environ):
            if key.startswith("AF_SANDBOX_IMAGE"):
                os.environ.pop(key, None)
        os.environ.update(self._saved)


class LoadConfigModeDispatchTest(EnvAwareTestCase):
    pass

if __name__ == "__main__":
    unittest.main()
