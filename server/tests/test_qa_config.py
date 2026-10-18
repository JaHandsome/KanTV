# -*- coding: utf-8 -*-
"""QA 补充测试 1：配置容错（P0-6 无人值守前提）。

覆盖：
- 配置文件损坏（非法 JSON）/ 根节点非对象 / 文件缺失 -> 默认值兜底不崩溃；
- 部分字段缺失 -> 深合并补齐（缺什么补什么，已有值不丢）；
- reload 完整替换域（既有读者保留上一版本）。
"""
import os
import shutil
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from offline_fixtures import write_test_config  # noqa: E402
from app.config import (DEFAULT_RULES, DEFAULT_SETTINGS, Config,  # noqa: E402
                        _deep_merge, _load_json)


def make_root():
    """构造带完整 config/ 的临时项目根。"""
    tmp = tempfile.mkdtemp(prefix='iptv-qa-cfg-')
    write_test_config(tmp)
    return tmp


class TestConfigFaultTolerance(unittest.TestCase):
    def setUp(self):
        self.tmp = make_root()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, name, content):
        with open(os.path.join(self.tmp, 'config', name), 'w', encoding='utf-8') as f:
            f.write(content)

    def test_corrupted_settings_json_falls_back_to_defaults(self):
        """settings.json 写坏（截断 JSON）-> 全部默认值，进程不崩。"""
        self._write('settings.json', '{"http": {"port": 9999, "host"')
        cfg = Config(self.tmp)
        self.assertEqual(cfg.settings['http']['port'], DEFAULT_SETTINGS['http']['port'])
        self.assertEqual(cfg.settings['limits']['net_concurrency'], 16)
        self.assertEqual(cfg.settings['schedule']['probe_minutes'], 30)

    def test_root_not_object_falls_back(self):
        """根节点是数组 -> 默认值（含 P0 新增 unsupported_schemes 默认段）。"""
        self._write('rules.json', '["a", "b"]')
        cfg = Config(self.tmp)
        self.assertEqual(cfg.rules, DEFAULT_RULES)

    def test_missing_file_falls_back(self):
        """文件被误删 -> 默认值。"""
        os.remove(os.path.join(self.tmp, 'config', 'channels.json'))
        cfg = Config(self.tmp)
        self.assertEqual(cfg.channels['channels'], [])
        self.assertIn('epg_template', cfg.channels)   # 默认模板仍在

    def test_partial_fields_deep_merged(self):
        """只写 2 个字段 -> 其余补默认；已写的值保留。"""
        self._write('settings.json', '{"http": {"port": 9999}, "limits": {"timeout_s": 3}}')
        cfg = Config(self.tmp)
        self.assertEqual(cfg.settings['http']['port'], 9999)          # 覆盖生效
        self.assertEqual(cfg.settings['http']['host'], '0.0.0.0')     # 同级补齐
        self.assertEqual(cfg.settings['limits']['timeout_s'], 3)
        self.assertEqual(cfg.settings['limits']['net_concurrency'], 16)  # 同组其余补齐
        self.assertEqual(cfg.settings['schedule']['probe_minutes'], 30)  # 其他组不受影响

    def test_unicode_bom_or_garbage_falls_back(self):
        """编码异常（二进制垃圾）-> 默认值不崩。"""
        with open(os.path.join(self.tmp, 'config', 'repos.json'), 'wb') as f:
            f.write(b'\xff\xfe\x00binary garbage')
        cfg = Config(self.tmp)
        self.assertEqual(cfg.repos['repos'], [])

    def test_reload_replaces_domain_and_keeps_previous_reference(self):
        """热加载完整替换域；并发读者手中的上一版本不会被清空或改变。"""
        cfg = Config(self.tmp)
        ref = cfg.settings
        previous_port = ref['http']['port']
        self._write('settings.json', '{"http": {"port": 7777}}')
        cfg.reload()
        self.assertIsNot(cfg.settings, ref)
        self.assertEqual(ref['http']['port'], previous_port)
        self.assertEqual(cfg.settings['http']['port'], 7777)

    def test_path_resolution(self):
        cfg = Config(self.tmp)
        self.assertTrue(os.path.isabs(cfg.db_path))
        self.assertEqual(cfg.db_path, os.path.join(self.tmp, 'data', 'iptv.db'))

    def test_deep_merge_nested_dict(self):
        base = {'a': {'x': 1, 'y': 2}, 'b': 1}
        out = _deep_merge(base, {'a': {'y': 9}, 'c': 3})
        self.assertEqual(out, {'a': {'x': 1, 'y': 9}, 'b': 1, 'c': 3})
        self.assertEqual(base['a']['y'], 2)   # 不污染入参

    def test_load_json_missing_returns_default(self):
        d = _load_json(os.path.join(self.tmp, 'nope.json'), {'k': 1})
        self.assertEqual(d, {'k': 1})


if __name__ == '__main__':
    unittest.main()
