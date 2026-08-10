#!/usr/bin/env python3
"""D15 release verifier — minimal oracle implementation.

v14 design: 3 CLI subcommands (verify-mapping, verify-library-set-binding, verify-hard-target-binding).
Python 3.7+ compatible.
"""
import argparse
import json
import re
import sys
from typing import Optional, Tuple

_SHA256_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
_DIGEST_FIELDS = ("baseImageDigest", "lockDigest", "librarySetDigest",
                  "sbomDigest", "methodSpecIndexDigest")


def load_mapping(path):
    # type: (str) -> Tuple[str, Optional[dict]]
    try:
        with open(path, "r", encoding="utf-8") as fh:
            mapping = json.load(fh)
    except Exception:
        return ('unparseable', None)
    if not isinstance(mapping, dict):
        return ('no-images', None)
    images = mapping.get('images')
    if not isinstance(images, dict) or not images:
        return ('no-images', None)
    return ('ok', mapping)


def _find_candidates(images, chosen_ref, inspected_id):
    matches = []
    for key, entry in images.items():
        if not isinstance(entry, dict):
            continue
        if key == inspected_id:
            matches.append((key, entry))
            continue
        image_ref = entry.get("imageRef")
        if isinstance(image_ref, str) and image_ref == chosen_ref:
            matches.append((key, entry))
    return matches


def _release_integrity_ok(entry):
    if not isinstance(entry, dict):
        return False
    bad = [name for name in _DIGEST_FIELDS
           if not (isinstance(entry.get(name), str) and _SHA256_RE.match(entry.get(name)))]
    if bad:
        return False
    return entry.get('releasable') is True


def verify_mapping(mapping_path, chosen_ref, inspected_id):
    v, parsed = load_mapping(mapping_path)
    if v == 'unparseable':
        return 'unparseable'
    if v == 'no-images':
        return 'no-images'
    images = parsed['images']
    matches = _find_candidates(images, chosen_ref, inspected_id)
    if len(matches) == 0:
        return 'no-match'
    if len(matches) > 1:
        return 'multiple-match'
    key, entry = matches[0]
    if key != inspected_id:
        return 'target-mismatch'
    if not _release_integrity_ok(entry):
        return 'not-releasable'
    return 'ok'


def _read_iidfile(iidfile_path):
    try:
        with open(iidfile_path, "r", encoding="utf-8") as fh:
            build_image_id = fh.read().strip()
    except Exception:
        return ('iidfile-missing', None)
    if not (isinstance(build_image_id, str) and _SHA256_RE.match(build_image_id)):
        return ('iidfile-malformed', None)
    return ('ok', build_image_id)


def verify_hard_target_binding(mapping_path, inspected_id, library_set_digest, iidfile_path):
    v, build_image_id = _read_iidfile(iidfile_path)
    if v != 'ok':
        return v
    if build_image_id != inspected_id:
        return 'iidfile-mismatch'
    v, parsed = load_mapping(mapping_path)
    if v != 'ok':
        return v
    images = parsed['images']
    if inspected_id not in images:
        return 'no-entry'
    entry = images[inspected_id]
    if not isinstance(entry, dict):
        return 'not-an-object'
    lib_digest = entry.get('librarySetDigest')
    if not (isinstance(lib_digest, str) and _SHA256_RE.match(lib_digest)):
        return 'librarySetDigest-invalid'
    if lib_digest != library_set_digest:
        return 'mismatch'
    return 'ok'


def verify_library_set_binding(mapping_path, inspected_id, library_set_digest):
    v, parsed = load_mapping(mapping_path)
    if v == 'unparseable':
        return 'unparseable'
    if v == 'no-images':
        return 'no-images'
    images = parsed['images']
    if inspected_id not in images:
        return 'no-entry'
    entry = images[inspected_id]
    if not isinstance(entry, dict):
        return 'not-an-object'
    lib_digest = entry.get('librarySetDigest')
    if not (isinstance(lib_digest, str) and _SHA256_RE.match(lib_digest)):
        return 'librarySetDigest-invalid'
    if lib_digest != library_set_digest:
        return 'mismatch'
    if not _release_integrity_ok(entry):
        return 'release-incomplete'
    return 'ok'


def _main():
    parser = argparse.ArgumentParser(prog='d15_release_verify.py')
    sub = parser.add_subparsers(dest='cmd', required=True)

    p1 = sub.add_parser('verify-mapping')
    p1.add_argument('--mapping', required=True)
    p1.add_argument('--chosen-ref', required=True)
    p1.add_argument('--inspected-id', required=True)

    p2 = sub.add_parser('verify-library-set-binding')
    p2.add_argument('--mapping', required=True)
    p2.add_argument('--inspected-id', required=True)
    p2.add_argument('--library-set-digest', required=True)

    p3 = sub.add_parser('verify-hard-target-binding')
    p3.add_argument('--mapping', required=True)
    p3.add_argument('--inspected-id', required=True)
    p3.add_argument('--library-set-digest', required=True)
    p3.add_argument('--iidfile', required=True)

    args = parser.parse_args()
    if args.cmd == 'verify-mapping':
        print(verify_mapping(args.mapping, args.chosen_ref, args.inspected_id))
        sys.exit(0)
    elif args.cmd == 'verify-library-set-binding':
        print(verify_library_set_binding(args.mapping, args.inspected_id, args.library_set_digest))
        sys.exit(0)
    elif args.cmd == 'verify-hard-target-binding':
        print(verify_hard_target_binding(args.mapping, args.inspected_id, args.library_set_digest, args.iidfile))
        sys.exit(0)


if __name__ == '__main__':
    _main()
