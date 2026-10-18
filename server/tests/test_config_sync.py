# -*- coding: utf-8 -*-
"""配置热加载的正式装配、分域容错与并发读取回归；只用临时虚构配置。"""
import copy
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app import main
from app.config import Config, _DOMAINS
from app.db import Database, utcnow
from app.hub import Hub
from app.parser import ChannelParser
from app.scheduler import Scheduler, TASK_ORDER
from app.status import StatusProvider


class TestConfigSync(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='iptv-config-sync-')
        self.root = self.tmp.name
        os.makedirs(os.path.join(self.root, 'config'))
        for _, filename, default in _DOMAINS:
            self.write(filename, copy.deepcopy(default))
        self.write('settings.json', {
            'http': {'host': '127.0.0.1', 'port': 0, 'snippet_host': 'example.invalid'},
            'hub': {'enabled': True},
        })
        self.old_channels = {'channels': ['测试一台'], 'aliases': {'旧名': '测试一台'},
                             'channel_groups': {'测试一台': '测试组'}}
        self.new_channels = {'channels': ['测试一台', '测试二台'],
                             'aliases': {'新臺': '测试二台'}, 't2s_map': {'臺': '台'},
                             'channel_groups': {'测试一台': '测试组', '测试二台': '新增组'}}
        self.write('channels.json', self.old_channels)
        self.addCleanup(self.tmp.cleanup)

    def write(self, filename, value):
        with open(os.path.join(self.root, 'config', filename), 'w', encoding='utf-8') as f:
            json.dump(value, f, ensure_ascii=False)

    def assemble(self):
        cfg = Config(self.root)
        db = Database(cfg.db_path)
        db.migrate(cfg.settings)
        self.addCleanup(db.close)
        return cfg, Hub(cfg, db), StatusProvider(cfg, db)

    def quiet_tick(self, scheduler):
        scheduler.last_run.update({task: time.time() for task in TASK_ORDER})
        scheduler.db.set_meta('last_cleanup', str(time.time()))
        with patch.object(scheduler, '_tick_fns', return_value={task: lambda: None
                                                              for task in TASK_ORDER}):
            scheduler.tick()

    def test_main_tick_updates_hub_and_status_without_restart(self):
        """执行真正 main 装配，只替换阻塞/外部动作，防手工共享 Config 掩盖入口错误。"""
        owner = self
        captured = {}

        class LocalHttp:
            def __init__(self, cfg, status, hub):
                captured.update(cfg=cfg, status=status, hub=hub)

            def serve_forever(self):
                cfg, status, hub = (captured[k] for k in ('cfg', 'status', 'hub'))
                scheduler = status.scheduler
                owner.addCleanup(scheduler.db.close)
                owner.write('channels.json', owner.new_channels)
                owner.quiet_tick(scheduler)
                owner.assertEqual(hub.channel_ids(), ['测试一台', '测试二台'])
                owner.assertEqual(hub.resolve_channel_id('新台'), '测试二台')
                owner.assertIs(scheduler.cfg, cfg)
                owner.assertEqual(status.api_status()['coverage']['total'], 2)
                owner.assertIn('测试二台', status.render_html())
                payload = hub.build_subscription()
                owner.assertEqual([c['channel_id'] for c in payload['channels']],
                                  ['测试一台', '测试二台'])

            def shutdown(self):
                pass

        with patch.object(main, 'ROOT', self.root), \
                patch.object(main, 'setup_logging'), patch.object(main, 'seed_if_empty'), \
                patch.object(main, 'HttpServer', LocalHttp), \
                patch.object(Scheduler, 'start'), patch.object(Scheduler, 'join'), \
                patch.object(main.signal, 'signal'), \
                patch.object(main.threading, 'excepthook'):
            self.assertEqual(main.main([]), 0)

    def test_reload_preserves_previous_domain_references(self):
        """并发读者已拿到的完整字典不应被 clear/update 改成空或下一版本。"""
        cfg, _, _ = self.assemble()
        previous = cfg.channels
        expected = copy.deepcopy(previous)
        self.write('channels.json', self.new_channels)
        cfg.reload()
        self.assertEqual(previous, expected)
        self.assertEqual(cfg.channels['channels'], self.new_channels['channels'])

    def test_corrupt_missing_and_wrong_root_keep_last_valid_channels(self):
        cfg, hub, status = self.assemble()
        self.write('channels.json', self.new_channels)
        cfg.reload()
        for bad in ('{broken', '[]', None):
            with self.subTest(bad=bad):
                path = os.path.join(self.root, 'config', 'channels.json')
                if bad is None:
                    os.unlink(path)
                else:
                    with open(path, 'w', encoding='utf-8') as f:
                        f.write(bad)
                self.write('settings.json', {'http': {'snippet_host': 'example.invalid'},
                                             'hub': {'enabled': True}})
                cfg.reload()
                self.assertEqual(hub.channel_ids(), self.new_channels['channels'])
                self.assertEqual(hub.resolve_channel_id('新台'), '测试二台')
                self.assertEqual(status.api_status()['coverage']['total'], 2)

    def test_bad_settings_does_not_block_channel_and_alias_update(self):
        cfg, hub, status = self.assemble()
        old_settings = copy.deepcopy(cfg.settings)
        with open(os.path.join(self.root, 'config', 'settings.json'), 'w') as f:
            f.write('{broken')
        self.write('channels.json', self.new_channels)
        cfg.reload()
        self.assertEqual(cfg.settings, old_settings)
        self.assertEqual(hub.resolve_channel_id('新臺'), '测试二台')
        self.assertEqual(status.api_status()['coverage']['total'], 2)
        parser = ChannelParser(cfg)
        self.assertEqual(parser.normalize_channel('新臺'), '测试二台')
        self.assertEqual(parser._to_simplified('新臺'), '新台')
        self.new_channels['t2s_map'] = {'臺': '版'}
        self.write('channels.json', self.new_channels)
        cfg.reload()
        self.assertEqual(hub.resolve_channel_id('新版'), '测试二台')
        self.assertEqual(parser._to_simplified('新臺'), '新版')

    def test_inflight_subscription_cannot_mix_channel_generations(self):
        cfg, hub, _ = self.assemble()
        entered, release, started, reloaded = (threading.Event() for _ in range(4))
        results, errors = [], []
        original = hub._line_rows

        def paused_rows():
            entered.set()
            if not release.wait(5):
                raise RuntimeError('test subscription release timed out')
            return original()

        def read():
            try:
                results.append(hub.build_subscription())
            except Exception as error:
                errors.append(error)

        def reload():
            started.set()
            try:
                cfg.reload()
            except Exception as error:
                errors.append(error)
            finally:
                reloaded.set()

        with patch.object(hub, '_line_rows', paused_rows):
            reader = threading.Thread(target=read)
            writer = threading.Thread(target=reload)
            reader.start()
            try:
                self.assertTrue(entered.wait(5))
                self.write('channels.json', self.new_channels)
                writer.start()
                self.assertTrue(started.wait(5))
                self.assertFalse(reloaded.wait(0.1), 'reload published during subscription assembly')
            finally:
                release.set()
                reader.join(5)
                if writer.ident is not None:
                    writer.join(5)
        self.assertFalse(reader.is_alive())
        self.assertFalse(writer.is_alive())
        self.assertEqual(errors, [])
        payload = results[0]
        self.assertEqual([c['channel_id'] for c in payload['channels']], ['测试一台'])
        self.assertEqual({a['channel_id'] for a in payload['aliases']}, {'测试一台'})
        self.assertEqual([g['id'] for g in payload['groups']], ['测试组'])
        self.assertEqual(hub.channel_ids(), self.new_channels['channels'])

    def test_subscription_waits_for_signed_key_refresh_in_tick(self):
        cfg, hub, _ = self.assemble()
        scheduler = Scheduler(self.root, cfg=cfg)
        self.addCleanup(scheduler.db.close)
        url = 'https://example.invalid/live.m3u8?test_signature=first'
        old_key = ChannelParser.url_key(url, set())
        hub.db.execute(
            'INSERT INTO sources(url, url_hash, url_key, channel_name, channel_key, status, '
            'av, profile, container, first_seen, last_seen) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
            (url, ChannelParser.url_hash(url), old_key, '测试一台', '测试一台', 'active', 'video',
             'direct', 'hls', utcnow(), utcnow()))
        main.schema.ensure_url_keys_fresh(scheduler.db, cfg.settings)
        self.write('settings.json', {'signed_url_params': ['test_signature']})
        entered, release, started, done = (threading.Event() for _ in range(4))
        results, errors = [], []
        original = main.schema.ensure_url_keys_fresh

        def paused_refresh(db, settings):
            entered.set()
            if not release.wait(5):
                raise RuntimeError('test key refresh release timed out')
            return original(db, settings)

        def tick():
            try:
                self.quiet_tick(scheduler)
            except Exception as error:
                errors.append(error)

        def read():
            started.set()
            try:
                results.append(hub.build_subscription(caps='hls-ts'))
            except Exception as error:
                errors.append(error)
            finally:
                done.set()

        with patch.object(main.schema, 'ensure_url_keys_fresh', paused_refresh):
            writer = threading.Thread(target=tick)
            reader = threading.Thread(target=read)
            writer.start()
            try:
                self.assertTrue(entered.wait(5))
                reader.start()
                self.assertTrue(started.wait(5))
                self.assertFalse(done.wait(0.1), 'subscription bypassed unfinished key refresh')
            finally:
                release.set()
                writer.join(5)
                if reader.ident is not None:
                    reader.join(5)
        self.assertFalse(writer.is_alive())
        self.assertFalse(reader.is_alive())
        self.assertEqual(errors, [])
        payload = results[0]
        self.assertEqual(payload['signed_params']['global'], ['test_signature'])
        expected_key = ChannelParser.url_key(url, {'test_signature'})
        self.assertNotEqual(old_key, expected_key)
        self.assertEqual(payload['channels'][0]['routes'][0]['url_key'], expected_key)

    def test_failed_key_refresh_keeps_settings_and_retries_without_losing_channels(self):
        cfg, hub, _ = self.assemble()
        scheduler = Scheduler(self.root, cfg=cfg)
        self.addCleanup(scheduler.db.close)
        previous_settings = cfg.settings
        previous_signed_params = hub.signed_params()
        self.write('settings.json', {'signed_url_params': ['test_signature']})
        self.write('channels.json', self.new_channels)
        with patch.object(main.schema, 'ensure_url_keys_fresh', side_effect=RuntimeError('test failure')):
            self.quiet_tick(scheduler)
        self.assertIs(cfg.settings, previous_settings)
        self.assertEqual(hub.signed_params(), previous_signed_params)
        self.assertEqual(hub.channel_ids(), self.new_channels['channels'])
        self.assertEqual(hub.resolve_channel_id('新台'), '测试二台')
        self.quiet_tick(scheduler)
        self.assertEqual(hub.signed_params()['global'], ['test_signature'])

    def test_feedback_batch_keeps_one_alias_generation(self):
        cfg, hub, _ = self.assemble()
        original = hub._freeze_contract
        calls = []

        def reload_between_results(item):
            if not calls:
                channels = copy.deepcopy(self.new_channels)
                channels['aliases']['旧名'] = '测试二台'
                self.write('channels.json', channels)
                cfg.reload()
            calls.append(item)
            return original(item)

        with patch.object(hub, '_freeze_contract', reload_between_results):
            counts = hub.record_feedback({'results': [{'channel_id': '旧名'}, {'channel_id': '旧名'}]})
        self.assertEqual(counts['unknown_channel'], 0)
        self.assertEqual([row['channel_id'] for row in hub.db.query(
            'SELECT channel_id FROM playback_reports ORDER BY id')], ['测试一台', '测试一台'])


if __name__ == '__main__':
    unittest.main()
