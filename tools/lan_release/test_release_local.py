"""通用发布入口的合成回归；所有构建/SSH/凭据工具均被替身拦截。"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ReleaseLocalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.script = self.root / 'tools/lan_release/release_local.sh'
        self.script.parent.mkdir(parents=True)
        shutil.copyfile(Path(__file__).with_name('release_local.sh'), self.script)
        binary = self.root / 'bin'
        binary.mkdir()
        self.calls = self.root / 'calls'
        for command in ('python3', 'ssh', 'security', 'gradle'):
            executable = binary / command
            executable.write_text(
                '#!/bin/bash\n'
                'printf "%s\\0" "${0##*/}" "$@" >> "$CALL_LOG"\n'
                'printf "\\n" >> "$CALL_LOG"\n' +
                ('exit 0\n' if command == 'python3' else 'exit 91\n'))
            executable.chmod(0o755)
        keystore = self.root / 'synthetic-keystore'
        keystore.touch()
        self.env = {
            'PATH': str(binary) + os.pathsep + '/usr/bin:/bin',
            'HOME': str(self.root),
            'CALL_LOG': str(self.calls),
            'KANTV_RELEASE_SSH_HOST': 'operator@example.invalid',
            'KANTV_RELEASE_DIR': '/srv/releases',
            'KANTV_NAS_HOSTNAME': 'example-host',
            'KANTV_RELEASE_APPLICATION_ID': 'com.example.tv',
            'KANTV_RELEASE_CERT_SHA256': 'a' * 64,
            'KANTV_RELEASE_KEYSTORE_FILE': str(keystore),
            'KANTV_RELEASE_KEYSTORE_PASSWORD': 'synthetic-password',
            'KANTV_RELEASE_KEY_ALIAS': 'example-key',
            'KANTV_RELEASE_KEY_PASSWORD': 'synthetic-password',
        }

    def run_script(self, *args, env=None):
        return subprocess.run(['/bin/bash', str(self.script), *args],
                              env=self.env if env is None else env,
                              capture_output=True, text=True)

    def assert_no_actions(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.calls.exists())
        self.assertFalse((self.root / 'app').exists())
        self.assertNotIn('synthetic-password', result.stdout + result.stderr)

    def test_each_missing_required_parameter_fails_before_any_tool(self):
        for name in self.env:
            if not name.startswith('KANTV_'):
                continue
            with self.subTest(name=name):
                env = self.env.copy()
                del env[name]
                result = self.run_script(env=env)
                self.assert_no_actions(result)
                self.assertIn(name, result.stderr)

    def test_default_prepares_candidate_without_activation(self):
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        calls = [line.split(b'\0')[:-1] for line in self.calls.read_bytes().splitlines()]
        self.assertEqual(4, len(calls))
        self.assertTrue(all(call[0] == b'python3' for call in calls))
        self.assertIn(b'tools/lan_release/publish_dual.py', calls[-1])
        self.assertIn(b'operator@example.invalid', calls[-1])
        self.assertIn(b'com.example.tv', calls[-1])
        self.assertNotIn(b'--activate', calls[-1])

    def test_activation_requires_explicit_flag(self):
        result = self.run_script('--activate')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b'--activate\0', self.calls.read_bytes())

    def test_invalid_options_do_not_run_tools(self):
        for args in [('--unknown',), ('--config',), ('--config', ''), ('--config', 'relative.sh')]:
            with self.subTest(args=args):
                self.assert_no_actions(self.run_script(*args))

    def test_configuration_commands_never_run_even_with_missing_parameters(self):
        config = self.root / 'private-config.sh'
        config.write_text('security synthetic-credential-request\nssh synthetic-target\n')
        for missing in (None, 'KANTV_RELEASE_DIR'):
            with self.subTest(missing=missing):
                env = self.env.copy()
                if missing:
                    del env[missing]
                result = self.run_script('--config', str(config), env=env)
                self.assert_no_actions(result)
                self.assertEqual(2, result.returncode)

    def test_invalid_certificate_and_missing_keystore_fail_locally(self):
        for name, value in [('KANTV_RELEASE_CERT_SHA256', 'not-a-pin'),
                            ('KANTV_RELEASE_KEYSTORE_FILE', str(self.root / 'absent')),
                            ('KANTV_RELEASE_DIR', 'relative'),
                            ('KANTV_RELEASE_SSH_HOST', '-option')]:
            with self.subTest(name=name):
                env = dict(self.env, **{name: value})
                self.assert_no_actions(self.run_script(env=env))

    def test_unpaired_endpoint_or_token_fails_before_any_tool(self):
        for name in ['KANTV_UPDATE_ENDPOINT', 'KANTV_UPDATE_TOKEN',
                     'KANTV_HUB_ENDPOINT', 'KANTV_HUB_FEEDBACK_TOKEN']:
            with self.subTest(name=name):
                self.assert_no_actions(self.run_script(env=dict(self.env, **{name: 'synthetic'})))


if __name__ == '__main__':
    unittest.main()
