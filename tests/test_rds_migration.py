import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('rds_migrate', Path(__file__).parents[1] / 'deploy/rds/migrate.py')
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)


class RdsMigrationTests(unittest.TestCase):
    def test_private_endpoint_rejects_public_loopback_and_mixed_dns(self):
        host = 'rm-example.mysql.rds.aliyuncs.com'
        for addresses, accepted in [(['192.168.10.4'], True), (['172.24.0.4'], True),
                                    (['127.0.0.1'], False), (['8.8.8.8'], False),
                                    (['192.168.10.4', '8.8.8.8'], False)]:
            with patch.object(migration.socket, 'getaddrinfo', return_value=[(None, None, None, None, (ip, 3306)) for ip in addresses]):
                if accepted:
                    migration.private_rds_host(host)
                else:
                    with self.assertRaises(ValueError):
                        migration.private_rds_host(host)
        with self.assertRaises(ValueError):
            migration.private_rds_host('rm-example.mysql.rds.aliyuncs.com.evil.example')

    def test_preflight_never_overwrites_nonempty_database(self):
        with patch.object(migration, 'credentials', return_value={}), \
             patch.object(migration, 'query', return_value=b'norman_acceptance\nSsl_cipher\tTLS_AES_256_GCM_SHA384\n'), \
             patch.object(migration, 'tables', return_value=['club_order']):
            with self.assertRaisesRegex(ValueError, 'empty dedicated database'):
                migration.preflight()

    def test_manifest_refuses_sql_in_table_identifiers(self):
        with patch.object(migration, 'query', return_value=b'club_order`; DROP TABLE club_order;--\n'):
            with self.assertRaises(ValueError):
                migration.tables('target')

    def test_failed_import_restores_source_config_and_leaves_database_intact(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / 'config'; config.mkdir()
            api = config / 'api.env'
            original = 'DB_URL=jdbc:mysql://127.0.0.1:3308/norman_acceptance?sslMode=REQUIRED\nDB_PASSWORD=synthetic-test-value\n'
            api.write_text(original)
            backups = root / 'backups'; backups.mkdir()
            flag = root / 'maintenance'
            nginx = root / 'nginx.conf'; nginx.write_text(str(flag))
            dropin = root / 'service.d/20-rds.conf'
            with patch.multiple(migration, CONFIG=config, BACKUPS=backups, FLAG=flag, NGINX=nginx, DROPIN=dropin), \
                 patch.object(migration, 'preflight', return_value={}), \
                 patch.object(migration, 'backup', return_value=root / 'dump.sql.gz'), \
                 patch.object(migration, 'manifest', return_value={'club_order': {'rows': 2, 'sha256': 'sample'}}), \
                 patch.object(migration, 'import_dump', side_effect=RuntimeError('synthetic import failure')), \
                 patch.object(migration, 'health'), patch.object(migration, 'run') as commands:
                with self.assertRaises(RuntimeError):
                    migration.migrate()
                self.assertEqual(api.read_text(), original)
                self.assertFalse(flag.exists())
                self.assertTrue(list(backups.glob('*/maintenance-finished')))
                self.assertFalse(dropin.exists())
                self.assertIn(unittest.mock.call(['systemctl', 'start', migration.SERVICE]), commands.call_args_list)
                self.assertTrue(all(call.args[0][0] == 'systemctl' for call in commands.call_args_list))

    def test_environment_values_preserve_special_characters_and_reject_newlines(self):
        self.assertEqual(migration.quote_environment('a"b\\c$#'), '"a\\"b\\\\c$#"')
        for value in ['a\nb', 'a\rb', 'a\x00b']:
            with self.assertRaises(ValueError):
                migration.quote_environment(value)


if __name__ == '__main__':
    unittest.main()
