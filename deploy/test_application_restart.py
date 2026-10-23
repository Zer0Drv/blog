import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

spec = importlib.util.spec_from_file_location("worker", Path(__file__).with_name("application-restart-worker.py"))
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class RestartTest(unittest.TestCase):
    def fixture(self, root):
        job = str(uuid.uuid4())
        (root / "request").mkdir()
        (root / "request" / "request.json").write_text(json.dumps({"id": job, "instance": "old"}))
        return job

    def test_success_requires_backend_and_gateway_and_uses_fixed_command(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); job = self.fixture(root); lock = root / "deployment.lock"
            with patch.object(worker.subprocess, "run") as run, patch.object(worker.backup, "wait_ready") as ready, \
                    patch.object(worker.backup, "recover_gateway", return_value=["/", "/assets/app.js"]) as gateway:
                worker.process(root, lock)
                self.assertEqual(["docker", "restart", "--time", "30", "blog-backend"], run.call_args.args[0])
                ready.assert_called_once_with("blog-backend"); gateway.assert_called_once()
            self.assertEqual("RECOVERED", json.loads((root / (job + ".json")).read_text())["state"])
            self.assertFalse(lock.exists())

    def test_deployment_lock_prevents_restart_and_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); job = self.fixture(root); lock = root / "deployment.lock"; lock.write_text("deployment")
            with patch.object(worker.subprocess, "run") as run:
                worker.process(root, lock); run.assert_not_called()
            self.assertEqual("deployment", lock.read_text())
            self.assertEqual("FAILED", json.loads((root / (job + ".json")).read_text())["state"])

    def test_interrupted_job_is_not_restarted_twice(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); job = self.fixture(root)
            (root / "request" / "started").write_text(job)
            with patch.object(worker.subprocess, "run") as run:
                worker.process(root, root / "deployment.lock"); run.assert_not_called()
            self.assertEqual("FAILED", json.loads((root / (job + ".json")).read_text())["state"])

    def test_gateway_failure_is_not_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); job = self.fixture(root)
            with patch.object(worker.subprocess, "run"), patch.object(worker.backup, "wait_ready"), \
                    patch.object(worker.backup, "recover_gateway", side_effect=RuntimeError("502")):
                worker.process(root, root / "deployment.lock")
            self.assertEqual("FAILED", json.loads((root / (job + ".json")).read_text())["state"])


if __name__ == "__main__": unittest.main()
