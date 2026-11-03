"""Isolated filesystem/protocol tests; never connect to cloud or restart a service."""
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("deploy_worker", Path(__file__).with_name("ssh-deploy-worker.py"))
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


def archive(extra=None):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as bundle:
        for name in ("norman-acceptance.jar", "web/mobile/index.html", "web/admin/index.html"):
            entry = tarfile.TarInfo(name)
            entry.size = 4
            entry.mode = 0o7777
            bundle.addfile(entry, io.BytesIO(b"test"))
        if extra:
            extra.size = 0
            bundle.addfile(extra, io.BytesIO())
    return output.getvalue()


class DeployTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.deploy = worker.Deployer(self.root / "app", self.root / "state")
        self.deploy.releases.mkdir(parents=True)
        self.deploy.bundles.mkdir(parents=True)
        self.old = self.deploy.releases / "uat-existing"
        self.old.mkdir()
        (self.deploy.base / "current").symlink_to(self.old)
        self.restarts = []
        self.deploy.restart = lambda: self.restarts.append(self.deploy.current().name)
        self.deploy.healthy = lambda: True

    def tearDown(self):
        self.temporary.cleanup()

    def upload(self, data):
        sha = hashlib.sha256(data).hexdigest()
        self.deploy.run(f"upload {sha} {len(data)}", io.BytesIO(data))
        return sha

    def test_only_fixed_commands_and_safe_arguments_are_accepted(self):
        sha = "a" * 64
        self.assertEqual(worker.parse_command(f"deploy uat-test-01 {sha}")[0], "deploy")
        for command in ("sh", "sudo id", "status\n", "status; id", "sftp", "scp -t /etc", f"deploy ../root {sha}", f"deploy uat-test-01 {sha};id", f"upload {sha} 999999999", f"upload {sha} -1"):
            with self.subTest(command=command), self.assertRaises(ValueError):
                worker.parse_command(command)

    def test_upload_validates_actual_length_and_hash_and_removes_partials(self):
        data = b"approved application bytes"
        sha = self.upload(data)
        self.assertEqual((self.deploy.bundles / (sha + ".tar.gz")).read_bytes(), data)
        for supplied, size, content in (("a" * 64, len(data), data), (sha, len(data) + 1, data), (sha, len(data) - 1, data)):
            with self.assertRaises(ValueError):
                self.deploy.upload(supplied, size, io.BytesIO(content))
        self.assertEqual(list(self.deploy.bundles.glob("upload-*")), [])

    def test_archive_rejects_traversal_links_devices_and_extra_scripts(self):
        entries = [tarfile.TarInfo(name) for name in ("/etc/cron.d/escape", "web/../../escape", "web/a/../escape", "web\\escape", "deploy/run.sh", "web/bad\nname")]
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.FIFOTYPE):
            entry = tarfile.TarInfo("web/link")
            entry.type = kind
            entry.linkname = "/etc"
            entries.append(entry)
        for index, entry in enumerate(entries):
            with self.subTest(entry=entry.name, kind=entry.type), self.assertRaises(ValueError):
                worker.safe_extract(io.BytesIO(archive(entry)), self.root / f"extract-{index}")
        self.assertFalse((self.root / "escape").exists())

    def test_directory_readability_and_executable_modes_do_not_come_from_archive(self):
        destination = self.root / "extract"
        previous = os.umask(0o077)
        try:
            worker.safe_extract(io.BytesIO(archive()), destination)
        finally:
            os.umask(previous)
        self.assertEqual((destination / "web/mobile").stat().st_mode & 0o7777, 0o755)
        self.assertEqual((destination / "norman-acceptance.jar").stat().st_mode & 0o7777, 0o644)

    def test_failed_release_restores_previous_application_and_restarts_it(self):
        sha = self.upload(archive())
        results = iter([False, True])
        self.deploy.await_health = lambda: next(results)
        with self.assertRaisesRegex(ValueError, "previous application release restored"):
            self.deploy.run(f"deploy uat-failure-01 {sha}", io.BytesIO())
        self.assertEqual(self.deploy.current(), self.old)
        self.assertEqual(self.restarts, ["uat-failure-01", "uat-existing"])

    def test_deployment_is_idempotent_and_rechecks_stored_checksum(self):
        sha = self.upload(archive())
        self.deploy.await_health = lambda: True
        result = self.deploy.run(f"deploy uat-success-01 {sha}", io.BytesIO())
        self.assertTrue(result["deployed"])
        self.assertTrue(self.deploy.run(f"deploy uat-success-01 {sha}", io.BytesIO())["alreadyDeployed"])
        self.assertEqual(self.restarts, ["uat-success-01"])
        (self.deploy.bundles / (sha + ".tar.gz")).write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.deploy.run(f"deploy uat-tampered-01 {sha}", io.BytesIO())
        self.assertEqual(self.deploy.current().name, "uat-success-01")

    def test_concurrent_operations_are_rejected_and_external_current_is_rejected(self):
        with self.deploy.lock(), self.assertRaisesRegex(ValueError, "operation is running"):
            self.deploy.run("status", io.BytesIO())
        (self.deploy.base / "current").unlink()
        (self.deploy.base / "current").symlink_to(self.root)
        with self.assertRaises(ValueError):
            self.deploy.current()


if __name__ == "__main__":
    unittest.main()
