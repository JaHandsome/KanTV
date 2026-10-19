import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from prepare_notices import notice_entries, read_pom, license_id
from recombine_apk import rewrite


def elf(abi='arm64-v8a'):
    data = bytearray(64)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', data, 18, 183)
    return bytes(data)


class DistributionTest(unittest.TestCase):
    def test_recombines_native_preserving_every_other_entry_and_license(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / 'input.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('lib/arm64-v8a/libffmpegJNI.so', b'original')
                z.writestr('classes.dex', b'dex-byte-content')
                z.writestr('META-INF/NOTICE.txt', b'license-notice')
                z.writestr('META-INF/CERT.RSA', b'old-signature')
                z.writestr('META-INF/CERT.SF', b'old-signature')
                z.writestr('META-INF/MANIFEST.MF', b'old-digests')
            native = root / 'native/arm64-v8a'
            native.mkdir(parents=True)
            (native / 'libffmpegJNI.so').write_bytes(elf())
            result = rewrite(apk, native.parent, root / 'output.apk')
            self.assertEqual(len(result), 1)
            with zipfile.ZipFile(root / 'output.apk') as z:
                self.assertEqual(z.read('classes.dex'), b'dex-byte-content')
                self.assertEqual(z.read('META-INF/NOTICE.txt'), b'license-notice')
                self.assertEqual(z.read('lib/arm64-v8a/libffmpegJNI.so'), elf())
                self.assertNotIn('META-INF/CERT.RSA', z.namelist())
                self.assertNotIn('META-INF/MANIFEST.MF', z.namelist())

    def test_rejects_wrong_architecture(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with zipfile.ZipFile(root / 'in.apk', 'w') as z:
                z.writestr('lib/armeabi-v7a/libffmpegJNI.so', b'original')
            (root / 'armeabi-v7a').mkdir()
            (root / 'armeabi-v7a/libffmpegJNI.so').write_bytes(elf())
            with self.assertRaisesRegex(ValueError, 'ABI mismatch'):
                rewrite(root / 'in.apk', root, root / 'out.apk')

    def test_preserves_nested_runtime_notices_without_matching_class_names(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as z:
            z.writestr('META-INF/NOTICE.txt', b'notice')
            z.writestr('example/LicenseHelper.class', b'class')
        aar = io.BytesIO()
        with zipfile.ZipFile(aar, 'w') as z:
            z.writestr('classes.jar', nested.getvalue())
            z.writestr('third_party_licenses.json', b'{}')
        self.assertEqual(dict(notice_entries(aar.getvalue())), {
            'classes.jar!/META-INF/NOTICE.txt': b'notice', 'third_party_licenses.json': b'{}'})

    def test_pom_inherits_parent_license(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            base = '<project xmlns="http://maven.apache.org/POM/4.0.0">%s</project>'
            parent = root / 'example/parent/1/hash'
            child = root / 'example/child/1/hash'
            parent.mkdir(parents=True)
            child.mkdir(parents=True)
            (parent / 'parent.pom').write_text(base % '<licenses><license><name>Apache 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>')
            (child / 'child.pom').write_text(base % '<parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent>')
            licenses, evidence, _ = read_pom(root, 'example:child:1')
            self.assertEqual(license_id(licenses), ['Apache-2.0'])
            self.assertEqual(len(evidence), 2)

    def test_unknown_license_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'Unreviewed license'):
            license_id([{'name': 'unknown proprietary terms', 'url': ''}])


if __name__ == '__main__':
    unittest.main()
