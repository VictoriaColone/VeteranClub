import importlib.util
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('rds_password', Path(__file__).parents[1] / 'deploy/rds/rotate-password.py')
rotation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rotation)


class PasswordRotationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.config = root / 'config'
        self.config.mkdir()
        self.backups = root / 'backups'
        self.flag = root / 'maintenance'
        self.old = 'SyntheticOld123!'
        self.new = 'SyntheticNew456$%+=()'
        self.api = ('DB_URL="jdbc:mysql://' + rotation.HOST + ':3306/norman_acceptance?sslMode=VERIFY_IDENTITY"\n'
                    'DB_USERNAME="norman_service"\nDB_PASSWORD="' + self.old + '"\n'
                    'ACCEPTANCE_RDS_HOST="' + rotation.HOST + '"\nOTHER_SECRET="unchanged$%"\n')
        self.rds = 'RDS_HOST=' + rotation.HOST + '\nRDS_USERNAME=norman_service\nRDS_PASSWORD=' + self.old + '\n'
        for name, text in [('api.env', self.api), ('rds.env', self.rds)]:
            (self.config / name).write_text(text)
        for name, value in [('CONFIG', self.config), ('BACKUPS', self.backups), ('FLAG', self.flag)]:
            context = patch.object(rotation, name, value)
            context.start()
            self.addCleanup(context.stop)
        # The tests run without root; test the ownership check separately below.
        context = patch.object(rotation, 'private_file', lambda path: path.read_text())
        context.start()
        self.addCleanup(context.stop)
        context = patch.object(rotation, 'verify_connection')
        self.connection = context.start()
        self.addCleanup(context.stop)

    def stage(self, confirmation=None):
        with patch.object(rotation.getpass, 'getpass', side_effect=[self.new, confirmation or self.new]):
            rotation.stage()

    def test_stage_leaves_active_configuration_unchanged_and_private(self):
        self.stage()
        self.assertEqual((self.config / 'api.env').read_text(), self.api)
        self.assertEqual((self.config / 'rds.env').read_text(), self.rds)
        pending = self.config / 'rds-password.pending.json'
        self.assertEqual(stat.S_IMODE(pending.stat().st_mode), 0o600)
        self.assertFalse(self.flag.exists())

    def test_rejects_confirmation_mismatch_without_saving(self):
        with self.assertRaises(ValueError):
            self.stage('Wrong456!')
        self.assertFalse((self.config / 'rds-password.pending.json').exists())

    def test_rejects_injection_and_duplicate_stage(self):
        self.new = 'New123!\nDB_USERNAME=other'
        with self.assertRaises(ValueError):
            self.stage()
        self.new = 'SyntheticNew456!'
        self.stage()
        with self.assertRaises(ValueError):
            self.stage()

    def test_apply_updates_both_passwords_preserves_other_secrets(self):
        self.stage()
        with patch.object(rotation, 'run') as run, patch.object(rotation, 'check_health'):
            rotation.apply()
        api = rotation.parse_env((self.config / 'api.env').read_text(), quoted=True)
        rds = rotation.parse_env((self.config / 'rds.env').read_text())
        self.assertEqual(api['DB_PASSWORD'], self.new)
        self.assertEqual(rds['RDS_PASSWORD'], self.new)
        self.assertEqual(api['OTHER_SECRET'], 'unchanged$%')
        self.assertFalse(self.flag.exists())
        self.assertFalse((self.config / 'rds-password.pending.json').exists())
        self.assertEqual(run.call_args_list[0].args[0], ['systemctl', 'stop', rotation.SERVICE])
        self.assertEqual(run.call_args_list[1].args[0], ['systemctl', 'start', rotation.SERVICE])
        backup = next(self.backups.iterdir())
        self.assertEqual((backup / 'api.env.before').read_text(), self.api)
        self.assertTrue((backup / 'VERIFIED').exists())
        for name in ['api.env', 'rds.env']:
            self.assertEqual(stat.S_IMODE((self.config / name).stat().st_mode), 0o600)

    def test_changed_configuration_blocks_apply_before_maintenance(self):
        self.stage()
        (self.config / 'api.env').write_text(self.api + 'NEW_SETTING="changed"\n')
        with patch.object(rotation, 'run') as run, self.assertRaises(ValueError):
            rotation.apply()
        run.assert_not_called()
        self.assertFalse(self.flag.exists())

    def test_failed_health_retains_new_password_and_maintenance(self):
        self.stage()
        with patch.object(rotation, 'run'), patch.object(rotation, 'check_health', side_effect=RuntimeError), self.assertRaises(RuntimeError):
            rotation.apply()
        self.assertTrue(self.flag.exists())
        self.assertTrue((self.config / 'rds-password.pending.json').exists())
        api = rotation.parse_env((self.config / 'api.env').read_text(), quoted=True)
        self.assertEqual(api['DB_PASSWORD'], self.new)
        self.assertFalse(any(self.backups.glob('*/VERIFIED')))

    def test_failed_connection_leaves_running_application_untouched(self):
        self.stage()
        self.connection.side_effect = RuntimeError('not active yet')
        with patch.object(rotation, 'run') as run, self.assertRaises(RuntimeError):
            rotation.apply()
        run.assert_not_called()
        self.assertFalse(self.flag.exists())
        self.assertEqual((self.config / 'api.env').read_text(), self.api)
        self.assertEqual((self.config / 'rds.env').read_text(), self.rds)

    def interrupt_apply(self):
        self.stage()
        with patch.object(rotation, 'run'), patch.object(rotation, 'check_health', side_effect=RuntimeError), self.assertRaises(RuntimeError):
            rotation.apply()

    def test_resume_after_disconnect_uses_original_backup(self):
        self.interrupt_apply()
        with patch.object(rotation, 'run'), patch.object(rotation, 'check_health'):
            rotation.apply(resume=True)
        self.assertFalse(self.flag.exists())
        self.assertFalse((self.config / 'rds-password.pending.json').exists())
        self.assertEqual(len(list(self.backups.iterdir())), 1)
        self.assertEqual(next(self.backups.glob('*/api.env.before')).read_text(), self.api)

    def test_resume_refuses_unrelated_configuration_change(self):
        self.interrupt_apply()
        (self.config / 'api.env').write_text((self.config / 'api.env').read_text() + 'UNRELATED="change"\n')
        with patch.object(rotation, 'run') as run, self.assertRaises(ValueError):
            rotation.apply(resume=True)
        run.assert_not_called()
        self.assertTrue(self.flag.exists())

    def test_guard_rejects_other_instance(self):
        (self.config / 'rds.env').write_text(self.rds.replace(rotation.HOST, 'other.mysql.rds.aliyuncs.com'))
        with self.assertRaises(ValueError):
            self.stage()
        self.assertFalse((self.config / 'rds-password.pending.json').exists())


if __name__ == '__main__':
    unittest.main()
