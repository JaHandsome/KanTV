# -*- coding: utf-8 -*-
"""QA(严过关) 独立验证套件：HK MPD/ClearKey 新链路的边角与反例。

独立于工程师自测（test_verifier_dash / test_url_lists / test_builder_media3）。
全部离线，零网络。

覆盖：
  A. 302 base 修复：不重定向源与改动前逐字节等价；重定向源基于 final_url；
     fetch_ex 与 fetch 的参数透传/异常语义/并发闸门一致。
  C. enabled=false 闸门：合成清单区分启用/禁用 -> 禁用项零网络；run() 级同样零请求。
  D. 挖矿红线：_MINER_PATTERNS/_MINER_HOME_PATTERN/_expand_home/parse_playlist
     不含 .mpd/.xml/.json，且只认 .m3u/.m3u8/.txt（源码级判据 + 行为级）。
  E. 密钥保真：用**另一条合成条目（翡翠台/J）**端到端 4 环节逐字节比对（含 ingest 真实路径）。
  F. parse_kodiprop 返回值语义（license_key 原始串 vs license_key_obj 对象）。
  B. probe_dash -> record() 在**瞬时失败**下是否保住清单 drm/kid（缺陷红灯用例）。
"""
import json
import os
import re
import shutil
import socket
import sys
import tempfile
import threading
import time
import unittest
from urllib.parse import urljoin

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.builder import OutputBuilder                            # noqa: E402
from offline_fixtures import (DRM_PLAYLIST, KEY_B64 as K_B64, KID_B64,
                              KID_HEX, KID_UUID, LICENSE)
from offline_fixtures import write_test_config, make_config  # noqa: E402
from app.config import Config                                    # noqa: E402
from app.db import SCHEMA_VERSION, Database, utcnow              # noqa: E402
from app.discovery import (RepoMinerProvider, SourceDiscovery,   # noqa: E402
                           UrlListProvider)
from app.net import NetClient                                    # noqa: E402
from app.parser import ChannelParser                             # noqa: E402
from app.verifier import (CLEARKEY_KEYFORMAT, Verifier, probe,   # noqa: E402
                          probe_m3u8)

# 合成条目（大小写混排的 base64url），不对应任何真实线路或密钥

FMP4 = b'\x00\x00\x00\x20ftypisom' + b'\x00' * 8 + b'hvcC\x01\x02\x03'

MPD_DYN = (
    '<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" '
    'xmlns:cenc="urn:mpeg:cenc:2013" type="dynamic"><Period>'
    '<AdaptationSet contentType="video" mimeType="video/mp4">'
    '<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" '
    'cenc:default_KID="%s"/>'
    '<ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>'
    '<Representation id="video=5000000">'
    '<SegmentTemplate initialization="$RepresentationID$/init.cmfv"/>'
    '</Representation></AdaptationSet></Period></MPD>' % KID_UUID)


def _copy_config(root):
    write_test_config(root)


class RecordingNet(object):
    """记录请求 URL 与参数的假网络。"""

    ua = 'qa'

    def __init__(self, responses=None, final_map=None, fail=None):
        self.responses = responses or {}
        self.final_map = final_map or {}
        self.fail = fail or set()
        self.requested = []

    @staticmethod
    def enc(u):
        return u

    def fetch_ex(self, url, ua=None, limit=None, timeout=None):
        self.requested.append(url)
        if url in self.fail:
            raise OSError('simulated transient failure')
        if url not in self.responses:
            raise KeyError(url)
        st, body, ct = self.responses[url]
        lim = 393216 if limit is None else limit
        return st, body[:lim], ct, self.final_map.get(url, url)

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        st, body, ct, _ = self.fetch_ex(url, ua, limit, timeout)
        return st, body, ct

    def fetch_retry(self, url, ua=None, limit=393216, timeout=None, retries=None, backoff=None):
        return self.fetch(url, ua, limit, timeout)[1]


# =====================================================================
# A. 302 base 修复
# =====================================================================
M3U_MEDIA = b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n'


class TestRedirectBaseEquivalence(unittest.TestCase):
    def test_non_redirect_is_byte_identical_to_old_logic(self):
        """不重定向：新逻辑 base=final_url 与旧逻辑 base=请求URL 产生的分片 URL 相同。"""
        req = 'http://h/dir/index.m3u8'
        net = RecordingNet({req: (200, M3U_MEDIA, 'application/vnd.apple.mpegurl'),
                            'http://h/dir/seg1.ts': (200, FMP4 * 200, 'video/mp2t')})
        # final_map 为空 -> fetch_ex 返回 final==req（等价于"无重定向"）
        r = probe_m3u8(net, req, None)
        self.assertTrue(r['alive'])
        old_logic = urljoin(req, 'seg1.ts')          # 改动前：以请求 URL 为 base
        new_logic = urljoin(req, 'seg1.ts')
        self.assertEqual(old_logic, new_logic)
        self.assertIn(old_logic, net.requested)
        self.assertNotIn('http://h/seg1.ts', net.requested)

    def test_redirect_uses_final_url(self):
        req = 'http://h/gw.m3u8'
        final = 'http://h/sess/xyz/index.m3u8'
        net = RecordingNet({req: (200, M3U_MEDIA, 'application/vnd.apple.mpegurl'),
                            'http://h/sess/xyz/seg1.ts': (200, FMP4 * 200, 'video/mp2t')},
                           final_map={req: final})
        r = probe_m3u8(net, req, None)
        self.assertTrue(r['alive'])
        self.assertIn('http://h/sess/xyz/seg1.ts', net.requested)
        self.assertNotIn('http://h/seg1.ts', net.requested)


class TestFetchExFetchParity(unittest.TestCase):
    def setUp(self):
        self.cfg = make_config()

    def test_fetch_is_thin_wrapper_arg_passthrough(self):
        net = NetClient(self.cfg)
        seen = []

        def fake_fx(url, ua=None, limit=None, timeout=None):
            seen.append((url, ua, limit, timeout))
            return 201, b'abc', 'text/plain', 'http://final'

        net.fetch_ex = fake_fx
        out = net.fetch('http://x', ua='UA', limit=99, timeout=7)
        self.assertEqual(out, (201, b'abc', 'text/plain'))   # 丢弃 final_url
        self.assertEqual(seen, [('http://x', 'UA', 99, 7)])  # 参数逐一同源

    def test_fetch_propagates_fetch_ex_exception_identically(self):
        net = NetClient(self.cfg)

        def boom(url, ua=None, limit=None, timeout=None):
            raise ValueError('boom')

        net.fetch_ex = boom
        with self.assertRaises(ValueError):
            net.fetch('http://x')
        with self.assertRaises(ValueError):
            net.fetch_ex('http://x')

    def test_real_client_parity_and_final_url(self):
        """真实 NetClient：fetch 与 fetch_ex 对同一 URL 返回一致，且 fetch_ex 暴露 final_url。"""
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        body = b'#EXTM3U\n#EXTINF:6,\nseg.ts\n'

        class H(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == '/r':
                    self.send_response(302)
                    self.send_header('Location', '/final.m3u8')
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header('Content-Type', 'application/vnd.apple.mpegurl')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass

        srv = ThreadingHTTPServer(('127.0.0.1', 0), H)
        port = srv.server_address[1]
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            net = NetClient(self.cfg)
            # 仅为本机 fixture 注入已知回环地址；生产 NetClient 默认仍拒绝私网。
            def fixture_resolver(host, request_port):
                self.assertEqual(host, '127.0.0.1')
                return socket.getaddrinfo(
                    host, request_port, 0, socket.SOCK_STREAM)

            net._resolver = fixture_resolver
            base = 'http://127.0.0.1:%d' % port
            st1, b1, ct1 = net.fetch(base + '/a.m3u8')
            st2, b2, ct2, fin = net.fetch_ex(base + '/a.m3u8')
            self.assertEqual((st1, b1, ct1), (st2, b2, ct2))      # 语义一致
            self.assertEqual(fin, base + '/a.m3u8')               # 无重定向 -> final==请求
            self.assertEqual(b1, body)
            # 302：fetch 得最终正文；fetch_ex 暴露最终 URL
            _st, b3, _ct = net.fetch(base + '/r')
            _s2, b4, _c2, fin2 = net.fetch_ex(base + '/r')
            self.assertEqual(b3, body)
            self.assertEqual(fin2, base + '/final.m3u8')
        finally:
            srv.shutdown(); srv.server_close()

    def test_concurrency_gate_blocks_both(self):
        """并发闸门：闸满时 fetch_ex 与 fetch 都必须阻塞（同一 self.gate）。"""
        cfg = make_config()
        cfg.settings['limits']['net_concurrency'] = 1
        net = NetClient(cfg)
        net.gate.acquire()                        # 占满
        done = []

        def worker(fn):
            try:
                fn('http://127.0.0.1:1/never', timeout=0.2)
            except Exception:
                pass
            done.append(1)

        t1 = threading.Thread(target=worker, args=(net.fetch_ex,), daemon=True)
        t2 = threading.Thread(target=worker, args=(net.fetch,), daemon=True)
        t1.start(); t2.start()
        time.sleep(0.3)
        self.assertEqual(done, [])                # 均被闸门拦住
        net.gate.release()
        t1.join(3); t2.join(3)
        self.assertEqual(len(done), 2)            # 释放后都放行
        self.assertEqual(int(cfg.settings['limits']['net_concurrency']), 1)


# =====================================================================
# C. enabled=false 闸门
# =====================================================================
class TestEnabledGate(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='qa2-gate-')
        _copy_config(self.tmp)
        self.cfg = Config(self.tmp)
        self.cfg.url_lists = {'lists': [
            {'id': 'disabled', 'url': 'https://disabled.example.invalid/list.m3u',
             'enabled': False},
            {'id': 'enabled', 'url': 'https://enabled.example.invalid/list.m3u',
             'enabled': True},
        ]}

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_disabled_lists_zero_network(self):
        lists = self.cfg.url_lists['lists']
        disabled = {it['url'] for it in lists if not it.get('enabled')}
        enabled = {it['url'] for it in lists if it.get('enabled')}
        # 同时包含禁用和启用条目，独立于部署清单的内容与顺序。
        self.assertTrue(disabled, '前置：合成配置包含禁用清单')
        self.assertTrue(enabled, '前置：合成配置包含启用清单')
        net = RecordingNet()
        UrlListProvider(self.cfg, net).collect()
        self.assertFalse(disabled & set(net.requested),
                         'enabled=false 的清单不得产生任何网络请求: %s' % net.requested)
        self.assertEqual(enabled & set(net.requested), enabled,
                         'enabled=true 的清单应全部进入拉取流程（桩内无响应体不影响判定）')

    def test_run_level_urllist_channel_makes_no_request(self):
        """run() 级：即便仓库/挖矿通道被桩掉，禁用清单 URL 也不得被请求。"""
        net = RecordingNet()
        disc = SourceDiscovery(self.cfg, Database(self.cfg.db_path), net)
        disc.db.migrate(self.cfg.settings)
        urls = {it['url'] for it in self.cfg.url_lists['lists']
                if not it.get('enabled')}

        orig_repo = __import__('app.discovery', fromlist=['RepoProvider']).RepoProvider
        orig_miner = RepoMinerProvider

        class _NoRepo(object):
            def __init__(self, *a, **k):
                pass

            def collect(self):
                return []

        class _NoMiner(object):
            def __init__(self, *a, **k):
                pass

            def collect(self):
                return 0

        import app.discovery as D
        D.RepoProvider = _NoRepo
        D.RepoMinerProvider = _NoMiner
        try:
            disc.run()
        finally:
            D.RepoProvider = orig_repo
            D.RepoMinerProvider = orig_miner
            disc.db.close()
        self.assertFalse(urls & set(net.requested),
                         '禁用的清单 URL 不得出现在任何请求中: %s' % net.requested)


# =====================================================================
# D. 挖矿红线
# =====================================================================
class TestMinerRedline(unittest.TestCase):
    SRC = open(os.path.join(ROOT, 'app', 'discovery.py'), encoding='utf-8').read()

    def _slice(self, start, end):
        i = self.SRC.index(start)
        j = self.SRC.index(end, i)
        return self.SRC[i:j]

    def test_patterns_block_has_no_mpd_xml_json(self):
        block = self._slice('_MINER_PATTERNS = (', 'def parse_playlist')
        for bad in ('.mpd', '.xml', '.json', 'mpd', 'xml', 'json'):
            self.assertNotIn(bad, block, '红线块不得出现 %s' % bad)
        # 3 条正则的扩展名 alternation 必须恰为 m3u|m3u8|txt
        self.assertEqual(block.count('m3u|m3u8|txt'), 3)
        self.assertIn(r'\.(?:m3u|m3u8|txt)', block)

    def test_expand_home_only_playlist_exts(self):
        block = self._slice('def _expand_home(self, repo):', '# ---------- 校验')
        # 候选过滤只认这三个扩展名（json 一词可出现在 json.loads 解析 API 响应处，故只查字面扩展名）
        self.assertIn("n.lower().endswith(('.m3u', '.m3u8', '.txt'))", block)
        for bad in ('.mpd', '.xml', '.json'):
            self.assertNotIn(bad, block, '_expand_home 扩展名过滤不得含 %s' % bad)

    def test_parse_playlist_only_m3u(self):
        block = self._slice('def parse_playlist(parser, text, path):', 'class SeedProvider')
        for bad in ('.mpd', '.xml', '.json'):
            self.assertNotIn(bad, block, 'parse_playlist 不得出现 %s' % bad)
        self.assertIn("('.m3u', '.m3u8')", block)

    def test_behavior_mpd_xml_json_not_mined(self):
        """行为级：README 里的 .mpd/.xml/.json 直链不得被提取为候选。"""
        tmp = tempfile.mkdtemp(prefix='qa2-miner-')
        _copy_config(tmp)
        cfg = Config(tmp)
        db = Database(os.path.join(tmp, 'data', 't.db'))
        db.migrate(cfg.settings)
        miner = RepoMinerProvider(cfg, db, RecordingNet())
        text = (
            'a https://raw.githubusercontent.com/u/r/main/a.m3u\n'
            'b https://raw.githubusercontent.com/u/r/main/b.mpd\n'
            'c https://raw.githubusercontent.com/u/r/main/c.json\n'
            'd https://raw.githubusercontent.com/u/r/main/d.xml\n'
            'e https://raw.githubusercontent.com/u/r/main/e.txt\n')
        cands = miner.extract_candidates(text, 'self/repo', 'main', set())
        paths = [p for _r, _b, p in cands]
        self.assertTrue(any(p.endswith('.m3u') for p in paths))
        self.assertTrue(any(p.endswith('.txt') for p in paths))
        for p in paths:
            self.assertFalse(p.endswith(('.mpd', '.json', '.xml')),
                             '不得提取非白名单扩展名: %s' % p)
        db.close()
        shutil.rmtree(tmp, ignore_errors=True)


# =====================================================================
# E. 密钥保真（另一条合成条目 + 真实 ingest 路径）
# =====================================================================
class TestKeyBytesFidelitySyntheticEntry(unittest.TestCase):
    """翡翠台(tvb-id=J)：parse_kodiprop -> _entries -> ingest -> builder 渲染，逐字节。"""

    def setUp(self):
        self.text = DRM_PLAYLIST
        self.tmp = tempfile.mkdtemp(prefix='qa2-key-')
        _copy_config(self.tmp)
        self.cfg = Config(self.tmp)
        self.cfg.settings['paths']['output_dir'] = os.path.join(self.tmp, 'output')

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _raw_entry(self):
        """从清单原文取 翡翠台 的 (url, raw_k, raw_kid, raw_license_key)。"""
        lines = [l.rstrip('\r') for l in self.text.splitlines()]
        for i, ln in enumerate(lines):
            if ln.startswith('#EXTINF') and ln.split(',')[-1].strip() == '翡翠台':
                lk = url = None
                for j in range(i + 1, min(i + 8, len(lines))):
                    if lines[j].startswith('#KODIPROP:') and 'license_key=' in lines[j]:
                        lk = lines[j].split('license_key=', 1)[1]
                    elif not lines[j].startswith('#') and lines[j].startswith('http'):
                        url = lines[j]
                        break
                return url, lk
        return None, None

    def test_four_stage_byte_fidelity(self):
        url, raw_lk = self._raw_entry()
        self.assertIsNotNone(url)
        self.assertIsNotNone(raw_lk)
        self.assertNotEqual(K_B64, K_B64.lower(), '前置：该合成密钥必须含大写，能检出大小写折叠')
        # 从**原文**抠 k/kid（不经任何解析）
        raw_k = re.search(r'"k"\s*:\s*"([^"]*)"', raw_lk).group(1)
        raw_kid = re.search(r'"kid"\s*:\s*"([^"]*)"', raw_lk).group(1)
        self.assertEqual(raw_k, K_B64)
        self.assertEqual(raw_kid, KID_B64)

        # 环节1：parse_kodiprop
        props = ChannelParser.parse_kodiprop(self.text)
        self.assertIn(url, props)
        self.assertEqual(props[url]['license_key'], raw_lk)              # 原始串原样
        obj = props[url]['license_key_obj']
        self.assertEqual(obj['keys'][0]['k'], raw_k)
        self.assertEqual(obj['keys'][0]['kid'], raw_kid)

        # 环节2：UrlListProvider._entries（真实映射）+ SourceDiscovery.ingest（真实落库）
        list_item = {'id': 'tvb-xiaotan', 'url': 'x', 'enabled': True}
        entries = UrlListProvider(self.cfg, RecordingNet())._entries(list_item, self.text)
        rec = next(e for e in entries if e['url'] == url)
        self.assertEqual(rec['drm'], 'clearkey')
        self.assertEqual(rec['drm_key'], raw_lk)                          # 未改写
        self.assertEqual(rec['kid'], ChannelParser.kid_hex(raw_kid))
        db = Database(self.cfg.db_path)
        db.migrate(self.cfg.settings)
        disc = SourceDiscovery(self.cfg, db, RecordingNet())
        disc.ingest(entries, 'urllist:tvb-xiaotan')
        row = db.query_one('SELECT * FROM sources WHERE url=?', (url,))
        self.assertIsNotNone(row)
        self.assertEqual(row['drm_key'], raw_lk)                         # 落库逐字节
        self.assertEqual(row['kid'], ChannelParser.kid_hex(raw_kid))
        self.assertEqual(row['drm'], 'clearkey')

        # 环节3：builder 渲染 live.media3.json
        now = utcnow()
        db.execute("UPDATE sources SET status='active', av='video', profile='media3-json' "
                   'WHERE id=?', (row['id'],))
        db.execute('INSERT INTO lineups(channel_key, source_id, rank, updated_at) '
                   'VALUES (?,?,?,?)', (row['channel_key'], row['id'], 0, now))
        out = OutputBuilder(self.cfg, db).render_media3_json(
            OutputBuilder(self.cfg, db)._collect_media3_items())
        doc = json.loads(out)
        ch = next((c for c in doc['channels'] if c['key'] == row['channel_key']), None)
        self.assertIsNotNone(ch, 'channel_key=%r 未出现在 media3' % row['channel_key'])
        line = next(ln for ln in ch['lines'] if ln['url'] == url)
        self.assertEqual(line['clearkey']['keys'][0]['k'], raw_k)
        self.assertEqual(line['clearkey']['keys'][0]['kid'], raw_kid)
        self.assertEqual(line['clearkey']['type'], 'temporary')
        self.assertEqual(line['kid'], ChannelParser.kid_hex(raw_kid))
        self.assertTrue(line['key_present'])
        db.close()


# =====================================================================
# F. parse_kodiprop 返回语义
# =====================================================================
class TestParseKodipropReturnType(unittest.TestCase):
    TEXT = ('#EXTM3U\n#EXTINF:-1,x\n'
            '#KODIPROP:inputstream.adaptive.manifest_type=mpd\n'
            '#KODIPROP:inputstream.adaptive.license_type=clearkey\n'
            '#KODIPROP:inputstream.adaptive.license_key={"keys": [{"kty": "oct", '
            '"k": "_-7dzLuqmYh3ZlVEMyIRAA", "kid": "ABEiM0RVZneImaq7zN3u_w"}], '
            '"type": "temporary"}\nhttp://h/x.mpd\n')

    def test_license_key_is_raw_string_not_object(self):
        """实测：license_key 返回**原始字符串**（设计 §3.4 措辞为"解析为对象"，实现用
        并列的 license_key_obj 承载对象）。二者并存；消费方用 obj 解析、用 raw 落 drm_key。"""
        p = ChannelParser.parse_kodiprop(self.TEXT)
        e = p['http://h/x.mpd']
        self.assertIsInstance(e['license_key'], str)
        self.assertTrue(e['license_key'].startswith('{"keys"'))
        self.assertIsInstance(e['license_key_obj'], dict)
        self.assertEqual(e['license_key_obj']['keys'][0]['k'], K_B64)
        # 两者一致性：obj 由 raw 解析而来
        self.assertEqual(json.loads(e['license_key']), e['license_key_obj'])

    def test_bad_json_obj_none_raw_kept(self):
        p = ChannelParser.parse_kodiprop(
            '#KODIPROP:inputstream.adaptive.license_key={oops\nhttp://h/y.mpd\n')
        e = p['http://h/y.mpd']
        self.assertEqual(e['license_key'], '{oops')
        self.assertIsNone(e['license_key_obj'])


# =====================================================================
# 迁移：v5 -> v6（合成 v5 库，自包含；真实 NAS v5 快照另由 tools/_qa2_v5_migrate.py 取证）
# =====================================================================
class TestV5ToV6MigrationSynthetic(unittest.TestCase):
    def test_v5_to_v6_preserves_rows_indexes_drm_key_null(self):
        from app.db import (DDL_V1, DDL_V2, DDL_V3, DDL_V4, _apply_ddl_v5,
                            _post_migrate_v5)
        tmp = tempfile.mkdtemp(prefix='qa2-v56-')
        _copy_config(tmp)
        cfg = Config(tmp)
        db = Database(os.path.join(tmp, 'data', 't.db'))
        for stmt in DDL_V1 + DDL_V2 + DDL_V3 + DDL_V4:
            db.conn.execute(stmt)
        _apply_ddl_v5(db.conn)
        _post_migrate_v5(db.conn, cfg.settings)
        db.conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version','5')")
        db.conn.commit()
        now = utcnow()
        for i in range(5):
            db.execute('INSERT INTO sources(url,url_hash,url_key,channel_name,channel_key,'
                       'origin,status,score,first_seen,last_seen) VALUES (?,?,?,?,?,?,?,?,?,?)',
                       ('http://h/%d.m3u8' % i, 'h%d' % i, 'k%d' % i, 'n', 'CCTV1',
                        't', 'active', 10, now, now))
        pre_rows = db.query_one('SELECT COUNT(*) c FROM sources')['c']
        pre_cols = [r['name'] for r in db.query('PRAGMA table_info(sources)')]
        db.migrate(cfg.settings)                       # v5 -> v6
        cols = [r['name'] for r in db.query('PRAGMA table_info(sources)')]
        self.assertEqual(len(pre_cols), 26)
        self.assertEqual(len(cols), 28)
        self.assertEqual([c for c in cols if c not in pre_cols], ['drm_key', 'base_score'])
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))  # v9（含 v7 playback_reports）
        self.assertEqual(db.query_one('SELECT COUNT(*) c FROM sources')['c'], pre_rows)
        self.assertEqual(db.query_one(
            'SELECT COUNT(*) c FROM sources WHERE drm_key IS NULL')['c'], pre_rows)
        idx = {r['name'] for r in db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='sources'")}
        self.assertIn('uq_sources_url_key', idx)
        self.assertIn('uq_sources_url', idx)
        db.migrate(cfg.settings)                       # 幂等
        self.assertEqual(db.get_meta('schema_version'), str(SCHEMA_VERSION))
        self.assertEqual(len(db.query('PRAGMA table_info(sources)')), 28)
        db.close()
        shutil.rmtree(tmp, ignore_errors=True)


# =====================================================================
# B. probe_dash -> record() 瞬时失败（缺陷红灯）
# =====================================================================
class TestRecordTransientFailureKeepsManifestDrm(unittest.TestCase):
    """B：单次瞬时网络失败后，record() 不得清空清单 drm/kid。

    失败即缺陷：probe_dash 失败结果不带 drm/kid，record() 的 fail/abnormal 分支
    仍无条件 UPDATE drm/kid 列为 ''，于是清单 provenance 丢失；下一轮
    _select_batch 用 expect_drm='' 重探 -> MPD 的 Widevine 节点使分类漂移为
    widevine/need-key，源被永久踢出 media3-json 投递。
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='qa2-rec-')
        _copy_config(self.tmp)
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate(self.cfg.settings)
        now = utcnow()
        self.db.execute(
            'INSERT INTO sources(url, url_hash, url_key, channel_name, channel_key, origin,'
            ' status, av, score, first_seen, last_seen, container, encryption, drm, keyformat,'
            ' kid, profile, drm_key) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
            ('http://x/transient.mpd', 'h1', 'k1', '翡翠台', 'TVB翡翠台', 'urllist:tvb',
             'candidate', None, 10, now, now, 'dash', 'cenc', 'clearkey',
             CLEARKEY_KEYFORMAT, KID_HEX, 'direct', LICENSE))
        self.sid = self.db.query_one('SELECT id FROM sources')['id']

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_transient_failure_preserves_drm_and_kid(self):
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (self.sid,))
        net = RecordingNet(responses={}, fail={'http://x/transient.mpd'})
        res = probe(net, row['url'], 'qa', expect_drm=row['drm'], expect_kid=row['kid'])
        self.assertFalse(res['alive'])
        Verifier(self.cfg, self.db, net).record(row, res, 1)
        after = self.db.query_one('SELECT drm, kid FROM sources WHERE id=?', (self.sid,))
        self.assertEqual(after['drm'], 'clearkey',
                         '瞬时失败后 drm 被清空 -> 下一轮 expect_drm 丢失，分类漂移')
        self.assertEqual(after['kid'], KID_HEX,
                         '瞬时失败后 kid 被清空')


if __name__ == '__main__':
    unittest.main(verbosity=2)
