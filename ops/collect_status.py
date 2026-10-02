"""Read-only host collector. Backup success requires a verified job receipt."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def timestamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp must include timezone")
    if parsed > datetime.now(timezone.utc):
        raise ValueError("timestamp is in the future")
    return parsed.astimezone(timezone.utc).isoformat()


def backup_status(receipt_path):
    if not receipt_path:
        return {"status": "unknown"}
    try:
        path = Path(receipt_path).resolve(strict=True)
        if path.stat().st_size > 65536:
            raise ValueError("receipt too large")
        receipt = json.loads(path.read_text(encoding="utf-8-sig"))
        if receipt.get("result") != "success":
            raise ValueError("backup job did not succeed")
        artifact = Path(receipt["artifact"])
        if not artifact.is_absolute():
            artifact = path.parent / artifact
        artifact = artifact.resolve(strict=True)
        if not artifact.is_file() or artifact.stat().st_size <= 0:
            raise ValueError("backup artifact missing or empty")
        digest = hashlib.sha256()
        with artifact.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        checksum = digest.hexdigest()
        if checksum != receipt["sha256"].lower():
            raise ValueError("backup checksum mismatch")
        success = timestamp(receipt["completedAt"])
        offsite = receipt.get("offsite", {})
        offsite_verified = (
            offsite.get("result") == "verified"
            and offsite.get("sha256", "").lower() == checksum
            and bool(timestamp(offsite["verifiedAt"]))
            and datetime.fromisoformat(timestamp(offsite["verifiedAt"])) >= datetime.fromisoformat(success)
        )
        return {"lastSuccessAt": success, "offsite": offsite_verified}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return {"status": "unknown", "reason": "backup_receipt_unverified"}


def container_status(name):
    try:
        result = subprocess.run(
            ["docker", "inspect", "--type", "container", "--format", "{{json .State}}", name],
            capture_output=True, text=True, timeout=10, check=True,
        )
        state = json.loads(result.stdout)
        healthy = state.get("Health", {}).get("Status")
        status = "up" if state.get("Running") and healthy in (None, "healthy") else "down"
        return {"name": name, "status": status}
    except (OSError, subprocess.SubprocessError, ValueError, AttributeError):
        return {"name": name, "status": "unknown"}


def collect(output, receipt=None, containers=()):
    target = Path(output).resolve()
    if receipt and target == Path(receipt).resolve():
        raise ValueError("output must not overwrite the backup receipt")
    payload = {
        "observedAt": datetime.now(timezone.utc).isoformat(),
        "backup": backup_status(receipt),
        "containers": [container_status(name) for name in containers],
    }
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=target.parent, prefix=".ops-", suffix=".json")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return payload


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--backup-receipt")
    parser.add_argument("--container", action="append", default=[])
    args = parser.parse_args()
    if len(args.container) > 50 or any(not name or len(name) > 100 or name.startswith("-") for name in args.container):
        parser.error("specify at most 50 valid container names")
    collect(args.output, args.backup_receipt, args.container)
