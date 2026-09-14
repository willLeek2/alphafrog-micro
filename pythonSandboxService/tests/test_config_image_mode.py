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
    def test_valid_local_id_accepted(self):
        validate_local_image_id(GOOD_LOCAL_ID)

    def test_uppercase_hex_rejected(self):
        with self.assertRaises(ValueError):
            validate_local_image_id("sha256:" + "AB" * 32)

    def test_short_hex_rejected(self):
        with self.assertRaises(ValueError):
            validate_local_image_id("sha256:" + "ab" * 63)

    def test_repo_digest_rejected(self):
        with self.assertRaises(ValueError) as ctx:
            validate_local_image_id(GOOD_DIGEST)
        self.assertIn("strict-release", str(ctx.exception))

    def test_bare_tag_rejected(self):
        with self.assertRaises(ValueError):
            validate_local_image_id("alphafrog-sandbox-runtime:latest")

    def test_empty_and_blank_rejected(self):
        for value in ("", "   ", None):
            with self.assertRaises(ValueError):
                validate_local_image_id(value)


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

    def test_default_mode_is_local_image_id(self):
        env(AF_SANDBOX_IMAGE=GOOD_LOCAL_ID)
        config = load_config()
        self.assertEqual(config.verify_mode, _VERIFY_MODE_LOCAL_IMAGE_ID)
        self.assertEqual(config.sandbox_image, GOOD_LOCAL_ID)
        self.assertEqual(config.image_tag_check, "")

    def test_local_mode_rejects_tag(self):
        env(AF_SANDBOX_IMAGE="alphafrog-sandbox-runtime:latest")
        with self.assertRaises(ValueError):
            load_config()

    def test_local_mode_rejects_digest_reference(self):
        env(AF_SANDBOX_IMAGE=GOOD_DIGEST)
        with self.assertRaises(ValueError):
            load_config()

    def test_local_mode_accepts_tag_check(self):
        env(
            AF_SANDBOX_IMAGE=GOOD_LOCAL_ID,
            AF_SANDBOX_IMAGE_TAG_CHECK="alphafrog-sandbox-runtime:latest",
        )
        config = load_config()
        self.assertEqual(
            config.image_tag_check, "alphafrog-sandbox-runtime:latest"
        )

    def test_local_mode_rejects_malformed_tag_check(self):
        env(
            AF_SANDBOX_IMAGE=GOOD_LOCAL_ID,
            AF_SANDBOX_IMAGE_TAG_CHECK="not a valid tag!!!",
        )
        with self.assertRaises(ValueError):
            load_config()

    def test_strict_release_accepts_digest_reference(self):
        env(
            AF_SANDBOX_IMAGE_VERIFY_MODE=_VERIFY_MODE_STRICT_RELEASE,
            AF_SANDBOX_IMAGE=GOOD_DIGEST,
        )
        config = load_config()
        self.assertEqual(config.verify_mode, _VERIFY_MODE_STRICT_RELEASE)
        self.assertEqual(config.sandbox_image, GOOD_DIGEST)

    def test_strict_release_keeps_dev_tag_switch(self):
        env(
            AF_SANDBOX_IMAGE_VERIFY_MODE=_VERIFY_MODE_STRICT_RELEASE,
            AF_SANDBOX_IMAGE="alphafrog-sandbox-runtime:latest",
            AF_SANDBOX_IMAGE_ALLOW_DEV_TAG="true",
        )
        config = load_config()
        self.assertEqual(config.sandbox_image, "alphafrog-sandbox-runtime:latest")

    def test_strict_release_rejects_tag_without_switch(self):
        env(
            AF_SANDBOX_IMAGE_VERIFY_MODE=_VERIFY_MODE_STRICT_RELEASE,
            AF_SANDBOX_IMAGE="alphafrog-sandbox-runtime:latest",
        )
        with self.assertRaises(ValueError):
            load_config()

    def test_strict_release_rejects_tag_check(self):
        env(
            AF_SANDBOX_IMAGE_VERIFY_MODE=_VERIFY_MODE_STRICT_RELEASE,
            AF_SANDBOX_IMAGE=GOOD_DIGEST,
            AF_SANDBOX_IMAGE_TAG_CHECK="alphafrog-sandbox-runtime:latest",
        )
        with self.assertRaises(ValueError) as ctx:
            load_config()
        self.assertIn("only supported in", str(ctx.exception))

    def test_unknown_mode_rejected(self):
        env(AF_SANDBOX_IMAGE_VERIFY_MODE="bogus", AF_SANDBOX_IMAGE=GOOD_LOCAL_ID)
        with self.assertRaises(ValueError):
            load_config()

    def test_missing_image_still_rejected(self):
        env(AF_SANDBOX_IMAGE="")
        with self.assertRaises(ValueError):
            load_config()

    def test_tag_check_must_be_valid_reference_shape(self):
        self.assertTrue(is_valid_dev_reference("alphafrog-sandbox-runtime:latest"))
        self.assertFalse(is_valid_dev_reference("UPPER/case:tag"))


if __name__ == "__main__":
    unittest.main()
