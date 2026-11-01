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

    def __init__(self, *, present_ids=(), tag_aliases=None, id_aliases=None, socket_error=None):
        # present_ids: full "sha256:..." IDs that exist locally.
        self._present = set(present_ids)
        # tag_aliases: {tag_ref: "sha256:..." id}
        self._tags = dict(tag_aliases or {})
        # id_aliases: {ref: returned id} for emulating a daemon that resolves a
        # reference to a DIFFERENT image than the queried ref.
        self._id_aliases = dict(id_aliases or {})
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
        if ref in self._id_aliases:
            return FakeImage(self._id_aliases[ref])
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

if __name__ == "__main__":
    unittest.main()
