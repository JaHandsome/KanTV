"""公开空配置回归；只读取代码默认值和公开样例。"""
import json
from pathlib import Path
import sys
import tempfile
import unittest

SERVER = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER))

from app.config import Config, DEFAULT_SETTINGS
from tools.init_config import initialize


class PublicConfigTests(unittest.TestCase):
    def test_example_is_generated_from_safe_defaults(self):
        example = json.loads((SERVER / 'config/settings.example.json').read_text())
        self.assertEqual(DEFAULT_SETTINGS, example)
        self.assertEqual('localhost', example['http']['snippet_host'])
        self.assertEqual({'enabled': False, 'feedback_token': ''}, example['hub'])
        self.assertEqual({}, example['signed_url_params_by_host'])

    def test_initialization_uses_empty_sources_and_disabled_hub(self):
        with tempfile.TemporaryDirectory() as root:
            initialize(Path(root) / 'config')
            cfg = Config(root)
            self.assertFalse(cfg.settings['hub']['enabled'])
            self.assertEqual('', cfg.settings['hub']['feedback_token'])
            self.assertEqual([], cfg.repos['repos'])
            self.assertEqual([], cfg.url_lists['lists'])
            self.assertEqual([], cfg.channels['channels'])
            self.assertEqual('http://localhost:8420/live.txt', cfg.snippet_url())

    def test_existing_configuration_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            destination = Path(root) / 'config'
            destination.mkdir()
            existing = destination / 'settings.json'
            existing.write_text('preserve-existing-bytes')
            with self.assertRaises(FileExistsError):
                initialize(destination)
            self.assertEqual('preserve-existing-bytes', existing.read_text())
            self.assertEqual([existing], list(destination.iterdir()))

    def test_dangling_symlink_is_never_followed(self):
        with tempfile.TemporaryDirectory() as root:
            destination = Path(root) / 'config'
            destination.mkdir()
            (destination / 'repos.json').symlink_to(Path(root) / 'absent')
            with self.assertRaises(FileExistsError):
                initialize(destination)
            self.assertFalse((Path(root) / 'absent').exists())


if __name__ == '__main__':
    unittest.main()
