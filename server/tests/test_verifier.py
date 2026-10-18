# -*- coding: utf-8 -*-
"""verifier 单测：媒体级判定（本地构造十六进制/字节样本 + FakeNet，全部离线）。

P0 收口新增：
- AES-128 核心的外部已知答案测试（NIST FIPS-197 C.1 / SP 800-38A CBC）——独立于
  fixture 自造样本，避免"加密解密同一实现、AES 全错也恒过"的陷阱；
- parse_ext_x_key 表驱动（逐分片对齐）+ classify_encryption；
- AES-128 加密 HLS fixture（AC-1.1）+ 末分片失败回退首分片必须改用 keys[0]（§8-⑧）；
- R2 scheme 前置拦截（AC-2.1/2.5）；R4 能力门禁 + 只读重判（AC-4.x）；R3 签名 403（AC-3.6）。
"""
import os
import shutil
import ssl
import sys
import tempfile
import threading
import unittest
from unittest import mock
from urllib.error import HTTPError, URLError

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

import app.verifier as verifier_module                       # noqa: E402
from offline_fixtures import write_test_config  # noqa: E402
from app.config import Config                                  # noqa: E402
from app.db import Database, utcnow                            # noqa: E402
from app.net import NetResolveError                            # noqa: E402
from app.verifier import (_AES_SBOX, _aes_cbc_decrypt,         # noqa: E402
                          _aes_decrypt_block, _expand_key, _gf_mul,
                          build_caps_required, check_caps, classify_encryption,
                          mp4_judge, parse_ext_x_key, probe, probe_m3u8,
                          sniff_segment, ts_judge, Verifier)


class FakeNet(object):
    """离线假网络：url -> (status, body, content_type)；未登记的 url 抛 KeyError。"""

    def __init__(self, responses):
        self.responses = responses

    @staticmethod
    def enc(url):
        return url

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        if url not in self.responses:
            raise KeyError(url)
        st, body, ct = self.responses[url]
        return st, body[:limit], ct


class RecordingFakeNet(FakeNet):
    """可记录全部请求 + 可模拟 302 final_url 的离线假网络（多级 master 测试用）。"""

    def __init__(self, responses, final_map=None):
        super(RecordingFakeNet, self).__init__(responses)
        self.final_map = final_map or {}
        self.requested = []

    def fetch_ex(self, url, ua=None, limit=393216, timeout=None):
        self.requested.append(url)
        if url not in self.responses:
            raise KeyError(url)
        st, body, ct = self.responses[url]
        return st, body[:limit], ct, self.final_map.get(url, url)

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        st, body, ct, _ = self.fetch_ex(url, ua, limit, timeout)
        return st, body, ct


# ---------- 样本构造 ----------
def make_ts_video():
    """PAT + PMT(H.264 流类型) + PES 视频包的 TS 样本（对齐 188 字节包）。"""
    pat = bytearray(b'\xff' * 188)
    pat[0:4] = b'\x47\x00\x00\x50'      # sync + pid 0 + PUSI/payload
    pat[4] = 0x00                        # pointer_field
    pat[5] = 0x00                        # (prog 高字节与 table_id 位)
    pat[6] = 0x01                        # prog=1 非零
    pat[7] = 0xE0                        # pmt pid 高字节
    pat[8] = 0x50                        # pmt pid = 0x50
    pmt = bytearray(b'\xff' * 188)
    pmt[0:4] = b'\x47\x00\x50\x50'      # sync + pid 0x50 + PUSI/payload
    pmt[4] = 0x00                        # pointer_field
    pmt[5] = 0x02                        # table_id = PMT
    pmt[14] = 0x00                       # program_info_length 高
    pmt[15] = 0x00                       # program_info_length 低
    pmt[17] = 0x1B                       # stream_type = H.264
    pmt[18] = 0xE1                       # elementary pid 高
    pmt[19] = 0x00                       # elementary pid 低
    pmt[20] = 0x00                       # es_info_length 高
    pmt[21] = 0x00                       # es_info_length 低
    pes = (b'\x00\x00\x01\xe0' + b'\xaa' * 20) * 45   # >=3 个视频 PES 起始码
    return bytes(pat) + bytes(pmt) + pes


def make_ts_pes_only():
    """无可解析 PAT/PMT、仅靠 PES 计数判定的 TS 样本。"""
    pat = bytearray(b'\xff' * 188)
    pat[0:4] = b'\x47\x00\x00\x50'
    pat[4] = 0x00
    # prog 恒为 0 -> pmt_pid 保持 None
    pes = (b'\x00\x00\x01\xe0' + b'\xbb' * 20) * 45
    return bytes(pat) + pes


def make_fmp4():
    """ftyp + moof + avcC 的 fMP4 分片样本。"""
    return (b'\x00\x00\x00\x20ftypisom' + b'\x00' * 8 +
            b'\x00\x00\x00\x10moof' + b'\x00' * 12 + b'avcC\x01\x42\x00\x1e')


def make_flv_video():
    """含视频 tag(9) 的 FLV 样本。"""
    return (b'FLV\x01\x05\x00\x00\x00\x09' +          # header(9) + prevtagsize(4)
            b'\x00\x00\x00\x00' +
            b'\x09\x00\x00\x04' + b'\x00' * 8 +        # tag9 header(11)
            b'\x01\x02\x03\x04' +                      # payload
            b'\x00\x00\x00\x00')                       # prev tag size


M3U_MEDIA = (b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n'
             b'#EXTINF:6,\nseg0.ts\n')


class TestTsJudge(unittest.TestCase):
    def test_pmt_video(self):
        self.assertEqual(ts_judge(make_ts_video()), 'video(TS:H.264视频)')

    def test_pes_video(self):
        self.assertIn('video(TS:PES视频', ts_judge(make_ts_pes_only()))

    def test_too_small(self):
        self.assertEqual(ts_judge(b'\x47' * 100), '分片过小(100B)')


class TestMp4Judge(unittest.TestCase):
    def test_ftyp_avcC(self):
        j = mp4_judge(make_fmp4())
        self.assertTrue(j.startswith('video(fMP4(ftyp):H.264)'))

    def test_audio_only(self):
        data = b'\x00\x00\x00\x20ftypisom' + b'\x00' * 8 + b'mp4a\x40\x02'
        self.assertEqual(mp4_judge(data), 'audio-only(fMP4(ftyp):AAC)')

    def test_unknown(self):
        data = b'\x00\x00\x00\x20ftypisom' + b'\x00' * 32
        self.assertIn('未知编码', mp4_judge(data))


class TestSniffSegment(unittest.TestCase):
    def test_ts(self):
        j = sniff_segment(FakeNet({'http://x/seg.ts': (200, make_ts_video(), 'video/mp2t')}),
                          'http://x/seg.ts', None)
        self.assertEqual(j, 'video(TS:H.264视频)')

    def test_adts_audio(self):
        j = sniff_segment(FakeNet({'http://x/a.ts': (200, b'\xff\xf1' + b'\x00' * 1200, '')}),
                          'http://x/a.ts', None)
        self.assertEqual(j, 'audio-only(ADTS/MP3)')

    def test_html(self):
        j = sniff_segment(FakeNet({'http://x/s': (200, b'<!DOCTYPE html>' + b'x' * 500, 'text/html')}),
                          'http://x/s', None)
        self.assertEqual(j, 'html页')


class TestProbeM3u8(unittest.TestCase):
    def test_endlist_rejected(self):
        """ENDLIST = 点播，拒绝（直播源验收硬规则）。"""
        pl = b'#EXTM3U\n#EXT-X-ENDLIST\nseg.ts\n'
        r = probe_m3u8(FakeNet({'http://x/v.m3u8': (200, pl, '')}), 'http://x/v.m3u8', None)
        self.assertFalse(r['alive'])
        self.assertEqual(r['why'], '点播(ENDLIST)')

    def test_media_video(self):
        net = FakeNet({'http://x/live.m3u8': (200, M3U_MEDIA, ''),
                       'http://x/seg.ts': (200, make_ts_video(), 'video/mp2t')})
        r = probe_m3u8(net, 'http://x/live.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertTrue(r['av'].startswith('video'))

    def test_master_follow_and_codecs(self):
        master = (b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="avc1.4d401f,mp4a.40.2"\n'
                  b'media.m3u8\n')
        net = FakeNet({'http://x/master.m3u8': (200, master, ''),
                       'http://x/media.m3u8': (200, M3U_MEDIA, ''),
                       'http://x/seg.ts': (200, make_ts_video(), 'video/mp2t')})
        r = probe_m3u8(net, 'http://x/master.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertEqual(r['av'], 'video(CODECS)')

    def test_not_m3u8(self):
        r = probe_m3u8(FakeNet({'http://x/a': (200, b'<html>404</html>', 'text/html')}),
                       'http://x/a', None)
        self.assertFalse(r['alive'])
        self.assertEqual(r['why'], '非m3u8')

    def test_last_seg_retry(self):
        """末分片失败换第一个分片重试。"""
        net = FakeNet({'http://x/live.m3u8': (200, M3U_MEDIA, ''),
                       'http://x/seg.ts': (200, b'x' * 10, ''),          # 末分片过小
                       'http://x/seg0.ts': (200, make_ts_video(), '')})  # 首分片可用
        r = probe_m3u8(net, 'http://x/live.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertTrue(r['av'].startswith('video'))


class TestProbeM3u8NestedMaster(unittest.TestCase):
    """多级 master 修复：每层独立选变体、CODECS 不串层、循环/深度 fail-closed。

    根因：旧实现 variants 在循环外创建且恒取 variants[0]，第二层起重复抓第一层，
    把嵌套 master 的 URI 当分片，绕过嵌套 ENDLIST / AES-128 识别。
    """

    def test_master_master_media_each_level_once(self):
        top = (b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="avc1.4d401f"\n'
               b'mid.m3u8\n')
        mid = (b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="avc1.4d401f"\n'
               b'sub/media.m3u8\n')
        media = b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n'
        net = RecordingFakeNet({'http://x/top.m3u8': (200, top, ''),
                                'http://x/mid.m3u8': (200, mid, ''),
                                'http://x/sub/media.m3u8': (200, media, ''),
                                'http://x/sub/seg.ts': (200, make_ts_video(), 'video/mp2t')})
        r = probe_m3u8(net, 'http://x/top.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertTrue(r['av'].startswith('video'))
        # 每级 master 只请求一次，且最终嗅探真实分片
        self.assertEqual(net.requested.count('http://x/top.m3u8'), 1)
        self.assertEqual(net.requested.count('http://x/mid.m3u8'), 1)
        self.assertEqual(net.requested.count('http://x/sub/media.m3u8'), 1)
        self.assertIn('http://x/sub/seg.ts', net.requested)
        self.assertEqual(r['codecs'], ['avc1.4d401f', 'avc1.4d401f'])

    def test_nested_endlist_rejected_not_treated_as_segment(self):
        top = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmid.m3u8\n'
        mid = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nsub/media.m3u8\n'
        media = b'#EXTM3U\n#EXT-X-ENDLIST\n#EXTINF:6,\nseg.ts\n'
        net = RecordingFakeNet({'http://x/top.m3u8': (200, top, ''),
                                'http://x/mid.m3u8': (200, mid, ''),
                                'http://x/sub/media.m3u8': (200, media, ''),
                                'http://x/sub/seg.ts': (200, make_ts_video(), 'video/mp2t')})
        r = probe_m3u8(net, 'http://x/top.m3u8', None)
        self.assertFalse(r['alive'])
        self.assertEqual(r['why'], '点播(ENDLIST)')
        # 嵌套 media 清单被当作下一层正文抓取，而非把其 URI 当分片
        self.assertIn('http://x/sub/media.m3u8', net.requested)
        self.assertNotIn('http://x/sub/seg.ts', net.requested)

    def test_nested_aes128_key_fetched_and_decrypted(self):
        top = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmid.m3u8\n'
        mid = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nenc.m3u8\n'
        media = (b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n'
                 b'#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXTINF:6,\nseg.ts\n')
        ct = _cbc_encrypt(make_ts_video(), KEY16, bytes(16))   # media_seq 0 + idx 0 -> IV=0
        net = RecordingFakeNet({'http://x/top.m3u8': (200, top, ''),
                                'http://x/mid.m3u8': (200, mid, ''),
                                'http://x/enc.m3u8': (200, media, ''),
                                'http://x/key.bin': (200, KEY16, ''),
                                'http://x/seg.ts': (200, ct, 'video/mp2t')})
        r = probe_m3u8(net, 'http://x/top.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertTrue(r['av'].startswith('video'))
        self.assertEqual(r['encryption'], 'aes128')
        self.assertTrue(r['key_present'])
        self.assertIn('http://x/key.bin', net.requested)

    def test_sibling_codecs_does_not_pollute_selected_branch(self):
        """首选分支 CODECS=mp4a；同层兄弟 CODECS=avc1 不得混入，av 应为 audio-only。"""
        master = (b'#EXTM3U\n'
                  b'#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS="mp4a.40.2"\nfirst.m3u8\n'
                  b'#EXT-X-STREAM-INF:BANDWIDTH=2,CODECS="avc1.4d401f"\nsecond.m3u8\n')
        media = b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n'
        net = RecordingFakeNet({'http://x/master.m3u8': (200, master, ''),
                                'http://x/first.m3u8': (200, media, ''),
                                'http://x/seg.ts': (200, b'\xff\xf1' + b'\x00' * 1200, '')})
        r = probe_m3u8(net, 'http://x/master.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertEqual(r['codecs'], ['mp4a.40.2'])
        self.assertEqual(r['av'], 'audio-only(CODECS)')
        self.assertNotIn('http://x/second.m3u8', net.requested)

    def test_self_reference_bounded(self):
        top = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\ntop.m3u8\n'
        net = RecordingFakeNet({'http://x/top.m3u8': (200, top, '')})
        r = probe_m3u8(net, 'http://x/top.m3u8', None)
        self.assertFalse(r['alive'])
        self.assertEqual(r['why'], 'master循环')
        self.assertEqual(net.requested.count('http://x/top.m3u8'), 1)

    def test_ab_a_cycle_bounded(self):
        a = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nb.m3u8\n'
        b = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n'
        net = RecordingFakeNet({'http://x/a.m3u8': (200, a, ''),
                                'http://x/b.m3u8': (200, b, '')})
        r = probe_m3u8(net, 'http://x/a.m3u8', None)
        self.assertFalse(r['alive'])
        self.assertEqual(r['why'], 'master循环')
        self.assertEqual(net.requested.count('http://x/a.m3u8'), 1)
        self.assertEqual(net.requested.count('http://x/b.m3u8'), 1)

    def test_three_master_descents_then_media_ok(self):
        l0 = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nl1.m3u8\n'
        l1 = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nl2.m3u8\n'
        l2 = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia.m3u8\n'
        media = b'#EXTM3U\n#EXTINF:6,\nseg.ts\n'
        net = RecordingFakeNet({'http://x/l0.m3u8': (200, l0, ''),
                                'http://x/l1.m3u8': (200, l1, ''),
                                'http://x/l2.m3u8': (200, l2, ''),
                                'http://x/media.m3u8': (200, media, ''),
                                'http://x/seg.ts': (200, make_ts_video(), 'video/mp2t')})
        r = probe_m3u8(net, 'http://x/l0.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertTrue(r['av'].startswith('video'))

    def test_over_three_masters_fail_closed(self):
        l0 = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nl1.m3u8\n'
        l1 = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nl2.m3u8\n'
        l2 = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nl3.m3u8\n'
        l3 = b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia.m3u8\n'
        media = b'#EXTM3U\n#EXTINF:6,\nseg.ts\n'
        net = RecordingFakeNet({'http://x/l0.m3u8': (200, l0, ''),
                                'http://x/l1.m3u8': (200, l1, ''),
                                'http://x/l2.m3u8': (200, l2, ''),
                                'http://x/l3.m3u8': (200, l3, ''),
                                'http://x/media.m3u8': (200, media, ''),
                                'http://x/seg.ts': (200, make_ts_video(), 'video/mp2t')})
        r = probe_m3u8(net, 'http://x/l0.m3u8', None)
        self.assertFalse(r['alive'])
        self.assertEqual(r['why'], 'master嵌套过深')
        # 嵌套 master 不得当媒体列表：第 4 层 child 与分片都不应被嗅探
        self.assertNotIn('http://x/media.m3u8', net.requested)
        self.assertNotIn('http://x/seg.ts', net.requested)


class TestProbeFlv(unittest.TestCase):
    def test_flv_video(self):
        r = probe(FakeNet({'http://x/live.flv': (200, make_flv_video(), 'video/x-flv')}),
                  'http://x/live.flv', None)
        self.assertEqual(r['type'], 'FLV')
        self.assertTrue(r['alive'])
        self.assertEqual(r['av'], 'video')

    def test_flv_not_flv(self):
        r = probe(FakeNet({'http://x/bad.flv': (200, b'NOTFLV' + b'x' * 200, '')}),
                  'http://x/bad.flv', None)
        self.assertFalse(r['alive'])
        self.assertIn('非FLV', r['why'])


# =====================================================================================
# P0 收口：AES-128 / 加密解析 / 门禁 / 6 分类
# =====================================================================================

# ---------- 本地 AES-128 加密（fixture 构造用；只用 verifier 的 S-box/密钥扩展，
#            正确性由下方 NIST KAT 独立保证）----------
def _ark(s, rk):
    for i in range(16):
        s[i] ^= rk[i]


def _shift_rows(s):
    out = list(s)
    for r in range(4):
        for c in range(4):
            out[r + 4 * c] = s[r + 4 * ((c + r) % 4)]
    return out


def _mix_columns(s):
    for c in range(4):
        a0, a1, a2, a3 = s[4 * c], s[4 * c + 1], s[4 * c + 2], s[4 * c + 3]
        s[4 * c] = _gf_mul(a0, 2) ^ _gf_mul(a1, 3) ^ a2 ^ a3
        s[4 * c + 1] = a0 ^ _gf_mul(a1, 2) ^ _gf_mul(a2, 3) ^ a3
        s[4 * c + 2] = a0 ^ a1 ^ _gf_mul(a2, 2) ^ _gf_mul(a3, 3)
        s[4 * c + 3] = _gf_mul(a0, 3) ^ a1 ^ a2 ^ _gf_mul(a3, 2)


def _encrypt_block(block, rk):
    s = list(block)
    _ark(s, rk[0])
    for rnd in range(1, 10):
        s = [_AES_SBOX[b] for b in s]
        s = _shift_rows(s)
        _mix_columns(s)
        _ark(s, rk[rnd])
    s = [_AES_SBOX[b] for b in s]
    s = _shift_rows(s)
    _ark(s, rk[10])
    return bytes(s)


def _cbc_encrypt(plaintext, key, iv):
    """AES-128-CBC + PKCS7（与真实 HLS 分片一致）。"""
    pad = 16 - (len(plaintext) % 16)
    data = plaintext + bytes([pad]) * pad
    rk = _expand_key(key)
    out, prev = bytearray(), iv
    for i in range(0, len(data), 16):
        enc = _encrypt_block(bytes(a ^ b for a, b in zip(data[i:i + 16], prev)), rk)
        out += enc
        prev = enc
    return bytes(out)


KEY16 = bytes.fromhex('000102030405060708090a0b0c0d0e0f')


class TestAesKat(unittest.TestCase):
    """外部已知答案测试：独立于自造 fixture，钉死 AES 实现正确性。"""

    def test_fips_197_appendix_c1_block(self):
        # FIPS-197 C.1：key=000102...0f, pt=00112233...ff, ct=69c4e0d86a7b0430d8cdb78070b4c55a
        ct = bytes.fromhex('69c4e0d86a7b0430d8cdb78070b4c55a')
        pt = bytes.fromhex('00112233445566778899aabbccddeeff')
        self.assertEqual(_aes_decrypt_block(ct, _expand_key(KEY16)), pt)

    def test_sp800_38a_aes128_cbc(self):
        # SP 800-38A F.2.1（AES-128-CBC）：4 块密文解出 4 块明文（含 IV 链式）
        key = bytes.fromhex('2b7e151628aed2a6abf7158809cf4f3c')
        iv = bytes.fromhex('000102030405060708090a0b0c0d0e0f')
        ct = bytes.fromhex('7649abac8119b246cee98e9b12e9197d'
                           '5086cb9b507219ee95db113a917678b2')
        pt = _aes_cbc_decrypt(ct, key, iv)
        self.assertEqual(pt.hex(),
                         '6bc1bee22e409f96e93d7e117393172a'
                         'ae2d8a571e03ac9c9eb76fac45af8e51')

    def test_cbc_prefix_mode_no_unpad_and_chain(self):
        # 前缀模式：仅解前 ceil(N/16) 块、不去 PKCS7；整段模式去填充
        data = bytes(range(48))
        ct = _cbc_encrypt(data, KEY16, bytes(16))
        full = _aes_cbc_decrypt(ct, KEY16, bytes(16))
        self.assertEqual(full, data)                       # 整段 -> 去填充还原
        pref = _aes_cbc_decrypt(ct, KEY16, bytes(16), max_bytes=20)
        self.assertEqual(pref, data[:20])                  # 20 字节 -> 2 块(32B)截前 20


class TestParseExtXKey(unittest.TestCase):
    """parse_ext_x_key 表驱动 + 逐分片对齐（AC-1.6）。"""

    def test_multi_key_aligned_per_segment(self):
        pl = ('#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n'
              '#EXTINF:6,\nseg0.ts\n#EXTINF:6,\nseg1.ts\n#EXTINF:6,\nseg2.ts\n'
              '#EXT-X-KEY:METHOD=AES-128,URI="k.bin"\n'
              '#EXTINF:6,\nseg3.ts\n#EXTINF:6,\nseg4.ts\n')
        kv = parse_ext_x_key(pl)
        self.assertEqual(len(kv['segs']), 5)
        self.assertEqual(len(kv['keys']), 5)
        self.assertIsNone(kv['keys'][0])                   # 前 3 段无 KEY
        self.assertIsNone(kv['keys'][2])
        self.assertIsNotNone(kv['keys'][3])                # 后 2 段生效
        self.assertIsNotNone(kv['keys'][4])
        self.assertEqual(kv['keys'][3]['method'], 'AES-128')
        self.assertEqual(kv['keys'][3]['uri'], 'k.bin')

    def test_method_none_resets(self):
        pl = ('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="k"\nseg0.ts\n'
              '#EXT-X-KEY:METHOD=NONE\nseg1.ts\n')
        kv = parse_ext_x_key(pl)
        self.assertIsNotNone(kv['keys'][0])
        self.assertIsNone(kv['keys'][1])

    def test_media_sequence_and_iv(self):
        pl = ('#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n'
              '#EXT-X-KEY:METHOD=AES-128,URI="k",IV=0x00000000000000000000000000000009\n'
              'seg0.ts\n')
        kv = parse_ext_x_key(pl)
        self.assertEqual(kv['media_sequence'], 7)
        self.assertEqual(kv['keys'][0]['iv'],
                         '0x00000000000000000000000000000009')


class TestClassifyEncryption(unittest.TestCase):
    def test_triples(self):
        cases = [
            (None, ('', '', '')),
            ({'method': 'NONE'}, ('', '', '')),
            ({'method': 'AES-128', 'keyformat': 'identity'}, ('aes128', '', 'identity')),
            ({'method': 'SAMPLE-AES', 'keyformat': 'identity'}, ('sample-aes', '', 'identity')),
            ({'method': 'SAMPLE-AES',
              'keyformat': 'urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed'},
             ('cbcs', 'widevine', 'urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed')),
            ({'method': 'SAMPLE-AES-CTR',
              'keyformat': 'urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95'},
             ('cenc', 'playready', 'urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95')),
        ]
        for key, want in cases:
            with self.subTest(key=key):
                self.assertEqual(classify_encryption(key), want)


class TestAesHlsFixture(unittest.TestCase):
    """AC-1.1：AES-128 加密 HLS fixture 解出 video 且 encryption='aes128'。"""

    def test_aes128_encrypted_segment_detected_as_video(self):
        plain = make_ts_video()
        ct = _cbc_encrypt(plain, KEY16, bytes(16))     # media_seq 0 + idx 0 -> IV=0
        pl = (b'#EXTM3U\n#EXT-X-TARGETDURATION:6\n'
              b'#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n'
              b'#EXTINF:6,\nseg.ts\n')
        net = FakeNet({'http://x/enc.m3u8': (200, pl, ''),
                       'http://x/key.bin': (200, KEY16, 'application/octet-stream'),
                       'http://x/seg.ts': (200, ct, 'video/mp2t')})
        r = probe_m3u8(net, 'http://x/enc.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertTrue(r['av'].startswith('video'))
        self.assertEqual(r['encryption'], 'aes128')
        self.assertTrue(r['key_present'])

    def test_key_unreachable_is_not_fail(self):
        """密钥 URI 不可达 -> alive False 但 verdict_reason='key_unreachable'（判 abnormal）。"""
        pl = (b'#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="nope.bin"\n'
              b'#EXTINF:6,\nseg.ts\n')
        net = FakeNet({'http://x/enc.m3u8': (200, pl, '')})
        r = probe_m3u8(net, 'http://x/enc.m3u8', None)
        self.assertFalse(r['alive'])
        self.assertEqual(r['verdict_reason'], 'key_unreachable')
        self.assertEqual(r['encryption'], 'aes128')

    def test_retry_first_segment_uses_keys0(self):
        """§8-⑧：末分片解密失败回退首分片时必须改用 keys[0]（此处首段为明文）。"""
        plain = make_ts_video()
        # seg_plain 为明文 TS（keys[0]=None）；seg_bad 触发 aes-undecryptable（长度非对齐）
        pl = (b'#EXTM3U\n#EXTINF:6,\nseg_plain.ts\n'
              b'#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXTINF:6,\nseg_bad.ts\n')
        net = FakeNet({'http://x/live.m3u8': (200, pl, ''),
                       'http://x/key.bin': (200, KEY16, ''),
                       'http://x/seg_plain.ts': (200, plain, 'video/mp2t'),
                       'http://x/seg_bad.ts': (200, b'\x01' * 17, '')})   # 17B 非对齐
        r = probe_m3u8(net, 'http://x/live.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertTrue(r['av'].startswith('video'))     # 若误用 keys[-1] 解明文会判不出
        self.assertEqual(r['encryption'], '')            # 回退到首段（明文）


class _RecordingNet(object):
    """记录 fetch 调用的假网络（验证 scheme 拦截不发起任何请求）。"""

    ua = 'test-ua'

    def __init__(self):
        self.fetched = []

    @staticmethod
    def enc(url):
        return url

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        self.fetched.append(url)
        raise OSError('should not be called')


class TestSchemeInterception(unittest.TestCase):
    """R2：不支持协议前置拦截（AC-2.1/2.5）。"""

    SCHEMES = ['rtmp', 'rtsp', 'rtp', 'udp', 'p2p', 'mitv', 'zlive', 'tvbus']

    def test_unsupported_scheme_no_network(self):
        for s in self.SCHEMES:
            net = _RecordingNet()
            r = probe(net, '%s://x/y' % s, None, unsupported_schemes=self.SCHEMES)
            self.assertTrue(r['unsupported'])
            self.assertEqual(r['reason'], 'scheme:' + s)
            self.assertEqual(net.fetched, [])            # 零网络请求

    def test_case_insensitive(self):
        net = _RecordingNet()
        r = probe(net, 'RTMP://x/y', None, unsupported_schemes=self.SCHEMES)
        self.assertTrue(r['unsupported'])

    def test_http_path_with_rtmp_not_blocked(self):
        """http://host/live.rtmp 的 scheme 是 http -> 不拦。"""
        r = probe(_RecordingNet(), 'http://host/live.rtmp', None,
                  unsupported_schemes=self.SCHEMES)
        self.assertNotIn('unsupported', r)


class TestCapsGate(unittest.TestCase):
    """R4：能力门禁纯函数（AC-4.1/4.4/4.5/4.6）。"""

    def test_build_caps_required(self):
        self.assertEqual(build_caps_required('hls', 'aes128', ''), {})
        self.assertEqual(build_caps_required('hls', '', ''), {})
        self.assertEqual(build_caps_required('hls', 'sample-aes', ''),
                         {'hls_sample_aes': True})
        self.assertEqual(build_caps_required('dash', 'cenc', 'clearkey'),
                         {'dash_clearkey': True})
        self.assertEqual(build_caps_required('hls', 'cenc', 'widevine'),
                         {'hls_cenc': True, 'widevine': True})

    def test_missing_cap_not_allowed(self):
        allowed, missing = check_caps({'dash_clearkey': True},
                                      {'version': '1.0.0', 'caps': {'dash_clearkey': False}})
        self.assertFalse(allowed)
        self.assertEqual(missing, 'dash_clearkey')

    def test_config_invalid_conservative_fail(self):
        for bad in (None, {}, {'caps': {'dash_clearkey': True}},
                    {'version': '1.0.0'}):
            allowed, missing = check_caps({'dash_clearkey': True}, bad)
            self.assertFalse(allowed)
            self.assertEqual(missing, 'caps_config_invalid')

    def test_plaintext_unaffected_by_bad_config(self):
        # 明文源 caps_required 为空 -> 恒通过（配置缺失不影响明文）
        self.assertEqual(check_caps({}, None), (True, ''))
        self.assertEqual(check_caps({}, {}), (True, ''))


def _make_root():
    tmp = tempfile.mkdtemp(prefix='iptv-p0-verifier-')
    write_test_config(tmp)
    return tmp


def _insert(db, url, **kw):
    now = utcnow()
    vals = {'key': 'CCTV1', 'status': 'candidate', 'av': None, 'encryption': '',
            'container': '', 'caps_required': None, 'profile': 'direct',
            'verdict_reason': None, 'drm': None, 'drm_key': None, 'kid': None}
    vals.update(kw)
    db.execute(
        'INSERT INTO sources(url, url_hash, channel_name, channel_key, origin, status, av, '
        'score, first_seen, last_seen, encryption, container, profile, caps_required, '
        'verdict_reason, drm, drm_key, kid) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
        (url, url, vals['key'], vals['key'], 'test', vals['status'], vals['av'], 10, now, now,
         vals['encryption'], vals['container'], vals['profile'], vals['caps_required'],
         vals['verdict_reason'], vals['drm'], vals['drm_key'], vals['kid']))
    return db.query_one('SELECT * FROM sources WHERE url_hash=?', (url,))['id']


# =====================================================================================
# 首次验证整批传输故障熔断（candidate 批次）
# =====================================================================================

class _RouteNet(object):
    """离线假网络：routes[url] 为异常实例/类（抛出）或 (status, body, ct)（返回）。"""

    ua = 'test-ua'

    def __init__(self, routes):
        self.routes = dict(routes)
        self.fetched = []
        self.lock = threading.Lock()

    @staticmethod
    def enc(url):
        return url

    def fetch(self, url, ua=None, limit=393216, timeout=None):
        with self.lock:
            self.fetched.append(url)
        if url not in self.routes:
            raise OSError('no route: %s' % url)
        action = self.routes[url]
        if isinstance(action, BaseException):
            raise action
        if isinstance(action, type) and issubclass(action, BaseException):
            raise action('route error')
        st, body, ct = action
        return st, body[:limit], ct


class _MainThreadClock(object):
    """只让主线程的 time.time() 计数，并在第 trigger_call 次跳到 big。

    Verifier.run 的预算检查在主线程；worker 的 t0/耗时读取在子线程一律返回 now。
    由此可确定性地"收齐 3 条结果后超时"，不依赖真实等待。
    """

    def __init__(self, trigger_call=4, now=1000.0, big=99999.0):
        self.main = threading.current_thread()
        self.trigger_call = trigger_call
        self.now = now
        self.big = big
        self.main_calls = 0

    def time(self):
        if threading.current_thread() is self.main:
            self.main_calls += 1
            if self.main_calls >= self.trigger_call:
                return self.big
        return self.now


class TestBatchCircuitBreaker(unittest.TestCase):
    """首次验证：整批传输层失败零落库；非传输/超时/不完整一律按原逻辑落库。"""

    def setUp(self):
        self.tmp = _make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.cfg.settings['limits']['verify_batch'] = 8
        self.cfg.settings['limits']['net_concurrency'] = 1

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, routes):
        return Verifier(self.cfg, self.db, _RouteNet(routes)).run()

    def _verifications(self):
        return self.db.query_one('SELECT COUNT(*) AS c FROM verifications')['c']

    def _source(self, sid):
        return self.db.query_one(
            'SELECT status, fail_streak, last_verified, backoff_until '
            'FROM sources WHERE id=?', (sid,))

    def test_all_urlerror_opens_circuit_without_db_writes(self):
        urls = ['http://x/c%d.m3u8' % i for i in range(3)]
        sids = [_insert(self.db, u) for u in urls]
        res = self._run({u: URLError('dns') for u in urls})
        self.assertIs(res['circuit_open'], True)
        self.assertIs(res['timeout'], False)
        self.assertEqual(res['batch'], 3)
        self.assertEqual(res['fail'], 0)
        self.assertEqual(self._verifications(), 0)
        for sid in sids:
            row = self._source(sid)
            self.assertEqual(row['status'], 'candidate')
            self.assertEqual(row['fail_streak'], 0)
            self.assertIsNone(row['last_verified'])
            self.assertIsNone(row['backoff_until'])

    def test_all_net_resolve_error_opens_circuit(self):
        urls = ['http://x/r%d.m3u8' % i for i in range(3)]
        sids = [_insert(self.db, u) for u in urls]
        res = self._run({u: NetResolveError('blocked non-public') for u in urls})
        self.assertIs(res['circuit_open'], True)
        self.assertEqual(self._verifications(), 0)
        for sid in sids:
            self.assertEqual(self._source(sid)['fail_streak'], 0)

    def test_all_ssl_errors_open_circuit(self):
        urls = ['http://x/s%d.m3u8' % i for i in range(4)]
        sids = [_insert(self.db, u) for u in urls]
        routes = {
            urls[0]: ssl.SSLError('tls'),
            urls[1]: ssl.SSLEOFError('tls eof'),
            urls[2]: ssl.SSLError('tls again'),
            urls[3]: ssl.SSLEOFError('tls eof again'),
        }
        res = self._run(routes)
        self.assertIs(res['circuit_open'], True)
        self.assertEqual(self._verifications(), 0)
        for sid in sids:
            self.assertEqual(self._source(sid)['fail_streak'], 0)

    def test_two_transport_failures_do_not_open_circuit(self):
        urls = ['http://x/two%d.m3u8' % i for i in range(2)]
        sids = [_insert(self.db, u) for u in urls]
        res = self._run({urls[0]: URLError('dns'), urls[1]: OSError('down')})
        self.assertIs(res['circuit_open'], False)
        self.assertEqual(res['fail'], 2)
        self.assertEqual(self._verifications(), 2)
        for sid in sids:
            self.assertEqual(self._source(sid)['fail_streak'], 1)

    def test_two_transport_plus_http_error_records_all(self):
        urls = ['http://x/mix%d.m3u8' % i for i in range(3)]
        sids = [_insert(self.db, u) for u in urls]
        routes = {
            urls[0]: URLError('dns'),
            urls[1]: OSError('down'),
            urls[2]: HTTPError('http://x/mix2.m3u8', 500, 'err', {}, None),
        }
        res = self._run(routes)
        self.assertIs(res['circuit_open'], False)
        self.assertEqual(res['fail'], 3)
        self.assertEqual(self._verifications(), 3)
        for sid in sids:
            self.assertEqual(self._source(sid)['fail_streak'], 1)

    def test_all_http_and_html_failures_do_not_open_circuit(self):
        urls = ['http://x/h%d.m3u8' % i for i in range(3)]
        sids = [_insert(self.db, u) for u in urls]
        routes = {
            urls[0]: HTTPError('http://x/h0.m3u8', 404, 'nf', {}, None),
            urls[1]: HTTPError('http://x/h1.m3u8', 500, 'err', {}, None),
            urls[2]: (200, b'<html>blocked</html>' + b'x' * 100, 'text/html'),
        }
        res = self._run(routes)
        self.assertIs(res['circuit_open'], False)
        self.assertEqual(res['fail'], 3)
        self.assertEqual(self._verifications(), 3)
        for sid in sids:
            self.assertEqual(self._source(sid)['fail_streak'], 1)

    def test_timeout_blocks_circuit(self):
        """mock 时钟：收齐 3 条传输失败后超时 -> 谓词为真但完整性门禁拒绝熔断。"""
        urls = ['http://x/to%d.m3u8' % i for i in range(4)]
        for u in urls:
            _insert(self.db, u)
        self.cfg.settings['limits']['verify_budget_min'] = 1
        clock = _MainThreadClock(trigger_call=4)
        with mock.patch.object(verifier_module, 'time', clock):
            res = self._run({u: URLError('dns') for u in urls})
        self.assertIs(res['timeout'], True)
        self.assertIs(res['circuit_open'], False)      # 不得用超时前的部分样本代表整批
        self.assertEqual(self._verifications(), 3)     # 已收齐的 3 条按原逻辑落库
        self.assertEqual(res['fail'], 3)
        self.assertEqual(
            self.db.query_one('SELECT COUNT(*) AS c FROM sources WHERE fail_streak=1')['c'], 3)

    def test_incomplete_results_block_circuit(self):
        """future 异常导致结果未收齐（3 传输 + 1 异常）-> 谓词为真但不得熔断。"""
        urls = ['http://x/inc%d.m3u8' % i for i in range(4)]
        sids = [_insert(self.db, u) for u in urls]
        boom = urls[0]

        def fake_probe(net, url, ua=None, **kwargs):
            if url == boom:
                raise RuntimeError('boom')
            return {'alive': False, 'why': 'URLError'}

        with mock.patch.object(verifier_module, 'probe', side_effect=fake_probe):
            res = self._run({u: URLError('dns') for u in urls})
        self.assertIs(res['timeout'], False)
        self.assertIs(res['circuit_open'], False)
        self.assertEqual(self._verifications(), 3)
        boom_row = self._source(sids[0])
        self.assertIsNone(boom_row['last_verified'])
        self.assertEqual(boom_row['fail_streak'], 0)

    def test_batch_predicate_transport_only(self):
        def r(why, alive=False, vr=None):
            d = {'alive': alive, 'why': why}
            if vr is not None:
                d['verdict_reason'] = vr
            return ({'id': 1}, d, 1)

        self.assertTrue(Verifier._should_circuit_open(
            [r('URLError'), r('NetResolveError'), r('SSLError')]))
        self.assertTrue(Verifier._should_circuit_open(
            [r('x', vr='SSLEOFError'), r('x', vr='URLError'), r('x', vr='OSError')]))
        self.assertFalse(Verifier._should_circuit_open([r('URLError'), r('OSError')]))
        self.assertFalse(Verifier._should_circuit_open(
            [r('URLError'), r('OSError'), r('URLError', alive=True)]))
        self.assertFalse(Verifier._should_circuit_open(
            [r('URLError'), r('OSError'), r('HTTPError')]))
        self.assertFalse(Verifier._should_circuit_open(
            [r('URLError'), r('OSError'), r('NetPolicyError')]))
        self.assertFalse(Verifier._should_circuit_open([r(''), r(''), r('')]))


class TestRecordSixClass(unittest.TestCase):
    """R3/R4：record() 6 分类 + 密钥缺口优先 + 签名 403 expired（AC-3.6/4.4）。"""

    def setUp(self):
        self.tmp = _make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.v = Verifier(self.cfg, self.db, _RecordingNet())

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_need_key_when_key_missing(self):
        sid = _insert(self.db, 'http://x/c.mpd', encryption='cenc', container='dash')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)', 'container': 'dash',
                            'encryption': 'cenc', 'drm': 'widevine', 'key_present': False}, 50)
        cur = self.db.query_one('SELECT status, profile, verdict_reason FROM sources WHERE id=?',
                                (sid,))
        self.assertEqual(cur['status'], 'drm')
        self.assertEqual(cur['profile'], 'need-key')
        self.assertEqual(cur['verdict_reason'], 'missing_key')
        vr = self.db.query_one('SELECT result FROM verifications WHERE source_id=?', (sid,))
        self.assertEqual(vr['result'], 'need_key')

    def test_need_client_cap_when_caps_missing(self):
        # caps.hls_sample_aes=false -> sample-aes 且密钥可取 -> need_client_cap
        sid = _insert(self.db, 'http://x/s.m3u8', encryption='sample-aes', container='hls')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)', 'container': 'hls',
                            'encryption': 'sample-aes', 'drm': '', 'key_present': True}, 50)
        cur = self.db.query_one('SELECT status, profile, verdict_reason FROM sources WHERE id=?',
                                (sid,))
        self.assertEqual(cur['status'], 'drm')
        self.assertEqual(cur['profile'], 'unsupported-drm')
        self.assertEqual(cur['verdict_reason'], 'caps:hls_sample_aes')

    def test_aes128_is_direct_ok(self):
        sid = _insert(self.db, 'http://x/a.m3u8')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)', 'container': 'hls',
                            'encryption': 'aes128', 'drm': '', 'key_present': True}, 50)
        cur = self.db.query_one('SELECT status, profile, encryption FROM sources WHERE id=?',
                                (sid,))
        self.assertEqual(cur['status'], 'active')
        self.assertEqual(cur['profile'], 'direct')
        self.assertEqual(cur['encryption'], 'aes128')

    # ---------- DRM/profile 派生漂移修复：探针未携带 DRM 事实时的有效事实 ----------
    _CLEARKEY_JSON = '{"keys":[{"kty":"oct","k":"AAAA"}],"type":"temporary"}'

    def _stored_drm_source(self):
        return _insert(
            self.db, 'http://x/stored.mpd', encryption='cenc', container='dash',
            drm='clearkey', drm_key=self._CLEARKEY_JSON,
            caps_required='{"dash_clearkey": true}', profile='direct')

    def test_stored_drm_without_probe_facts_never_direct(self):
        """存量 cenc+clearkey+drm_key：探针结果未带 DRM 事实也不得漂移成 active/direct。"""
        sid = self._stored_drm_source()
        self.cfg.settings['client_caps'] = {'version': 'test', 'caps': {'dash_clearkey': True}}
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        # 探针只报视频存活，container 缺失，无 encryption/drm/key_present
        self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)'}, 50)
        cur = self.db.query_one(
            'SELECT status, profile, drm, encryption, caps_required, kid FROM sources WHERE id=?',
            (sid,))
        self.assertEqual(cur['status'], 'active')
        self.assertEqual(cur['profile'], 'media3-json')          # 不得为 direct
        self.assertEqual(cur['drm'], 'clearkey')                 # provenance 保留
        self.assertEqual(cur['encryption'], 'cenc')
        self.assertIn('dash_clearkey', cur['caps_required'])     # caps 基于有效事实
        # 不变量守卫：active/direct 与 DRM 事实互斥
        self.assertFalse(cur['profile'] == 'direct' and (cur['drm'] or ''))

    def test_stored_drm_without_probe_facts_requires_cap(self):
        """有效事实需要客户端不具备的能力 -> need_client_cap/unsupported-drm（而非 direct/need_key）。"""
        sid = self._stored_drm_source()
        self.cfg.settings['client_caps'] = {'version': 'test', 'caps': {}}   # 缺 dash_clearkey
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)'}, 50)
        cur = self.db.query_one(
            'SELECT status, profile, verdict_reason, drm FROM sources WHERE id=?', (sid,))
        self.assertEqual(cur['status'], 'drm')
        self.assertEqual(cur['profile'], 'unsupported-drm')
        self.assertTrue(cur['verdict_reason'].startswith('caps:'))
        self.assertEqual(cur['drm'], 'clearkey')

    def test_stored_drm_key_prevents_false_missing_key(self):
        """库内已有非空 drm_key -> enc 存在也不算 missing_key（探针无 key_present）。"""
        sid = self._stored_drm_source()
        self.cfg.settings['client_caps'] = {'version': 'test', 'caps': {'dash_clearkey': True}}
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)', 'encryption': 'cenc'}, 50)
        cur = self.db.query_one('SELECT status, profile, verdict_reason FROM sources WHERE id=?',
                                (sid,))
        self.assertNotEqual(cur['verdict_reason'], 'missing_key')
        self.assertEqual(cur['status'], 'active')
        self.assertEqual(cur['profile'], 'media3-json')

    def test_failed_probe_keeps_stored_drm_provenance(self):
        """失败探针不得清空 drm/encryption provenance。"""
        sid = self._stored_drm_source()
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': False, 'why': 'URLError'}, 10)
        cur = self.db.query_one('SELECT status, drm, encryption FROM sources WHERE id=?', (sid,))
        self.assertEqual(cur['drm'], 'clearkey')
        self.assertEqual(cur['encryption'], 'cenc')
        self.assertEqual(cur['status'], 'candidate')

    def test_stored_plain_without_probe_facts_stays_direct(self):
        """无 DRM 的明文源（探针未带事实）仍 direct —— 修复不得把明文误判为 DRM。"""
        sid = _insert(self.db, 'http://x/plain.m3u8', encryption='', container='hls')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)'}, 50)
        cur = self.db.query_one('SELECT status, profile FROM sources WHERE id=?', (sid,))
        self.assertEqual(cur['status'], 'active')
        self.assertEqual(cur['profile'], 'direct')

    def test_aes128_stored_without_probe_encryption_stays_direct(self):
        """存量 aes128（探针未带 encryption，但报告 key 可取）仍 direct —— 不误判为 DRM。"""
        sid = _insert(self.db, 'http://x/aes.m3u8', encryption='aes128', container='hls')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': True, 'av': 'video(TS:H.264视频)',
                            'key_present': True}, 50)
        cur = self.db.query_one('SELECT status, profile, encryption FROM sources WHERE id=?',
                                (sid,))
        self.assertEqual(cur['status'], 'active')
        self.assertEqual(cur['profile'], 'direct')
        self.assertEqual(cur['encryption'], 'aes128')

    def test_unsupported_scheme_writes_unsupported(self):
        sid = _insert(self.db, 'rtmp://x/y')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': False, 'unsupported': True, 'reason': 'scheme:rtmp'}, 10)
        cur = self.db.query_one('SELECT status, fail_streak, backoff_until, verdict_reason '
                                'FROM sources WHERE id=?', (sid,))
        self.assertEqual(cur['status'], 'unsupported')
        self.assertEqual(cur['fail_streak'], 0)
        self.assertIsNone(cur['backoff_until'])
        self.assertEqual(cur['verdict_reason'], 'scheme:rtmp')

    def test_key_unreachable_is_abnormal_not_fail(self):
        sid = _insert(self.db, 'http://x/k.m3u8', encryption='aes128', container='hls')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': False, 'why': 'key_unreachable',
                            'verdict_reason': 'key_unreachable', 'encryption': 'aes128',
                            'container': 'hls'}, 30)
        cur = self.db.query_one('SELECT status, fail_streak FROM sources WHERE id=?', (sid,))
        self.assertEqual(cur['status'], 'candidate')       # 保持 candidate，不进 fail 退避
        self.assertEqual(cur['fail_streak'], 0)
        vr = self.db.query_one('SELECT result FROM verifications WHERE source_id=?', (sid,))
        self.assertEqual(vr['result'], 'abnormal')

    def test_signed_403_is_expired_short_backoff(self):
        from app.db import iso_plus_hours
        sid = _insert(self.db, 'http://x/l.m3u8?token=abc&playlive=1')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': False, 'why': 'HTTPError', 'code': 403}, 30)
        cur = self.db.query_one('SELECT verdict_reason, backoff_until FROM sources WHERE id=?',
                                (sid,))
        self.assertEqual(cur['verdict_reason'], 'expired')
        self.assertIsNotNone(cur['backoff_until'])
        self.assertLess(cur['backoff_until'], iso_plus_hours(48))   # < 48h（非 30 天）

    def test_unsigned_403_still_long_cooldown(self):
        from app.db import iso_plus_hours
        sid = _insert(self.db, 'http://x/plain.m3u8')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': False, 'why': 'HTTPError', 'code': 403}, 30)
        cur = self.db.query_one('SELECT backoff_until FROM sources WHERE id=?', (sid,))
        self.assertGreater(cur['backoff_until'], iso_plus_hours(20 * 24))

    def test_key_authid_not_treated_as_signed(self):
        """静态访问 key/authid 不算时效签名（403 -> 30 天长冷却）。"""
        from app.db import iso_plus_hours
        sid = _insert(self.db, 'http://x/l.m3u8?key=fixture-auth-eec577e289ee&authid=0')
        row = self.db.query_one('SELECT * FROM sources WHERE id=?', (sid,))
        self.v.record(row, {'alive': False, 'why': 'HTTPError', 'code': 403}, 30)
        cur = self.db.query_one('SELECT backoff_until FROM sources WHERE id=?', (sid,))
        self.assertGreater(cur['backoff_until'], iso_plus_hours(20 * 24))


class TestReevaluateDrmPool(unittest.TestCase):
    """R4：能力版本变更后的只读重判（AC-4.1/4.2/4.3）。"""

    def setUp(self):
        self.tmp = _make_root()
        self.cfg = Config(self.tmp)
        self.db = Database(self.cfg.db_path)
        self.db.migrate()
        self.v = Verifier(self.cfg, self.db, _RecordingNet())

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _drm_source(self):
        return _insert(
            self.db, 'http://x/d.mpd', status='drm', encryption='cenc', container='dash',
            profile='unsupported-drm', caps_required='{"dash_clearkey": true}',
            verdict_reason='caps:dash_clearkey')

    def test_toggle_cap_promotes_then_demotes_without_reprobe(self):
        sid = self._drm_source()
        last_verified = self.db.query_one(
            'SELECT last_verified FROM sources WHERE id=?', (sid,))['last_verified']
        before = self.db.query_one('SELECT COUNT(*) c FROM verifications')['c']

        # 关闭 dash_clearkey -> 仍 drm / unsupported-drm，reason 含键名
        self.cfg.settings['client_caps']['caps']['dash_clearkey'] = False
        res = self.v.reevaluate_drm_pool()
        self.assertEqual(res['scanned'], 1)
        self.assertEqual(res['promoted'], 0)
        cur = self.db.query_one('SELECT status, profile, verdict_reason, last_verified '
                                'FROM sources WHERE id=?', (sid,))
        self.assertEqual(cur['status'], 'drm')
        self.assertEqual(cur['profile'], 'unsupported-drm')
        self.assertIn('dash_clearkey', cur['verdict_reason'])
        self.assertEqual(cur['last_verified'], last_verified)      # 不重探
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM verifications')['c'], before)

        # 改回 true -> 提升 active/media3-json，仍不写 verifications
        self.cfg.settings['client_caps']['caps']['dash_clearkey'] = True
        res = self.v.reevaluate_drm_pool()
        self.assertEqual(res['promoted'], 1)
        cur = self.db.query_one('SELECT status, profile, last_verified FROM sources WHERE id=?',
                                (sid,))
        self.assertEqual(cur['status'], 'active')
        self.assertIn(cur['profile'], ('direct', 'm3u-kodiprop', 'media3-json'))
        self.assertEqual(cur['profile'], 'media3-json')
        self.assertEqual(cur['last_verified'], last_verified)
        self.assertEqual(self.db.query_one('SELECT COUNT(*) c FROM verifications')['c'], before)

    def test_key_gap_stays_need_key(self):
        sid = _insert(self.db, 'http://x/w.mpd', status='drm', encryption='cenc',
                      container='dash', profile='need-key',
                      caps_required='{"dash_clearkey": true}', verdict_reason='missing_key')
        self.cfg.settings['client_caps']['caps']['dash_clearkey'] = True
        self.v.reevaluate_drm_pool()
        cur = self.db.query_one('SELECT status, profile FROM sources WHERE id=?', (sid,))
        self.assertEqual(cur['status'], 'drm')             # 密钥缺口不受能力变更影响
        self.assertEqual(cur['profile'], 'need-key')

    def test_inline_key_is_not_stuck_need_key(self):
        """库内已有非空内联密钥 -> 不存在缺口，按能力提升为 media3-json（非 need-key/direct）。"""
        sid = _insert(self.db, 'http://x/wk.mpd', status='drm', encryption='cenc',
                      container='dash', profile='need-key', drm='clearkey',
                      drm_key='{"keys":[{"kty":"oct","k":"AAAA"}]}',
                      caps_required='{"dash_clearkey": true}', verdict_reason='missing_key')
        self.cfg.settings['client_caps']['caps']['dash_clearkey'] = True
        res = self.v.reevaluate_drm_pool()
        self.assertEqual(res['promoted'], 1)
        cur = self.db.query_one('SELECT status, profile, drm FROM sources WHERE id=?', (sid,))
        self.assertEqual(cur['status'], 'active')
        self.assertEqual(cur['profile'], 'media3-json')
        self.assertEqual(cur['drm'], 'clearkey')


if __name__ == '__main__':
    unittest.main()
