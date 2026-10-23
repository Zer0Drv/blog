"""Local Docker lifecycle worker. Run separately from the application; no network listener."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

spec = importlib.util.spec_from_file_location("local_backup", Path(__file__).with_name("local-backup.py"))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


def write_report(root, job, state, **extra):
    path = root / (job + ".json")
    temporary = root / (job + ".tmp")
    temporary.write_text(json.dumps({"id": job, "state": state, **extra}), encoding="utf-8")
    temporary.replace(path)


def process(root, lock):
    pending = root / "request"
    request = pending / "request.json"
    if not request.is_file():
        return
    # A writer may still be finishing the small request; retry a partial JSON on the next tick.
    try:
        data = json.loads(request.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return
    job = str(uuid.UUID(data["id"]))
    if data["id"] != job:
        raise ValueError("Invalid request ID")
    audit = {"operator": data.get("operator"), "requestedAt": data.get("requestedAt"),
             "previousInstance": data.get("instance")}
    descriptor = None
    try:
        descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.write(descriptor, ("restart-" + job).encode())
        marker = pending / "started"
        if marker.exists():
            raise RuntimeError("Interrupted restart requires operator verification")
        marker.write_text(job, encoding="utf-8")
        write_report(root, job, "RESTARTING", **audit)
        # The executable and container are constants, never supplied by a Web request.
        subprocess.run(["docker", "restart", "--time", "30", "blog-backend"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90)
        backup.wait_ready("blog-backend")
        checks = backup.recover_gateway()
        write_report(root, job, "RECOVERED", gatewayChecks=checks, **audit)
    except FileExistsError:
        write_report(root, job, "FAILED", reason="发布或备份持有部署锁，未执行重启", **audit)
    except Exception:
        write_report(root, job, "FAILED", reason="重启或恢复验证失败，请检查容器与网关；不会自动再次重启", **audit)
    finally:
        if descriptor is not None:
            os.close(descriptor)
            Path(lock).unlink()
        request.unlink(missing_ok=True)
        (pending / "started").unlink(missing_ok=True)
        pending.rmdir()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--lock", required=True, help="The same .deployment.lock used by both CD workflows and backup")
    args = parser.parse_args()
    root = Path(args.directory).resolve()
    root.mkdir(parents=True, exist_ok=True)
    # Fail closed if another worker is running, or the previous process died.
    leader = root / "worker.lock"
    descriptor = os.open(leader, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.write(descriptor, str(os.getpid()).encode())
    try:
        while True:
            (root / "heartbeat").write_text(str(time.time()), encoding="utf-8")
            process(root, Path(args.lock).resolve())
            time.sleep(2)
    finally:
        os.close(descriptor)
        leader.unlink()


if __name__ == "__main__":
    main()
