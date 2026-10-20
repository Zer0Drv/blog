"""Consistent cold snapshot of this host's Docker deployment. No third-party dependencies.

Default invocation only reports the plan. --execute requires a maintenance window:
backend, MySQL, Redis and MinIO stop while their persistent data is copied.
No restore, pruning or production overwrite is performed by this program.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid

CONTAINERS = ("blog-backend", "blog-mysql", "blog-redis", "blog-minio")
DATA = {"blog-mysql": "/var/lib/mysql", "blog-redis": "/data", "blog-minio": "/data"}


def command(args, **kwargs):
    result = subprocess.run(args, stderr=subprocess.PIPE, timeout=3600, **kwargs)
    if result.returncode:
        # Docker diagnostics may contain deployment details; do not expose them to public logs.
        raise RuntimeError(f"{args[0]} {args[1]} failed ({result.returncode})")
    return result


def protect(path):
    if os.name == "nt":
        identity = subprocess.check_output(["whoami"], text=True).strip()
        command(["icacls", str(path), "/inheritance:r", "/grant:r",
                 f"{identity}:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"], stdout=subprocess.DEVNULL)
    else:
        path.chmod(0o700)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def wait_ready(name):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            if name == "blog-backend":
                with urllib.request.urlopen("http://127.0.0.1:8082/actuator/health", timeout=5) as response:
                    if json.load(response).get("status") == "UP":
                        return
            else:
                state = json.loads(command(["docker", "inspect", "--format", "{{json .State}}", name],
                                           stdout=subprocess.PIPE).stdout)
                if state.get("Running") and state.get("Health", {}).get("Status") == "healthy":
                    return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError(f"{name} did not become healthy")


def verify(directory):
    directory = Path(directory).resolve()
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1 or not manifest.get("files"):
        raise ValueError("Invalid snapshot manifest")
    expected = {"manifest.json"}
    for item in manifest["files"]:
        name = item["name"]
        if not isinstance(name, str) or Path(name).name != name or name in expected or "\\" in name:
            raise ValueError("Invalid snapshot member")
        expected.add(name)
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size != item["size"] or digest(path) != item["sha256"]:
            raise ValueError("Snapshot checksum or size mismatch")
    if {p.name for p in directory.iterdir()} != expected:
        raise ValueError("Unexpected snapshot member")
    required = {"blog-mysql.tar", "blog-redis.tar", "blog-minio.tar", "deployment.env",
                "runtime-config.json", "compose.yml", "compose.prod.yml", "nginx.conf"}
    if manifest.get("localUploadsPresent"):
        required.add("local-uploads.tar")
    if not required.issubset(expected):
        raise ValueError("Snapshot is missing a required recovery component")
    return manifest


def inspect():
    raw = command(["docker", "inspect", *CONTAINERS], stdout=subprocess.PIPE).stdout
    containers = json.loads(raw)
    if len(containers) != len(CONTAINERS):
        raise ValueError("Incomplete deployment")
    by_name = {entry["Name"].lstrip("/"): entry for entry in containers}
    for name in CONTAINERS:
        entry = by_name[name]
        if not entry["State"]["Running"]:
            raise ValueError(f"{name} is not running; investigate before backup")
        if name in DATA and not any(m["Destination"] == DATA[name] and m["Type"] in ("volume", "bind")
                                    for m in entry["Mounts"]):
            raise ValueError(f"{name} must have persistent data (tmpfs is not a cold-backup source)")
        digests = command(["docker", "image", "inspect", "--format", "{{json .RepoDigests}}", entry["Image"]],
                          stdout=subprocess.PIPE).stdout
        entry["BackupRepoDigests"] = json.loads(digests)
        if not entry["BackupRepoDigests"]:
            raise ValueError(f"{name} has no immutable registry image reference")
    return by_name


def capture_sources():
    sources = inspect()
    backend = sources["blog-backend"]
    environment = dict(value.split("=", 1) for value in backend["Config"]["Env"] if "=" in value)
    upload_dir = environment.get("BLOG_UPLOAD_DIR", "./uploads")
    if not upload_dir.startswith("/"):
        upload_dir = backend["Config"]["WorkingDir"].rstrip("/") + "/" + upload_dir
    probe = subprocess.run(["docker", "exec", "blog-backend", "test", "-d", upload_dir],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
    if probe.returncode not in (0, 1):
        raise RuntimeError("Unable to inspect local uploads")
    return sources, upload_dir, probe.returncode == 0


def backup(root, config_file, execute=False):
    root, config_file = Path(root).resolve(), Path(config_file).resolve()
    if any((parent / ".git").exists() for parent in [root, *root.parents]):
        raise ValueError("Backup destination must be outside a Git checkout")
    if not config_file.is_file():
        raise ValueError("Deployment configuration is missing")
    if not execute:
        sources, upload_dir, uploads_present = capture_sources()
        return {"mode": "plan", "destination": str(root), "containers": list(CONTAINERS),
                "localUploadsPresent": uploads_present,
                "downtime": "All application writes and object reads stop during the snapshot",
                "rpoTargetHours": 24, "rtoTargetHours": 2, "offsiteCopy": False}
    root.mkdir(parents=True, exist_ok=True)
    protect(root)
    # Same host-wide lock as both CD workflows: no deployment can mutate the snapshot sources.
    lock = root.parent / ".deployment.lock"
    try:
        descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        (root / "last-attempt.json").write_text(json.dumps({
            "checkedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "ok": False,
            "reason": "Another deployment or backup holds the host lock", "offsiteCopy": False
        }), encoding="utf-8")
        raise
    os.write(descriptor, f"backup-{os.getpid()}".encode())
    snapshot = root / (dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex)
    stopped, restart_errors = [], []
    success = False
    frozen = None
    try:
        # Inspect after acquiring the CD lock so image/config identity belongs to this snapshot.
        sources, upload_dir, uploads_present = capture_sources()
        snapshot.mkdir(mode=0o700)
        protect(snapshot)
        expected_bytes = 0
        paths = list(DATA.items())
        if uploads_present:
            paths.append(("blog-backend", upload_dir))
        for name, source in paths:
            sizes = command(["docker", "exec", name, "sh", "-c",
                             'find "$1" -type f -exec stat -c %s {} \\;', "--", source],
                            stdout=subprocess.PIPE).stdout
            # Apparent sizes include sparse database files that a tar stream may expand.
            expected_bytes += sum(int(size) + 1024 for size in sizes.splitlines())
        if shutil.disk_usage(root).free < expected_bytes * 2 + 5 * 1024**3:
            raise RuntimeError("Insufficient space: require twice the source size plus 5 GiB reserve")
        started = dt.datetime.now(dt.timezone.utc).isoformat()
        # Stop the writer first; never capture SQL and objects while background workers are writing.
        for name in CONTAINERS:
            stopped.append(name)  # Even a timed-out stop must be followed by a start attempt.
            command(["docker", "stop", "--time", "60", name], stdout=subprocess.DEVNULL)
        frozen = dt.datetime.now(dt.timezone.utc).isoformat()
        for name, source in DATA.items():
            with (snapshot / f"{name}.tar").open("xb") as output:
                command(["docker", "cp", f"{name}:{source}/.", "-"], stdout=output)
        if uploads_present:
            with (snapshot / "local-uploads.tar").open("xb") as output:
                command(["docker", "cp", f"blog-backend:{upload_dir}/.", "-"], stdout=output)
        # Required for JWT/TOTP/database recovery. This entire directory is sensitive, not a CI artifact.
        shutil.copyfile(config_file, snapshot / "deployment.env")
        (snapshot / "runtime-config.json").write_text(json.dumps(sources, indent=2), encoding="utf-8")
        for relative, name in [("docker-compose.yml", "compose.yml"),
                               ("docker-compose.prod.yml", "compose.prod.yml"),
                               ("deploy/nginx.conf", "nginx.conf")]:
            source = config_file.parent / relative
            if not source.is_file():
                raise ValueError(f"Required deployment file is missing: {relative}")
            shutil.copyfile(source, snapshot / name)
        manifest = {
            "schemaVersion": 1, "startedAt": started, "recoveryPoint": frozen,
            "estimatedSourceBytes": expected_bytes,
            "method": "stopped-container-physical-copy", "localUploadsPath": upload_dir,
            "localUploadsPresent": uploads_present,
            "images": {name: {"id": entry["Image"], "reference": entry["Config"]["Image"],
                              "repoDigests": entry.get("BackupRepoDigests", []),
                              "revision": (entry["Config"].get("Labels") or {}).get("org.opencontainers.image.revision")}
                       for name, entry in sources.items()},
            "files": [{"name": p.name, "size": p.stat().st_size, "sha256": digest(p)}
                      for p in sorted(snapshot.iterdir())],
            "offsiteCopy": False
        }
        (snapshot / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        verify(snapshot)
        success = True
    finally:
        # Infrastructure before application. Attempt every restart even if another one fails.
        for name in reversed(stopped):
            try:
                command(["docker", "start", name], stdout=subprocess.DEVNULL)
                wait_ready(name)
            except Exception:
                restart_errors.append(name)
        status = {"checkedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                  "ok": success and not restart_errors, "snapshot": snapshot.name,
                  "restartFailures": restart_errors, "offsiteCopy": False, "recoveryPoint": frozen}
        try:
            (root / "last-attempt.json").write_text(json.dumps(status, indent=2), encoding="utf-8")
            if status["ok"]:
                shutil.copyfile(root / "last-attempt.json", root / "last-success.json")
        finally:
            os.close(descriptor)
            lock.unlink()
    if restart_errors:
        raise RuntimeError("Restart failed: " + ", ".join(restart_errors))
    return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=r"E:\dev\zer0drv\backups")
    parser.add_argument("--config", default=r"E:\dev\zer0drv\blog\.env")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--verify")
    parser.add_argument("--status", action="store_true")
    args = parser.parse_args()
    try:
        if args.status:
            root = Path(args.root).resolve()
            latest = json.loads((root / "last-success.json").read_text(encoding="utf-8"))
            age = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(latest["recoveryPoint"])).total_seconds() / 3600
            attempt = json.loads((root / "last-attempt.json").read_text(encoding="utf-8"))
            print(json.dumps({"lastSuccessAgeHours": round(age, 2), "latestAttemptOk": attempt["ok"],
                              "rpoTargetHours": 24, "offsiteCopy": False}))
            return 0 if 0 <= age < 18 and attempt["ok"] else 1
        elif args.verify:
            verify(args.verify)
            print('{"verified": true}')
        else:
            print(json.dumps(backup(args.root, args.config, args.execute), indent=2))
    except Exception as error:
        print(f"Backup failed: {type(error).__name__}: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
