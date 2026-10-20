#!/usr/bin/env python3
"""Generate shared wire-format test vectors for the Kotlin and ESP32-C++ sides.

This script is the *ground truth* generator. It is deliberately written in a
different language and a different code path than the shipped implementations so
that a coding mistake on one side cannot silently validate itself.

It uses only the Python standard library plus `cryptography` (HKDF / AES-GCM).
No cryptographic primitive is implemented here.

Run:
    python protocol/tools/gen_vectors.py            # writes ../vectors/*.json
    python protocol/tools/gen_vectors.py --check     # regenerates in memory and
                                                     # compares with files on disk

Layout is documented in protocol/schema/wire-format.md and
docs/protocol-implementation-notes.md.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import pathlib
import sys
from typing import Any

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

WIRE_VERSION = 2
FRAGMENT_HEADER_LEN = 8
ENVELOPE_HEADER_LEN = 20
GCM_TAG_LEN = 16

LIMIT_NORMAL = 1024
LIMIT_HANDSHAKE = 512
LIMIT_CONTROL = 128

TRANSCRIPT_MAGIC = b"E32PHN2H"
LABEL_BOARD = b"esp32phone/v2/proof/board"
LABEL_APP = b"esp32phone/v2/proof/app"
INFO_SESSION = b"esp32phone/v2/session"
KEY_INFOS = {
    "app_normal": b"esp32phone/v2/key/app-normal",
    "board_normal": b"esp32phone/v2/key/board-normal",
    "app_control": b"esp32phone/v2/key/app-control",
    "board_control": b"esp32phone/v2/key/board-control",
}
NONCE_INFOS = {
    "app_normal": b"esp32phone/v2/nonce/app-normal",
    "board_normal": b"esp32phone/v2/nonce/board-normal",
    "app_control": b"esp32phone/v2/nonce/app-control",
    "board_control": b"esp32phone/v2/nonce/board-control",
}

MESSAGE_TYPES = {
    "client_hello": 1,
    "board_hello": 2,
    "app_auth": 3,
    "auth_ok": 4,
    "capabilities_request": 10,
    "capabilities": 11,
    "heartbeat": 20,
    "heartbeat_ack": 21,
    "action": 30,
    "accepted": 31,
    "started": 32,
    "done": 33,
    "rejected": 34,
    "cancelled": 35,
    "error": 36,
    "stop": 40,
    "stopped": 41,
    "query_result_request": 50,
    "query_result": 51,
    "status": 60,
}

ERROR_CODES = {
    "OK": 0,
    "BAD_VERSION": 1,
    "BAD_FRAME": 2,
    "FRAGMENT_TIMEOUT": 3,
    "AUTH_FAILED": 4,
    "NOT_AUTHENTICATED": 5,
    "REPLAY_DETECTED": 6,
    "UNKNOWN_TYPE": 7,
    "INVALID_JSON": 8,
    "INVALID_ACTION": 9,
    "ARG_OUT_OF_RANGE": 10,
    "BUSY": 11,
    "COMMAND_CONFLICT": 12,
    "COMMAND_STALE": 13,
    "USB_UNAVAILABLE": 14,
    "CALIBRATION_REQUIRED": 15,
    "RELEASE_FAILED": 16,
    "BUDGET_EXCEEDED": 17,
    "NOT_CONTROLLER": 18,
    "RATE_LIMITED": 19,
    "TOO_LARGE": 20,
}


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


def transcript_bytes(board_id: str, client_nonce: bytes, board_nonce: bytes, features: int) -> bytes:
    ident = board_id.encode("utf-8")
    if not 1 <= len(ident) <= 32:
        raise ValueError("board_id must be 1..32 UTF-8 bytes")
    return (
        TRANSCRIPT_MAGIC
        + bytes([WIRE_VERSION, len(ident)])
        + ident
        + client_nonce
        + board_nonce
        + features.to_bytes(4, "little")
    )


def board_proof(secret: bytes, transcript: bytes) -> bytes:
    return hmac.new(secret, LABEL_BOARD + transcript, hashlib.sha256).digest()


def app_proof(secret: bytes, transcript: bytes, bproof: bytes) -> bytes:
    return hmac.new(secret, LABEL_APP + transcript + bproof, hashlib.sha256).digest()


def derive_session(secret: bytes, transcript: bytes) -> dict[str, Any]:
    salt = hashlib.sha256(transcript).digest()
    out: dict[str, Any] = {
        "session_id_hex": hkdf(secret, salt, INFO_SESSION, 8).hex(),
        "keys": {},
        "nonce_prefixes": {},
    }
    for name, info in KEY_INFOS.items():
        out["keys"][name] = hkdf(secret, salt, info, 32).hex()
    for name, info in NONCE_INFOS.items():
        out["nonce_prefixes"][name] = hkdf(secret, salt, info, 8).hex()
    return out


def fragments(payload: bytes, transfer_id: int, flags: int, mtu: int) -> list[bytes]:
    content = mtu - 3 - FRAGMENT_HEADER_LEN
    if content <= 0:
        raise ValueError("mtu too small")
    count = max(1, (len(payload) + content - 1) // content)
    if count > 255:
        raise ValueError("too many fragments")
    out = []
    for index in range(count):
        chunk = payload[index * content : (index + 1) * content]
        header = (
            bytes([WIRE_VERSION, flags])
            + transfer_id.to_bytes(2, "little")
            + bytes([index, count])
            + len(payload).to_bytes(2, "little")
        )
        out.append(header + chunk)
    return out


def envelope_header(
    message_type: int, session_id: bytes, sequence: int, command_id: int, payload_len: int
) -> bytes:
    return (
        bytes([WIRE_VERSION, message_type])
        + session_id
        + sequence.to_bytes(4, "little")
        + command_id.to_bytes(4, "little")
        + payload_len.to_bytes(2, "little")
    )


def seal(
    key: bytes,
    nonce_prefix: bytes,
    message_type: int,
    session_id: bytes,
    sequence: int,
    command_id: int,
    payload: bytes,
) -> bytes:
    header = envelope_header(message_type, session_id, sequence, command_id, len(payload))
    nonce = nonce_prefix + sequence.to_bytes(4, "little")
    sealed = AESGCM(key).encrypt(nonce, payload, header)
    return header + sealed


def b64(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


# ---------------------------------------------------------------------------
# vector documents
# ---------------------------------------------------------------------------

SCHEMA = {
    "name": "schema_constants",
    "wire_version": WIRE_VERSION,
    "fragment_header_len": FRAGMENT_HEADER_LEN,
    "envelope_header_len": ENVELOPE_HEADER_LEN,
    "gcm_tag_len": GCM_TAG_LEN,
    "flags": {"protected": 1, "plaintext": 0},
    "limits": {
        "normal": LIMIT_NORMAL,
        "handshake": LIMIT_HANDSHAKE,
        "control": LIMIT_CONTROL,
    },
    "uuid": {
        "service": "50e00000-7cc6-47b9-8e6f-6f59d90e0000",
        "info": "50e00000-7cc6-47b9-8e6f-6f59d90e0001",
        "command": "50e00000-7cc6-47b9-8e6f-6f59d90e0002",
        "event": "50e00000-7cc6-47b9-8e6f-6f59d90e0003",
        "control": "50e00000-7cc6-47b9-8e6f-6f59d90e0004",
    },
    "transcript_magic_hex": TRANSCRIPT_MAGIC.hex(),
    "proof_labels": {"board": LABEL_BOARD.decode(), "app": LABEL_APP.decode()},
    "hkdf": {
        "session_info": INFO_SESSION.decode(),
        "key_info": {k: v.decode() for k, v in KEY_INFOS.items()},
        "nonce_info": {k: v.decode() for k, v in NONCE_INFOS.items()},
    },
    "message_types": MESSAGE_TYPES,
    "error_codes": ERROR_CODES,
}


def build_fragmentation() -> dict[str, Any]:
    cases = []
    specs = [
        ("mtu23_12B_single", 23, b"\xaa" * 12, 1),
        ("mtu23_30B_split", 23, bytes(range(30)), 7),
        ("mtu23_129B", 23, bytes([0x5A]) * 129, 9),
        ("mtu185_300B", 185, bytes([0x11]) * 300, 2),
        ("mtu517_max_handshake", 517, bytes([0xC3]) * LIMIT_HANDSHAKE, 3),
        ("zero_payload", 23, b"", 4),
    ]
    for name, mtu, payload, transfer_id in specs:
        frags = fragments(payload, transfer_id, 0, mtu)
        cases.append(
            {
                "name": name,
                "mtu": mtu,
                "transfer_id": transfer_id,
                "flags": 0,
                "payload_hex": payload.hex() if payload else "",
                "expected_fragments_hex": [f.hex() for f in frags],
            }
        )

    negatives = [
        {
            "name": "bad_wire_version",
            "reason": "BAD_VERSION",
            "fragment_hex": (bytes([3, 0]) + (1).to_bytes(2, "little") + bytes([0, 1]) + (4).to_bytes(2, "little") + b"abcd").hex(),
        },
        {
            "name": "reserved_flag_bit_set",
            "reason": "BAD_FRAME",
            "fragment_hex": (bytes([2, 0x02]) + (1).to_bytes(2, "little") + bytes([0, 1]) + (4).to_bytes(2, "little") + b"abcd").hex(),
        },
        {
            "name": "fragment_count_zero",
            "reason": "BAD_FRAME",
            "fragment_hex": (bytes([2, 0]) + (1).to_bytes(2, "little") + bytes([0, 0]) + (4).to_bytes(2, "little") + b"abcd").hex(),
        },
        {
            "name": "total_bytes_exceeds_normal_limit",
            "reason": "TOO_LARGE",
            "fragment_hex": (bytes([2, 1]) + (5).to_bytes(2, "little") + bytes([0, 1]) + (LIMIT_NORMAL + 1).to_bytes(2, "little") + b"abcd").hex(),
        },
        {
            "name": "fragment_longer_than_declared_total",
            "reason": "BAD_FRAME",
            "fragment_hex": (bytes([2, 0]) + (1).to_bytes(2, "little") + bytes([0, 1]) + (2).to_bytes(2, "little") + b"abcd").hex(),
        },
    ]

    sequences = [
        {
            "name": "out_of_order_then_correct",
            "transfer_id": 21,
            "mtu": 23,
            "payload_hex": bytes(range(30)).hex(),
            "steps": [
                {"fragment_index": 1, "expect": "BAD_FRAME"},
                {"fragment_index": 0, "expect": "INCOMPLETE"},
                {"fragment_index": 1, "expect": "INCOMPLETE"},
                {"fragment_index": 2, "expect": "COMPLETE"},
            ],
        },
        {
            "name": "duplicate_index_rejected",
            "transfer_id": 22,
            "mtu": 23,
            "payload_hex": bytes(range(30)).hex(),
            "steps": [
                {"fragment_index": 0, "expect": "INCOMPLETE"},
                {"fragment_index": 0, "expect": "BAD_FRAME"},
            ],
        },
        {
            "name": "restart_after_duplicate_index",
            "transfer_id": 22,
            "mtu": 23,
            "payload_hex": bytes(range(30)).hex(),
            "steps": [
                {"fragment_index": 0, "expect": "INCOMPLETE"},
                {"fragment_index": 0, "expect": "BAD_FRAME"},
                {"fragment_index": 0, "expect": "INCOMPLETE"},
                {"fragment_index": 1, "expect": "INCOMPLETE"},
                {"fragment_index": 2, "expect": "COMPLETE"},
            ],
        },
        {
            "name": "transfer_switch_midway",
            "transfer_id": 23,
            "mtu": 23,
            "payload_hex": bytes(range(30)).hex(),
            "steps": [
                {"fragment_index": 0, "expect": "INCOMPLETE"},
                {"transfer_id": 24, "fragment_index": 0, "expect": "BAD_FRAME"},
            ],
        },
        {
            "name": "short_middle_fragment_not_padded",
            "transfer_id": 25,
            "mtu": 23,
            "payload_hex": bytes(range(13)).hex(),
            "steps": [
                {"fragment_index": 0, "expect": "BAD_FRAME", "truncate_content_to": 1},
            ],
        },
    ]

    return {
        "name": "fragmentation",
        "cases": cases,
        "negative_fragments": negatives,
        "assembler_sequences": sequences,
    }


def build_handshake() -> dict[str, Any]:
    cases = []
    inputs = [
        {
            "name": "board_1bc5",
            "secret_hex": bytes(range(32)).hex(),
            "board_id": "esp32phone-1bc5",
            "client_nonce_hex": (b"\x01" * 32).hex(),
            "board_nonce_hex": (b"\x02" * 32).hex(),
            "features": 0x0000000F,
        },
        {
            "name": "board_alt_features",
            "secret_hex": hashlib.sha256(b"second-device").hexdigest(),
            "board_id": "e32-7f3a90",
            "client_nonce_hex": bytes((i * 7 + 3) % 256 for i in range(32)).hex(),
            "board_nonce_hex": bytes((i * 11 + 5) % 256 for i in range(32)).hex(),
            "features": 0,
        },
    ]
    for spec in inputs:
        secret = bytes.fromhex(spec["secret_hex"])
        client_nonce = bytes.fromhex(spec["client_nonce_hex"])
        board_nonce = bytes.fromhex(spec["board_nonce_hex"])
        tr = transcript_bytes(spec["board_id"], client_nonce, board_nonce, spec["features"])
        bproof = board_proof(secret, tr)
        aproof = app_proof(secret, tr, bproof)
        derived = derive_session(secret, tr)
        cases.append(
            {
                **spec,
                "transcript_hex": tr.hex(),
                "transcript_sha256_hex": hashlib.sha256(tr).hexdigest(),
                "board_proof_hex": bproof.hex(),
                "app_proof_hex": aproof.hex(),
                **derived,
            }
        )

    # Negative: a proof produced with the wrong role label must not verify, and a
    # proof bound to a tampered nonce must not verify in the original transcript.
    base = cases[0]
    secret = bytes.fromhex(base["secret_hex"])
    tr = bytes.fromhex(base["transcript_hex"])
    tampered = bytearray(tr)
    tampered[-5] ^= 0x01
    negatives = [
        {
            "name": "board_proof_used_as_app_proof",
            "app_proof_hex": base["board_proof_hex"],
            "expect": "AUTH_FAILED",
        },
        {
            "name": "tampered_transcript_nonce",
            "board_proof_hex": board_proof(secret, bytes(tampered)).hex(),
            "expect": "AUTH_FAILED",
        },
        {
            "name": "wrong_secret",
            "board_proof_hex": board_proof(bytes(32), tr).hex(),
            "expect": "AUTH_FAILED",
        },
    ]
    return {"name": "handshake", "cases": cases, "negative_proofs": negatives}


def build_envelope(handshake: dict[str, Any]) -> dict[str, Any]:
    case = handshake["cases"][0]
    session_id = bytes.fromhex(case["session_id_hex"])
    keys = {k: bytes.fromhex(v) for k, v in case["keys"].items()}
    prefixes = {k: bytes.fromhex(v) for k, v in case["nonce_prefixes"].items()}

    specs = [
        {
            "name": "auth_ok_board_normal_seq1",
            "channel": "board_normal",
            "message_type": MESSAGE_TYPES["auth_ok"],
            "sequence": 1,
            "command_id": 0,
            "payload_json": {"t": "auth_ok", "session_id": session_id.hex()},
        },
        {
            "name": "action_app_normal_seq1",
            "channel": "app_normal",
            "message_type": MESSAGE_TYPES["action"],
            "sequence": 1,
            "command_id": 7,
            "payload_json": {"op": "tap", "x_counts": 2048, "y_counts": 1024, "duration_ms": 80},
        },
        {
            "name": "stop_app_control_seq3",
            "channel": "app_control",
            "message_type": MESSAGE_TYPES["stop"],
            "sequence": 3,
            "command_id": 8,
            "payload_json": {"t": "stop", "reason": "user"},
        },
        {
            "name": "stopped_board_control_seq3",
            "channel": "board_control",
            "message_type": MESSAGE_TYPES["stopped"],
            "sequence": 3,
            "command_id": 8,
            "payload_json": {"t": "stopped", "released": True, "release_sent": True},
        },
    ]

    cases = []
    for spec in specs:
        payload = json.dumps(spec["payload_json"], separators=(",", ":"), sort_keys=False).encode("utf-8")
        channel = spec["channel"]
        sealed = seal(
            keys[channel],
            prefixes[channel],
            spec["message_type"],
            session_id,
            spec["sequence"],
            spec["command_id"],
            payload,
        )
        cases.append(
            {
                "name": spec["name"],
                "channel": channel,
                "session_id_hex": session_id.hex(),
                "message_type": spec["message_type"],
                "sequence": spec["sequence"],
                "command_id": spec["command_id"],
                "payload_utf8": payload.decode("utf-8"),
                "expected_aad_hex": sealed[:ENVELOPE_HEADER_LEN].hex(),
                "expected_nonce_hex": (prefixes[channel] + spec["sequence"].to_bytes(4, "little")).hex(),
                "expected_sealed_hex": sealed.hex(),
            }
        )

    negatives = []
    for src in (cases[0], cases[1]):
        raw = bytearray(bytes.fromhex(src["expected_sealed_hex"]))
        flipped = bytearray(raw)
        flipped[-1] ^= 0x01
        negatives.append(
            {
                "name": f"{src['name']}_tag_flip",
                "channel": src["channel"],
                "expected_sealed_hex": bytes(flipped).hex(),
                "expect": "AUTH_FAILED",
            }
        )
        aad_flip = bytearray(raw)
        aad_flip[ENVELOPE_HEADER_LEN - 3] ^= 0x01  # inside command_id
        negatives.append(
            {
                "name": f"{src['name']}_aad_flip",
                "channel": src["channel"],
                "expected_sealed_hex": bytes(aad_flip).hex(),
                "expect": "AUTH_FAILED",
            }
        )
        truncated = raw[: len(raw) - 1]
        negatives.append(
            {
                "name": f"{src['name']}_truncated_tag",
                "channel": src["channel"],
                "expected_sealed_hex": bytes(truncated).hex(),
                "expect": "BAD_FRAME",
            }
        )

    return {"name": "envelope", "handshake_case": case["name"], "cases": cases, "negative_sealed": negatives}


def build_ledger() -> dict[str, Any]:
    """Command dedup / watermark scenarios shared by Kotlin and C++."""
    return {
        "name": "command_ledger",
        "cache_size": 64,
        "scenarios": [
            {
                "name": "same_id_same_payload",
                "steps": [
                    {"command_id": 5, "payload": "tap:1", "expect": "EXECUTE"},
                    {"command_id": 5, "payload": "tap:1", "expect": "DUPLICATE"},
                ],
            },
            {
                "name": "same_id_different_payload",
                "steps": [
                    {"command_id": 5, "payload": "tap:1", "expect": "EXECUTE"},
                    {"command_id": 5, "payload": "tap:2", "expect": "CONFLICT"},
                ],
            },
            {
                "name": "stale_after_eviction",
                "steps": [
                    *[{"command_id": i, "payload": f"p{i}", "expect": "EXECUTE"} for i in range(1, 71)],
                    {"command_id": 70, "payload": "p70", "expect": "DUPLICATE"},
                    {"command_id": 7, "payload": "p7", "expect": "DUPLICATE"},
                    {"command_id": 6, "payload": "p6", "expect": "STALE"},
                    {"command_id": 1, "payload": "p1", "expect": "STALE"},
                ],
            },
            {
                "name": "increasing_ids",
                "steps": [
                    {"command_id": 2, "payload": "a", "expect": "EXECUTE"},
                    {"command_id": 3, "payload": "b", "expect": "EXECUTE"},
                    {"command_id": 4, "payload": "c", "expect": "EXECUTE"},
                ],
            },
            {
                "name": "cache_eviction_keeps_watermark",
                "steps": [
                    {"command_id": 1, "payload": "a", "expect": "EXECUTE"},
                    {"command_id": 2, "payload": "b", "expect": "EXECUTE"},
                    {"command_id": 3, "payload": "c", "expect": "EXECUTE"},
                    {"command_id": 4, "payload": "d", "expect": "EXECUTE"},
                    {"command_id": 4, "payload": "d", "expect": "DUPLICATE"},
                    {"command_id": 2, "payload": "b", "expect": "DUPLICATE"},
                    {"command_id": 1, "payload": "a", "expect": "DUPLICATE"},
                ],
            },
        ],
    }


def build_documents() -> dict[str, Any]:
    handshake = build_handshake()
    return {
        "schema.json": SCHEMA,
        "fragmentation.json": build_fragmentation(),
        "handshake.json": handshake,
        "envelope.json": build_envelope(handshake),
        "command_ledger.json": build_ledger(),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    out_dir = pathlib.Path(__file__).resolve().parent.parent / "vectors"
    out_dir.mkdir(parents=True, exist_ok=True)
    docs = build_documents()

    failures = 0
    for filename, doc in docs.items():
        text = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
        target = out_dir / filename
        if args.check:
            existing = target.read_text(encoding="utf-8") if target.exists() else ""
            if existing != text:
                print(f"MISMATCH {filename}", file=sys.stderr)
                failures += 1
            else:
                print(f"ok {filename}")
        else:
            target.write_text(text, encoding="utf-8", newline="\n")
            print(f"wrote {target}")

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
