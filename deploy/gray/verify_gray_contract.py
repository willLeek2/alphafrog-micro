#!/usr/bin/env python3
"""Verify the repository-owned AlphaFrog gray-rule v1 contract fixtures."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent
SCHEMA_PATH = ROOT / "gray-rules.schema.json"
EXAMPLE_PATH = ROOT / "gray-rules.example.json"
VECTORS_PATH = ROOT / "gray-bucket-test-vectors.json"

IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
DOCUMENT_KEYS = {"ruleVersion", "bucketSalt", "rules"}
RULE_KEYS = {"ruleId", "enabled", "percent", "userFilter", "owner", "expiresAt"}


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def bucket_details(rule_id: str, bucket_salt: str, user_id: str) -> tuple[str, str, int, int]:
    raw = f"{rule_id}:{bucket_salt}:{user_id}".encode("utf-8")
    digest = hashlib.sha256(raw).digest()
    unsigned64 = int.from_bytes(digest[:8], byteorder="big", signed=False)
    return digest.hex(), digest[:8].hex(), unsigned64, unsigned64 % 100


def parse_rfc3339(value: str) -> datetime:
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("expiresAt must contain an explicit UTC offset")
    return parsed


def verify_schema_identity(schema: dict[str, Any]) -> None:
    assert schema["$schema"] == "https://json-schema.org/draft-07/schema#"
    assert schema["$id"].endswith("gray-rules-v1.schema.json")
    assert set(schema["required"]) == DOCUMENT_KEYS
    assert set(schema["definitions"]["grayRule"]["required"]) == RULE_KEYS


def verify_document(document: dict[str, Any]) -> None:
    assert set(document) == DOCUMENT_KEYS
    assert isinstance(document["ruleVersion"], str) and document["ruleVersion"].strip()
    assert len(document["ruleVersion"]) <= 128
    assert isinstance(document["bucketSalt"], str) and IDENTIFIER_PATTERN.fullmatch(document["bucketSalt"])
    assert isinstance(document["rules"], list) and len(document["rules"]) <= 10000

    seen_rule_ids: set[str] = set()
    for rule in document["rules"]:
        assert isinstance(rule, dict) and set(rule) == RULE_KEYS
        rule_id = rule["ruleId"]
        assert isinstance(rule_id, str) and IDENTIFIER_PATTERN.fullmatch(rule_id)
        assert rule_id not in seen_rule_ids
        seen_rule_ids.add(rule_id)

        assert isinstance(rule["enabled"], bool)
        assert isinstance(rule["percent"], int) and not isinstance(rule["percent"], bool)
        assert 0 <= rule["percent"] <= 100
        assert isinstance(rule["userFilter"], list) and len(rule["userFilter"]) <= 10000
        assert len(rule["userFilter"]) == len(set(rule["userFilter"]))
        assert all(isinstance(user_id, str) and 1 <= len(user_id) <= 256 for user_id in rule["userFilter"])
        assert isinstance(rule["owner"], str) and rule["owner"].strip()
        assert len(rule["owner"]) <= 128
        assert isinstance(rule["expiresAt"], str)
        parse_rfc3339(rule["expiresAt"])


def verify_vectors(fixture: dict[str, Any]) -> None:
    assert fixture["contractVersion"] == "gray-bucket-v1"
    assert fixture["algorithm"]["ruleVersionParticipatesInHash"] is False
    assert fixture["algorithm"]["emptyUserIdMatches"] is False

    vector_ids: set[str] = set()
    for vector in fixture["vectors"]:
        assert vector["id"] not in vector_ids
        vector_ids.add(vector["id"])
        digest_hex, first_eight_hex, unsigned64, bucket = bucket_details(
            vector["ruleId"], vector["bucketSalt"], vector["userId"]
        )
        assert digest_hex == vector["sha256Hex"], vector["id"]
        assert first_eight_hex == vector["firstEightBytesHex"], vector["id"]
        assert str(unsigned64) == vector["unsigned64Decimal"], vector["id"]
        assert bucket == vector["bucket"], vector["id"]

    for case in fixture["wideningCases"]:
        assert 0 <= case["fromPercent"] < case["toPercent"] <= 100
        buckets = {
            user_id: bucket_details(case["ruleId"], case["bucketSalt"], user_id)[3]
            for user_id in case["userIds"]
        }
        from_matches = [
            user_id for user_id in case["userIds"] if buckets[user_id] < case["fromPercent"]
        ]
        to_matches = [
            user_id for user_id in case["userIds"] if buckets[user_id] < case["toPercent"]
        ]
        assert from_matches == case["expectedFromMatches"], case["id"]
        assert to_matches == case["expectedToMatches"], case["id"]
        assert set(from_matches).issubset(to_matches), case["id"]

    for case in fixture["versionIndependenceCases"]:
        assert case["firstRuleVersion"] != case["secondRuleVersion"], case["id"]
        first_bucket = bucket_details(case["ruleId"], case["bucketSalt"], case["userId"])[3]
        second_bucket = bucket_details(case["ruleId"], case["bucketSalt"], case["userId"])[3]
        assert first_bucket == case["expectedBucketForBothVersions"], case["id"]
        assert second_bucket == case["expectedBucketForBothVersions"], case["id"]

    for case in fixture["decisionCases"]:
        if not case["enabled"]:
            actual_match, actual_reason = False, "DISABLED"
        elif case["expired"]:
            actual_match, actual_reason = False, "EXPIRED"
        elif case["userId"] is None or case["userId"] == "":
            actual_match, actual_reason = False, "EMPTY_USER_ID"
        elif case["userId"] in case["userFilter"]:
            actual_match, actual_reason = True, "USER_FILTER"
        else:
            bucket = bucket_details(case["ruleId"], case["bucketSalt"], case["userId"])[3]
            assert bucket == case["expectedBucket"], case["id"]
            actual_match, actual_reason = bucket < case["percent"], "BUCKET"
        assert actual_match is case["expectedMatch"], case["id"]
        assert actual_reason == case["expectedReason"], case["id"]


def main() -> int:
    schema = load_json(SCHEMA_PATH)
    example = load_json(EXAMPLE_PATH)
    fixtures = load_json(VECTORS_PATH)

    verify_schema_identity(schema)
    verify_document(example)
    verify_vectors(fixtures)
    print(
        "gray contract verification passed: "
        f"schema={schema['$id']}, rules={len(example['rules'])}, "
        f"vectors={len(fixtures['vectors'])}, "
        f"wideningCases={len(fixtures['wideningCases'])}, "
        f"versionIndependenceCases={len(fixtures['versionIndependenceCases'])}, "
        f"decisionCases={len(fixtures['decisionCases'])}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
