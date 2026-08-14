"""Tests for app/runtime_image_verify.py (260814 scheduler-03, local-image-id mode).

The verification function takes an injectable docker client, so these tests
use a fake client -- no real Docker daemon, socket or image is touched. The
fake client emulates the docker SDK's ``images.get(ref).id`` surface plus the
failure modes a real socket can produce (image missing, daemon unreachable).
"""

import unittest

from app.runtime_image_verify import ImageVerificationError, verify_local_image_id

GOOD_ID = "sha256:" + "ab" * 32  # 64 lowercase hex chars
OTHER_ID = "sha256:" + "cd" * 32


class FakeImage:
    def __init__(self, image_id: str):
        self.id = image_id


class FakeDockerClient:
    """Docker SDK surface emulation for images.get(ref).id."""

    def __init__(self, *, present_ids=(), tag_aliases=None, socket_error=None):
        # present_ids: full "sha256:..." IDs that exist locally.
        self._present = set(present_ids)
        # tag_aliases: {tag_ref: "sha256:..." id}
        self._tags = dict(tag_aliases or {})
        # socket_error: exception raised for EVERY images.get call (daemon
        # unreachable / API error emulation).
        self._socket_error = socket_error
        self.inspect_calls = []

    def images_get(self, ref):
        self.inspect_calls.append(ref)
        if self._socket_error is not None:
            raise self._socket_error
        if ref in self._tags:
            return FakeImage(self._tags[ref])
        if ref in self._present:
            return FakeImage(ref)
        raise LookupError("image not found: %r" % ref)

    class _Images:
        def __init__(self, outer):
            self._outer = outer

        def get(self, ref):
            return self._outer.images_get(ref)

    @property
    def images(self):
        return self._Images(self)


class ImageNotFound(Exception):
    pass


class DockerException(Exception):
    pass


class VerifyLocalImageIdTest(unittest.TestCase):
    def client(self, **kwargs):
        return FakeDockerClient(**kwargs)

    def test_exact_match_passes_and_returns_frozen_id(self):
        client = self.client(present_ids={GOOD_ID})
        result = verify_local_image_id(GOOD_ID, client=client)
        self.assertEqual(result, GOOD_ID)
        self.assertEqual(client.inspect_calls, [GOOD_ID])

    def test_missing_image_fails_closed(self):
        client = self.client(present_ids=set())
        with self.assertRaises(ImageVerificationError) as ctx:
            verify_local_image_id(GOOD_ID, client=client)
        self.assertIn("refuses to start", str(ctx.exception))

    def test_socket_error_fails_closed(self):
        client = self.client(socket_error=DockerException("daemon down"))
        with self.assertRaises(ImageVerificationError):
            verify_local_image_id(GOOD_ID, client=client)

    def test_empty_image_id_from_docker_fails_closed(self):
        client = self.client(present_ids={GOOD_ID})
        # Corrupt client: returns an image whose id attribute is None.
        client._present = {GOOD_ID}
        orig = client.images_get

        def broken(ref):
            return FakeImage(None)

        client.images_get = broken
        with self.assertRaises(ImageVerificationError):
            verify_local_image_id(GOOD_ID, client=client)

    def test_tag_check_same_id_passes(self):
        client = self.client(
            present_ids={GOOD_ID},
            tag_aliases={"alphafrog-sandbox-runtime:latest": GOOD_ID},
        )
        result = verify_local_image_id(
            GOOD_ID, client=client, tag_check="alphafrog-sandbox-runtime:latest"
        )
        self.assertEqual(result, GOOD_ID)

    def test_tag_check_different_id_fails_closed(self):
        client = self.client(
            present_ids={GOOD_ID},
            tag_aliases={"alphafrog-sandbox-runtime:latest": OTHER_ID},
        )
        with self.assertRaises(ImageVerificationError) as ctx:
            verify_local_image_id(
                GOOD_ID, client=client, tag_check="alphafrog-sandbox-runtime:latest"
            )
        self.assertIn("no longer points", str(ctx.exception))

    def test_tag_check_missing_tag_fails_closed(self):
        client = self.client(present_ids={GOOD_ID})
        with self.assertRaises(ImageVerificationError):
            verify_local_image_id(
                GOOD_ID, client=client, tag_check="alphafrog-sandbox-runtime:latest"
            )

    def test_tag_check_with_socket_error_fails_closed(self):
        client = self.client(socket_error=DockerException("daemon down"))
        with self.assertRaises(ImageVerificationError):
            verify_local_image_id(GOOD_ID, client=client, tag_check="some-tag")

    def test_no_tag_check_skips_tag_resolution(self):
        client = self.client(present_ids={GOOD_ID})
        verify_local_image_id(GOOD_ID, client=client)
        # Only the configured ID is inspected; no extra resolution happens.
        self.assertEqual(client.inspect_calls, [GOOD_ID])


if __name__ == "__main__":
    unittest.main()
