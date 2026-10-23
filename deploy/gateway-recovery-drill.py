"""Disposable nginx/Docker DNS-change regression. Never accesses production containers."""
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import uuid

spec = importlib.util.spec_from_file_location("backup", Path(__file__).with_name("local-backup.py"))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


def docker(*args):
    return subprocess.check_output(["docker", *args], stderr=subprocess.PIPE, timeout=90).decode().strip()


def main():
    prefix = "blog-gateway-it-" + uuid.uuid4().hex[:10]
    root = Path("target", prefix).resolve()
    files = root / "www"
    (files / "blog-frontend" / "assets").mkdir(parents=True)
    (files / "site").mkdir()
    html = '<html><div id="app"></div><script type="module" src="/assets/app.js"></script></html>'
    (files / "blog-frontend" / "index.html").write_text(html)
    (files / "blog-frontend" / "assets" / "app.js").write_text('export {};')
    (files / "blog-frontend" / "release.json").write_text(json.dumps({"schemaVersion":1,"sha":"0"*40}))
    (files / "site" / "config").write_text('{"code":"200","data":{"site.name":"Fixture"}}')
    (files / "articles").write_text('{"code":"200","data":{"records":[{"id":1}]}}')
    (root / "upstream.conf").write_text('server { listen 9000; root /usr/share/nginx/html; location = /site/config { default_type application/json; } location = /articles { default_type application/json; } location = /article/1 { default_type text/html; alias /usr/share/nginx/html/blog-frontend/index.html; } }')
    config = Path(__file__).with_name("nginx.conf").read_text(encoding="utf-8").replace("host.docker.internal:8082", "backend:9000")
    (root / "gateway.conf").write_text(config, encoding="utf-8")
    upstream, gateway, blocker = prefix+"-upstream", prefix+"-gateway", prefix+"-blocker"
    containers = []
    created_network = False
    try:
        docker("network", "create", prefix); created_network = True
        def start_upstream():
            docker("run", "-d", "--name", upstream, "--network", prefix, "--network-alias", "minio", "--network-alias", "backend",
                   "-v", str(files)+":/usr/share/nginx/html:ro", "-v", str(root/"upstream.conf")+":/etc/nginx/conf.d/default.conf:ro", "nginx:1.29-alpine")
            if upstream not in containers: containers.append(upstream)
        start_upstream()
        old = json.loads(docker("inspect", upstream))[0]["NetworkSettings"]["Networks"][prefix]["IPAddress"]
        docker("run", "-d", "--name", gateway, "--network", prefix, "-p", "127.0.0.1::80",
               "-v", str(root/"gateway.conf")+":/etc/nginx/conf.d/default.conf:ro", "nginx:1.29-alpine")
        containers.append(gateway)
        port = json.loads(docker("inspect", gateway))[0]["NetworkSettings"]["Ports"]["80/tcp"][0]["HostPort"]
        origin = "http://127.0.0.1:"+port
        for attempt in range(30):
            try: backup.verify_gateway(origin); break
            except Exception:
                if attempt == 29: raise
                time.sleep(1)
        docker("rm", "-f", upstream)
        docker("run", "-d", "--name", blocker, "--network", prefix, "--ip", old, "nginx:1.29-alpine", "sleep", "300")
        containers.append(blocker)
        start_upstream()
        new = json.loads(docker("inspect", upstream))[0]["NetworkSettings"]["Networks"][prefix]["IPAddress"]
        assert old != new
        try: backup.verify_gateway(origin)
        except Exception: reproduced = True
        else: raise AssertionError("Expected stale upstream to fail before reload")
        original_command, original_smoke = backup.command, backup.verify_gateway
        backup.command = lambda args, **kw: original_command([gateway if value == "blog-gateway" else value for value in args], **kw)
        backup.verify_gateway = lambda: original_smoke(origin)
        checks = backup.recover_gateway()
        report = {"oldIp":old,"newIp":new,"failureReproduced":reproduced,"recoveryChecks":checks,"ok":True}
        (root / "report.json").write_text(json.dumps(report,indent=2))
        print(json.dumps(report))
    finally:
        for container in reversed(containers):
            try: docker("rm", "-f", container)
            except subprocess.CalledProcessError: pass
        if created_network: docker("network", "rm", prefix)


if __name__ == "__main__": main()
