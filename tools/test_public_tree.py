"""Public guard checks use synthetic inputs and never read credentials."""
import unittest
import subprocess
import tempfile
from pathlib import Path
from check_public_tree import violations, public_paths


class PublicTreeTests(unittest.TestCase):
    def test_private_material_is_rejected_without_echoing_values(self):
        self.assertIn((0, 'private-file'), violations('server/config/settings.json', b'{}'))
        self.assertIn((1, 'personal-path'), violations('docs/setup.md', b'/' + b'Users/owner/private'))
        self.assertIn((1, 'secret-format'), violations('app/config.txt', b'gh' + b'p_' + b'x' * 30))
        self.assertIn((1, 'private-deployment-address'), violations('app/config.txt', b'192.168.77.4'))

    def test_force_added_ignored_private_file_cannot_bypass_commit_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root/'.gitignore').write_text('.env\n')
            (root/'.env').write_text('synthetic')
            subprocess.run(['git', 'add', '.gitignore'], cwd=root, check=True)
            subprocess.run(['git', 'add', '-f', '.env'], cwd=root, check=True)
            self.assertIn('.env', public_paths(root))
            self.assertNotIn('.env', public_paths(root, export_candidate=True))
            self.assertIn((0, 'private-file'), violations('.env', b'synthetic'))

    def test_generic_examples_and_address_policy_tests_are_preserved(self):
        self.assertEqual([], violations('docs/setup.md', b'http://192.168.1.10:8420'))
        self.assertEqual([], violations('server/tests/test_net.py', b'192.168.77.4'))
        self.assertEqual([], violations('server/config/settings.example.json', b'{}'))
        self.assertEqual([], violations('app/src/main/assets/licenses/FFmpeg-LGPL-2.1.txt', b'license'))


if __name__ == '__main__':
    unittest.main()
