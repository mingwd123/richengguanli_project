import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from collect_status import backup_status, collect, container_status


class CollectorTests(unittest.TestCase):
    def test_missing_receipt_does_not_claim_success(self):
        self.assertEqual(backup_status(None), {"status": "unknown"})
        self.assertEqual(backup_status("does-not-exist")["status"], "unknown")

    def test_receipt_requires_checksum_and_explicit_offsite_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "backup.sql"
            artifact.write_bytes(b"test-only-backup")
            receipt = root / "receipt.json"
            payload = {
                "result": "success", "artifact": artifact.name,
                "completedAt": "2020-01-01T00:00:00Z",
                "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
            }
            receipt.write_text(json.dumps(payload))
            self.assertFalse(backup_status(receipt)["offsite"])
            payload["offsite"] = {"result": "verified", "sha256": payload["sha256"], "verifiedAt": "2020-01-01T00:01:00Z"}
            receipt.write_text(json.dumps(payload))
            self.assertTrue(backup_status(receipt)["offsite"])
            artifact.write_bytes(b"changed")
            self.assertEqual(backup_status(receipt)["status"], "unknown")

    @patch("collect_status.subprocess.run")
    def test_container_health_and_command_failure_are_not_green(self, run):
        run.return_value.stdout = '{"Running":true,"Health":{"Status":"unhealthy"}}'
        self.assertEqual(container_status("app")["status"], "down")
        run.return_value.stdout = '{"Running":true,"Health":{"Status":"healthy"}}'
        self.assertEqual(container_status("app")["status"], "up")
        run.side_effect = subprocess.TimeoutExpired("docker", 10)
        self.assertEqual(container_status("app")["status"], "unknown")

    def test_atomic_output_preserves_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "ops.json"
            collect(output)
            self.assertIn("observedAt", json.loads(output.read_text()))
            with self.assertRaises(ValueError):
                collect(output, output)


if __name__ == "__main__":
    unittest.main()
