import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("backup", Path(__file__).with_name("local-backup.py"))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class SnapshotTest(unittest.TestCase):
    def test_success_copies_configuration_and_records_only_verified_recovery_point(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            (base / "deploy").mkdir()
            for path in [".env", "docker-compose.yml", "docker-compose.prod.yml", "deploy/nginx.conf"]:
                (base / path).write_text("fixture")
            entries = {name: {"Image": "sha256:fixture", "Config": {"Env": [], "WorkingDir": "/app",
                        "Image": "fixture:1", "Labels": None}} for name in backup.CONTAINERS}

            def command(args, **kwargs):
                if args[1] == "cp":
                    kwargs["stdout"].write(b"archive fixture")
                return SimpleNamespace(stdout=b"0\n")

            with patch.object(backup, "inspect", return_value=entries), \
                    patch.object(backup, "protect"), patch.object(backup, "wait_ready"), \
                    patch.object(backup, "recover_gateway", return_value=["/", "/release.json"]), \
                    patch.object(backup.shutil, "disk_usage", return_value=SimpleNamespace(free=2**40)), \
                    patch.object(backup, "command", side_effect=command), \
                    patch.object(backup.subprocess, "run") as probe:
                probe.return_value.returncode = 1
                result = backup.backup(base / "backups", base / ".env", execute=True)
            self.assertTrue(result["ok"])
            manifest = backup.verify(base / "backups" / result["snapshot"])
            self.assertEqual(result["recoveryPoint"], manifest["recoveryPoint"])
            self.assertEqual(8, len(manifest["files"]))

    def test_gateway_failure_preserves_previous_success_and_restarts_every_service(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            (base / "deploy").mkdir()
            for path in [".env", "docker-compose.yml", "docker-compose.prod.yml", "deploy/nginx.conf"]:
                (base / path).write_text("fixture")
            root = base / "backups"
            root.mkdir()
            (root / "last-success.json").write_text('{"previous":true}')
            entries = {name: {"Image": "fixture", "Config": {"Env": [], "WorkingDir": "/app",
                       "Image": "fixture", "Labels": None}} for name in backup.CONTAINERS}
            calls = []
            def command(args, **kwargs):
                calls.append(args)
                if args[1] == "cp": kwargs["stdout"].write(b"archive")
                return SimpleNamespace(stdout=b"0")
            with patch.object(backup, "inspect", return_value=entries), patch.object(backup, "protect"), \
                    patch.object(backup, "wait_ready"), patch.object(backup, "recover_gateway", side_effect=RuntimeError("failed")), \
                    patch.object(backup.shutil, "disk_usage", return_value=SimpleNamespace(free=2**40)), \
                    patch.object(backup, "command", side_effect=command), patch.object(backup.subprocess, "run") as probe:
                probe.return_value.returncode = 1
                with self.assertRaisesRegex(RuntimeError, "blog-gateway"):
                    backup.backup(root, base / ".env", execute=True)
            self.assertEqual(list(reversed(backup.CONTAINERS)), [a[-1] for a in calls if a[1] == "start"])
            self.assertEqual('{"previous":true}', (root / "last-success.json").read_text())
            self.assertFalse(json.loads((root / "last-attempt.json").read_text())["ok"])
            self.assertFalse((base / ".deployment.lock").exists())

    def test_invalid_gateway_configuration_never_reloads(self):
        with patch.object(backup, "command", side_effect=RuntimeError("invalid config")) as command:
            with self.assertRaisesRegex(RuntimeError, "invalid config"):
                backup.recover_gateway()
        self.assertEqual(1, command.call_count)
        self.assertEqual("-t", command.call_args.args[0][-1])

    def test_reload_failure_never_reports_success(self):
        with patch.object(backup, "command", side_effect=[None, RuntimeError("reload")]), \
                patch.object(backup, "verify_gateway") as smoke:
            with self.assertRaisesRegex(RuntimeError, "reload"):
                backup.recover_gateway()
        smoke.assert_not_called()

    def test_backend_health_does_not_replace_gateway_smoke(self):
        with patch.object(backup, "command"), patch.object(backup, "verify_gateway", side_effect=RuntimeError("502")), \
                patch.object(backup.time, "monotonic", side_effect=[0, 31]):
            with self.assertRaisesRegex(RuntimeError, "Gateway recovery smoke failed"):
                backup.recover_gateway()

    def test_integrity_rejects_damage_extra_and_path_escape(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            names = ["blog-mysql.tar", "blog-redis.tar", "blog-minio.tar", "deployment.env",
                     "runtime-config.json", "compose.yml", "compose.prod.yml", "nginx.conf"]
            for name in names:
                (root / name).write_bytes(b"snapshot")
            data = root / names[0]
            manifest = {"schemaVersion": 1, "files": [{"name": name, "size": 8, "sha256": backup.digest(root / name)} for name in names]}
            (root / "manifest.json").write_text(json.dumps(manifest))
            backup.verify(root)
            data.write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                backup.verify(root)
            data.write_bytes(b"snapshot")
            (root / "extra").write_text("unexpected")
            with self.assertRaises(ValueError):
                backup.verify(root)
            (root / "extra").unlink()
            manifest["files"][0]["name"] = "../outside"
            (root / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                backup.verify(root)

    def test_copy_failure_restarts_all_services_and_preserves_last_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "backups"
            root.mkdir()
            (root / "last-success.json").write_text('{"previous":true}')
            config = Path(temporary) / "deployment.env"
            config.write_text("SECRET=not-for-logs")
            entries = {name: {"Config": {"Env": [], "WorkingDir": "/app"}} for name in backup.CONTAINERS}
            calls = []

            def command(args, **kwargs):
                calls.append(args)
                if args[1] == "cp":
                    raise RuntimeError("copy failed")
                return SimpleNamespace(stdout=b"0\n")

            with patch.object(backup, "inspect", return_value=entries), \
                    patch.object(backup, "protect"), patch.object(backup, "wait_ready"), \
                    patch.object(backup, "recover_gateway", return_value=["/", "/release.json"]), \
                    patch.object(backup.shutil, "disk_usage", return_value=SimpleNamespace(free=2**40)), \
                    patch.object(backup, "command", side_effect=command), \
                    patch.object(backup.subprocess, "run") as probe:
                probe.return_value.returncode = 1
                with self.assertRaisesRegex(RuntimeError, "copy failed"):
                    backup.backup(root, config, execute=True)
            starts = [args[-1] for args in calls if args[1] == "start"]
            self.assertEqual(list(reversed(backup.CONTAINERS)), starts)
            self.assertEqual('{"previous":true}', (root / "last-success.json").read_text())
            self.assertFalse(json.loads((root / "last-attempt.json").read_text())["ok"])
            self.assertFalse((root / ".backup.lock").exists())


if __name__ == "__main__":
    unittest.main()
