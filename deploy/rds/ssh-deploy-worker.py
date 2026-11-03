#!/usr/bin/python3 -I
"""Root-owned forced-command worker. No shell, arbitrary paths or bundled scripts."""
import contextlib
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import resource
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request

MAX_BUNDLE = 80 * 1024 * 1024
MAX_EXPANDED = 200 * 1024 * 1024
MAX_ENTRIES = 5000
SHA = r"[0-9a-f]{64}"
RELEASE = r"[A-Za-z0-9][A-Za-z0-9_-]{6,63}"


def parse_command(command):
    if command == "status":
        return ("status",)
    match = re.fullmatch(rf"upload ({SHA}) ([1-9][0-9]{{0,8}})", command)
    if match and int(match[2]) <= MAX_BUNDLE:
        return ("upload", match[1], int(match[2]))
    match = re.fullmatch(rf"deploy ({RELEASE}) ({SHA})", command)
    if match:
        return ("deploy", match[1], match[2])
    raise ValueError("Only status, bounded upload and project deployment are allowed")


def safe_extract(bundle, destination):
    """Copy only regular project files; never trust archive ownership or modes."""
    destination = Path(destination)
    def directory(path):
        path.mkdir(mode=0o755, parents=True, exist_ok=True)
        while path == destination or destination in path.parents:
            path.chmod(0o755)
            if path == destination:
                break
            path = path.parent
    entries = total = 0
    seen = set()
    opened = tarfile.open(fileobj=bundle, mode="r:gz") if hasattr(bundle, "read") else tarfile.open(bundle, "r:gz")
    with opened as archive:
        for member in archive:
            entries += 1
            path = PurePosixPath(member.name)
            if (entries > MAX_ENTRIES or path.is_absolute() or
                    any(part in ("", ".", "..") for part in member.name.rstrip("/").split("/")) or
                    "\\" in member.name or any(ord(c) < 32 or ord(c) == 127 for c in member.name) or
                    member.name in seen or not (member.isfile() or member.isdir()) or
                    member.issparse() or member.size < 0):
                raise ValueError("Unsafe release archive entry")
            seen.add(member.name)
            if path.parts[0] not in ("norman-acceptance.jar", "web"):
                raise ValueError("Release may contain only the application JAR and web files")
            if path.parts[0] == "norman-acceptance.jar" and (len(path.parts) != 1 or not member.isfile()):
                raise ValueError("Invalid application JAR")
            total += member.size
            if total > MAX_EXPANDED or member.size > MAX_BUNDLE:
                raise ValueError("Expanded release exceeds limits")
            target = destination.joinpath(*path.parts)
            if member.isdir():
                directory(target)
                continue
            directory(target.parent)
            with archive.extractfile(member) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output, 65536)
            target.chmod(0o644)
            if target.stat().st_size != member.size:
                raise ValueError("Truncated release entry")
    for required in ("norman-acceptance.jar", "web/mobile/index.html", "web/admin/index.html"):
        if not (destination / required).is_file():
            raise ValueError("Release is missing required application files")


class Deployer:
    def __init__(self, base=Path("/opt/norman-acceptance"), state=Path("/var/lib/norman-deploy")):
        self.base = Path(base).resolve()
        self.state = Path(state).resolve()
        self.bundles = self.state / "bundles"
        self.releases = self.base / "releases"

    @contextlib.contextmanager
    def lock(self):
        with (self.state / "operation.lock").open("a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError("Another deployment operation is running") from None
            yield

    def upload(self, sha, size, source):
        if not re.fullmatch(SHA, sha) or not 0 < size <= MAX_BUNDLE:
            raise ValueError("Invalid upload metadata")
        if sum(p.stat().st_size for p in self.bundles.iterdir()) + size > 512 * 1024 * 1024:
            raise ValueError("Deployment upload quota reached; administrator cleanup required")
        fd, name = tempfile.mkstemp(prefix="upload-", dir=self.bundles)
        temporary = Path(name)
        try:
            digest = hashlib.sha256()
            with os.fdopen(fd, "wb") as output:
                remaining = size
                while remaining:
                    chunk = source.read(min(65536, remaining))
                    if not chunk:
                        raise ValueError("Incomplete upload")
                    output.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
                if source.read(1):
                    raise ValueError("Upload exceeds declared size")
                output.flush()
                os.fsync(output.fileno())
            if digest.hexdigest() != sha:
                raise ValueError("Upload checksum mismatch")
            os.replace(temporary, self.bundles / (sha + ".tar.gz"))
            return {"uploaded": True, "sha256": sha, "bytes": size}
        finally:
            temporary.unlink(missing_ok=True)

    def current(self):
        current = (self.base / "current").resolve(strict=True)
        if current.parent != self.releases.resolve(strict=True) or not current.is_dir():
            raise ValueError("Current release is outside the project release directory")
        return current

    def healthy(self):
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open("http://127.0.0.1:8091/actuator/health", timeout=2) as response:
                return json.loads(response.read(8192)).get("status") == "UP"
        except (OSError, ValueError):
            return False

    def status(self):
        return {"release": self.current().name, "healthy": self.healthy()}

    def switch(self, release):
        temporary = self.base / (".current-" + os.urandom(8).hex())
        try:
            temporary.symlink_to(release)
            os.replace(temporary, self.base / "current")
        finally:
            temporary.unlink(missing_ok=True)

    def restart(self):
        subprocess.run(["/usr/bin/systemctl", "restart", "norman-acceptance"],
                       check=True, capture_output=True, timeout=60)

    def await_health(self):
        for _ in range(45):
            if self.healthy():
                return True
            time.sleep(1)
        return False

    def deploy(self, release, sha):
        if not re.fullmatch(RELEASE, release) or not re.fullmatch(SHA, sha):
            raise ValueError("Invalid deployment metadata")
        bundle = self.bundles / (sha + ".tar.gz")
        with bundle.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != sha:
                raise ValueError("Stored upload checksum mismatch")
        previous = self.current()
        target = self.releases / release
        if target.exists():
            if previous == target and (target / ".bundle-sha256").read_text() == sha:
                return self.status() | {"alreadyDeployed": True}
            raise ValueError("Release identifier already exists")
        stage = Path(tempfile.mkdtemp(prefix=".stage-", dir=self.releases))
        try:
            safe_extract(bundle, stage)
            (stage / ".bundle-sha256").write_text(sha)
            stage.chmod(0o755)
            os.rename(stage, target)
        finally:
            if stage.exists():
                shutil.rmtree(stage)
        try:
            self.switch(target)
            self.restart()
            if not self.await_health():
                raise ValueError("New release failed its health check")
        except Exception:
            self.switch(previous)
            self.restart()
            if not self.await_health():
                raise RuntimeError("Previous release restored but service requires administrator attention") from None
            raise ValueError("Deployment failed; previous application release restored") from None
        return self.status() | {"deployed": True, "sha256": sha}

    def run(self, command, source):
        action = parse_command(command)
        with self.lock():
            if action[0] == "upload":
                return self.upload(action[1], action[2], source)
            if action[0] == "deploy":
                return self.deploy(action[1], action[2])
            return self.status()


def main():
    if os.geteuid() != 0 or len(sys.argv) != 2:
        raise SystemExit("This fixed worker must be invoked by the approved deployment account")
    os.umask(0o077)
    resource.setrlimit(resource.RLIMIT_AS, (512 * 1024 * 1024, 512 * 1024 * 1024))
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError("Operation timed out")))
    signal.alarm(180)
    try:
        result = Deployer().run(sys.argv[1], sys.stdin.buffer)
        code = 0
    except Exception as error:
        result = {"error": str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__}
        code = 1
    with Path("/var/log/norman-deploy.log").open("a") as log:
        log.write(json.dumps({"at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                              "actor": os.environ.get("SUDO_USER", "root"), "result": result}) + "\n")
    print(json.dumps(result), flush=True)
    raise SystemExit(code)


if __name__ == "__main__":
    main()
