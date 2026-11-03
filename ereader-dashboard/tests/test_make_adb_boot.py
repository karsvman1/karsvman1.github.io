"""Tests for tools/make_adb_boot.py. Run: py -3.10 -m unittest discover -s tests"""
import gzip
import hashlib
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import make_adb_boot as m  # noqa: E402

STOCK = os.path.join(ROOT, 'backup', '20261103-113527', 'boot.img')
STOCK_SHA256 = '08009edee18dd02342e2519b5668aa9169b1f68217e8b57dc4ff0faa044bf06b'
KEY = b'QUFBQQ== test@laptop\n'


def entry(name, body, ino=1, mode=0o100644):
    return ([ino, mode, 0, 0, 1, 0, len(body), 0, 0, 0, 0, len(name) + 1, 0], name, body)


class CpioTest(unittest.TestCase):
    def test_round_trip(self):
        entries = [entry('a.txt', b'hello'), entry('dir/b', b'x' * 7, 2), entry('TRAILER!!!', b'', 0, 0)]
        parsed = m.read_cpio(m.write_cpio(entries))
        self.assertEqual([(n, b) for _, n, b in parsed], [(n, b) for _, n, b in entries])

    def test_truncated_archive_is_refused(self):
        data = m.write_cpio([entry('a.txt', b'hello'), entry('TRAILER!!!', b'', 0, 0)])
        with self.assertRaises(m.BootImageError):
            m.read_cpio(data[:120])

    def test_missing_trailer_is_refused(self):
        with self.assertRaises(m.BootImageError):
            m.read_cpio(m.write_cpio([entry('a.txt', b'hello')]))


class ParseBootTest(unittest.TestCase):
    def test_not_android(self):
        with self.assertRaises(m.BootImageError):
            m.parse_boot(b'KRNL' + b'\0' * 100)

    def test_sizes_beyond_image(self):
        hdr = b'ANDROID!' + m.struct.pack(m.HDR_FMT, 10 ** 6, 0, 0, 0, 0, 0, 0, 2048, 0, 0)
        with self.assertRaises(m.BootImageError):
            m.parse_boot(hdr + b'\0' * 4096)


@unittest.skipUnless(os.path.exists(STOCK), 'stock boot backup not present')
class StockImageTest(unittest.TestCase):
    """Against the real backup read from the device on 2026-11-03."""

    @classmethod
    def setUpClass(cls):
        with open(STOCK, 'rb') as f:
            cls.stock = f.read()
        cls.hdr, cls.kernel, cls.ramdisk, cls.second = m.parse_boot(cls.stock)

    def files(self, ramdisk):
        return {n: b for _, n, b in m.read_cpio(gzip.decompress(ramdisk))}

    def test_backup_is_the_verified_one(self):
        self.assertEqual(hashlib.sha256(self.stock).hexdigest(), STOCK_SHA256)

    def test_patch_changes_exactly_three_files(self):
        before = self.files(self.ramdisk)
        after = self.files(m.patch_ramdisk(self.ramdisk, KEY))
        self.assertEqual(set(after) - set(before), {'adb_keys'})
        changed = sorted(n for n in before if before[n] != after[n])
        self.assertEqual(changed, ['default.prop', 'init.rc'])
        self.assertEqual(after['adb_keys'], KEY)
        self.assertIn(b'persist.sys.usb.config=mtp,adb', after['default.prop'])
        self.assertNotIn(b'persist.sys.usb.config=none', after['default.prop'])
        self.assertTrue(after['init.rc'].startswith(before['init.rc'].rstrip(b'\n')))
        self.assertIn(b'/system/bin/sh /system/bin/pm disable com.storytel.bootstrap', after['init.rc'])
        self.assertIn(b'/system/bin/sh /system/bin/pm disable com.onyx.android.onyxotaservice',
                      after['init.rc'])

    def test_kernel_and_second_untouched_and_size_kept(self):
        img = m.pack_boot(self.stock, self.hdr, self.kernel, m.patch_ramdisk(self.ramdisk, KEY), self.second)
        _, kernel, _, second = m.parse_boot(img)
        self.assertEqual(kernel, self.kernel)
        self.assertEqual(second, self.second)
        self.assertEqual(len(img), len(self.stock))

    def test_patching_twice_is_refused(self):
        patched = m.patch_ramdisk(self.ramdisk, KEY)
        with self.assertRaises(m.BootImageError):
            m.patch_ramdisk(patched, KEY)


if __name__ == '__main__':
    unittest.main()
