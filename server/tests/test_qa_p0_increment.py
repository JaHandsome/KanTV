# -*- coding: utf-8 -*-
"""QA 独立补充测试（P0 增量）——针对工程师自测未覆盖的边角。

本文件由 QA（严过关）在独立验证阶段新增，覆盖：
- 用 openssl（独立实现）交叉验证纯 Python AES-CBC（无 openssl 时自动跳过）；
- HLS #EXT-X-MEDIA-SEQUENCE 派生 IV 的端到端解密（工程师用例仅覆盖 media_seq=0）；
- parse_ext_x_key 小写属性名 / URI 含逗号 的解析鲁棒性；
- _is_signed_url 精确匹配（禁子串，排除 key/authid）表驱动；
- 无扩展名 HLS 走内容校验分发。
"""
import os
import shutil
import subprocess
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from app.verifier import (_aes_cbc_decrypt, _derive_hls_iv,  # noqa: E402
                          _is_signed_url, parse_ext_x_key, probe, probe_m3u8)

HAS_OPENSSL = shutil.which('openssl') is not None


class FakeNet(object):
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


def _make_ts():
    pat = bytearray(b'\xff' * 188)
    pat[0:4] = b'\x47\x00\x00\x50'; pat[4] = 0; pat[5] = 0
    pat[6] = 1; pat[7] = 0xE0; pat[8] = 0x50
    pmt = bytearray(b'\xff' * 188)
    pmt[0:4] = b'\x47\x00\x50\x50'; pmt[4] = 0; pmt[5] = 2
    pmt[17] = 0x1B; pmt[18] = 0xE1
    return bytes(pat) + bytes(pmt) + (b'\x00\x00\x01\xe0' + b'\xaa' * 20) * 45


def _ossl_encrypt(plain, key, iv):
    """openssl AES-128-CBC 无填充加密（独立于 verifier 的 AES 实现）。"""
    p = subprocess.run(['openssl', 'enc', '-aes-128-cbc', '-K', key.hex(), '-iv', iv.hex(),
                        '-nopad'], input=plain, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode())
    return p.stdout


@unittest.skipUnless(HAS_OPENSSL, '需要 openssl 作为独立 AES oracle')
class TestAesCrossOpenssl(unittest.TestCase):
    """用 openssl 独立交叉验证 CBC 整段/前缀模式（非同一实现，防恒过）。"""

    KEY = bytes.fromhex('2b7e151628aed2a6abf7158809cf4f3c')
    IV = bytes.fromhex('000102030405060708090a0b0c0d0e0f')

    def test_full_decrypt_matches_openssl(self):
        pt = bytes((i * 7 + 1) & 0xFF for i in range(100))
        ct = _ossl_encrypt(pt + bytes([12]) * 12, self.KEY, self.IV)  # PKCS7 手工补齐
        self.assertEqual(_aes_cbc_decrypt(ct, self.KEY, self.IV), pt)

    def test_prefix_mode_no_unpad(self):
        # 明文末尾恰为合法 padding 形状（0x01），前缀模式不得误删
        pt = b'A' * 15 + b'\x01'
        ct = _ossl_encrypt(pt, self.KEY, self.IV)
        self.assertEqual(_aes_cbc_decrypt(ct, self.KEY, self.IV, max_bytes=16), pt)

    def test_prefix_mode_non_multiple_of_16(self):
        pt = bytes((i * 13 + 5) & 0xFF for i in range(64))
        ct = _ossl_encrypt(pt, self.KEY, self.IV)
        for nb in (1, 17, 20, 33):
            got = _aes_cbc_decrypt(ct, self.KEY, self.IV, max_bytes=nb)
            self.assertEqual(got, pt[:nb])


class TestHlsIvDerivation(unittest.TestCase):
    def test_explicit_iv_priority(self):
        iv = bytes.fromhex('00' * 15 + '09')
        self.assertEqual(_derive_hls_iv(5, 3, iv), iv)

    def test_default_iv_big_endian(self):
        self.assertEqual(_derive_hls_iv(100, 2, None), (102).to_bytes(16, 'big'))

    @unittest.skipUnless(HAS_OPENSSL, '需要 openssl 构造密文')
    def test_media_sequence_derived_iv_end_to_end(self):
        """media_seq=100、末段 idx=2 -> IV=(102).to_bytes；真解密应判 video。"""
        key = bytes.fromhex('000102030405060708090a0b0c0d0e0f')
        plain = _make_ts()
        aligned = plain + b'\x00' * ((16 - len(plain) % 16) % 16)
        ct = _ossl_encrypt(aligned, key, (102).to_bytes(16, 'big'))
        pl = (b'#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:100\n#EXT-X-KEY:METHOD=AES-128,URI="k"\n'
              b'#EXTINF:6,\ns0.ts\n#EXTINF:6,\ns1.ts\n#EXTINF:6,\ns2.ts\n')
        net = FakeNet({'http://x/l.m3u8': (200, pl, ''), 'http://x/k': (200, key, ''),
                       'http://x/s2.ts': (200, ct, 'video/mp2t')})
        r = probe_m3u8(net, 'http://x/l.m3u8', None)
        self.assertTrue(r['alive'])
        self.assertTrue(r['av'].startswith('video'))
        self.assertEqual(r['encryption'], 'aes128')


class TestParseExtXKeyRobust(unittest.TestCase):
    def test_lowercase_attr_names(self):
        kv = parse_ext_x_key('#EXTM3U\n#EXT-X-KEY:method=aes-128,uri="k"\nseg.ts\n')
        self.assertEqual(kv['keys'][0]['method'].upper(), 'AES-128')
        self.assertEqual(kv['keys'][0]['uri'], 'k')

    def test_uri_with_comma_and_equals(self):
        kv = parse_ext_x_key('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="http://x/k?a=b,c"\nseg.ts\n')
        self.assertEqual(kv['keys'][0]['uri'], 'http://x/k?a=b,c')

    def test_key_after_segment_not_applied(self):
        kv = parse_ext_x_key('#EXTM3U\nseg.ts\n#EXT-X-KEY:METHOD=AES-128,URI="k"\n')
        self.assertIsNone(kv['keys'][0])


class TestSignedUrlPreciseMatch(unittest.TestCase):
    PARAMS = ['expires', 'expire', 'expires_at', 'token', 'securitykey', 'sign',
              'signature', 'msisdn']

    def test_table(self):
        cases = [
            ('http://x/a.m3u8?expires=1', True),
            ('http://x/a.m3u8?EXPIRES=1', True),
            ('http://x/a.m3u8?design=1', False),      # 不得子串命中 sign
            ('http://x/a.m3u8?tokenizer=1', False),   # 不得子串命中 token
            ('http://x/a.m3u8?signature=1', True),
            ('http://x/a.m3u8?key=fixture-auth-eec577e289ee&authid=0', False),   # 静态 access key 不算签名
            ('http://x/a.m3u8?SecurityKey=1', True),
            ('http://x/a.m3u8?playlive=1', False),
        ]
        for url, want in cases:
            with self.subTest(url=url):
                self.assertEqual(_is_signed_url(url, self.PARAMS), want)


class TestExtensionlessDispatch(unittest.TestCase):
    def test_extensionless_hls_dispatches_by_content(self):
        pl = b'#EXTM3U\n#EXTINF:6,\nseg.ts\n'
        net = FakeNet({'http://x/s': (200, pl, ''), 'http://x/seg.ts': (200, _make_ts(), '')})
        r = probe(net, 'http://x/s', None)
        self.assertTrue(r['alive'])
        self.assertTrue(r['av'].startswith('video'))


if __name__ == '__main__':
    unittest.main()
