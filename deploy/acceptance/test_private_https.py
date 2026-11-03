import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("private_https", Path(__file__).with_name("enable-private-https.py"))
gateway = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gateway)


class RestrictionsTest(unittest.TestCase):
    def test_only_preview_match_is_extended_and_retries_are_idempotent(self):
        before = ("PasswordAuthentication no\nMatch User other\n  PermitOpen 127.0.0.1:8088\n"
                  "Match User norman-preview\n  AllowTcpForwarding local\n"
                  "  PermitOpen 127.0.0.1:8088\n  ForceCommand /usr/bin/false\n"
                  "Match User norman-deploy\n  AllowTcpForwarding no\n")
        after = gateway.extend_ssh(before)
        self.assertEqual(after.count("PermitOpen 127.0.0.1:8088 127.0.0.1:8443"), 1)
        self.assertIn("Match User other\n  PermitOpen 127.0.0.1:8088\n", after)
        self.assertEqual(gateway.extend_ssh(after), after)
        self.assertEqual(after.count("ForceCommand /usr/bin/false"), 1)
        self.assertTrue(after.endswith("Match User norman-deploy\n  AllowTcpForwarding no\n"))

    def test_unexpected_preview_rules_are_not_silently_replaced(self):
        for before in ("PermitOpen 127.0.0.1:8088\n",
                       "Match User norman-preview\n  PermitOpen any\n",
                       "Match User norman-preview other\n  PermitOpen 127.0.0.1:8088\n"):
            with self.assertRaises(ValueError):
                gateway.extend_ssh(before)

    def test_all_keys_keep_shell_denial_and_original_destination(self):
        before = ('restrict,port-forwarding,permitopen="127.0.0.1:8088",'
                  'command="/usr/bin/false" ssh-ed25519 AAAA test\n')
        after = gateway.extend_keys(before)
        self.assertIn('permitopen="127.0.0.1:8088",permitopen="127.0.0.1:8443"', after)
        self.assertTrue(after.startswith("restrict,port-forwarding,"))
        self.assertTrue(after.endswith('command="/usr/bin/false" ssh-ed25519 AAAA test\n'))
        self.assertEqual(gateway.extend_keys(after), after)
        with self.assertRaises(ValueError):
            gateway.extend_keys("ssh-ed25519 AAAA test\n")


if __name__ == "__main__":
    unittest.main()
