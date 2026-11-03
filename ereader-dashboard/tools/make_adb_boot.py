"""Build a minimal ADB-enabled boot image for the Storytel Reader Gen 2 from the stock backup.

Changes ONLY the ramdisk, and inside it only:
  * default.prop  : persist.sys.usb.config=none -> mtp,adb
  * init.rc       : appended block that runs after boot completes (USB/ADB on, Storytel launcher
                    and Onyx OTA updater disabled, dashboard opened); see INIT_RC_APPEND
  * /adb_keys     : new file with this PC's ADB public key (authentication stays ON; only this PC
                    is trusted, no "allow USB debugging" dialog is needed)
Kernel and second stage are copied byte-for-byte. No root, no Magisk.

All input checks raise BootImageError (never `assert`, which `python -O` removes).

Usage: py -3.10 -I tools/make_adb_boot.py <stock boot.img> <adbkey.pub> <out.img>
"""
import gzip
import hashlib
import struct
import sys

# AOSP boot_img_hdr v0 after the 8-byte magic: kernel_size, kernel_addr, ramdisk_size,
# ramdisk_addr, second_size, second_addr, tags_addr, page_size, header_version, os_version.
HDR_FMT = '<10I'
ID_OFFSET = 576          # boot_img_hdr.id (32 bytes)
CPIO_HEADER = 110        # 'newc' header length
MAX_CPIO_ENTRIES = 4096  # the stock ramdisk has 49 entries
MARKER = b'# --- dashboard: keep USB data + ADB enabled'
INIT_RC_APPEND = MARKER + b""" (added by make_adb_boot.py) ---
on property:sys.boot_completed=1
    setprop persist.sys.usb.config mtp,adb
    # pm/settings are shell scripts without #!, so init runs them through sh.
    # UsbDeviceManager drops 'adb' while Settings.Global.adb_enabled=0; turn the setting on.
    exec - system system -- /system/bin/sh /system/bin/settings put global adb_enabled 1
    setprop sys.usb.config mtp,adb
    # Fallback: adbd also on TCP 5555 (same key check via /adb_keys).
    setprop service.adb.tcp.port 5555
    restart adbd
    # Dedicated dashboard: Storytel's launcher (com.storytel.bootstrap) is disabled, not removed,
    # so the dashboard is the only Home app. Undo: pm enable com.storytel.bootstrap (as root).
    exec - root root -- /system/bin/sh /system/bin/pm disable com.storytel.bootstrap
    # v5: Onyx OTA updater disabled (not removed) so a firmware update cannot replace this boot
    # image and silently remove ADB and the dashboard. Undo: pm enable com.onyx.android.onyxotaservice
    exec - root root -- /system/bin/sh /system/bin/pm disable com.onyx.android.onyxotaservice
    start dashboard_home

service dashboard_home /system/bin/am start -a android.intent.action.MAIN -c android.intent.category.HOME
    user system
    group system
    oneshot
    disabled
"""


class BootImageError(Exception):
    """The input is not the boot image / key this tool was verified against."""


def pages(n, ps):
    return (n + ps - 1) // ps


def read_cpio(data):
    """Parse a 'newc' cpio archive into an ordered list of (header_fields, name, body)."""
    entries, i = [], 0
    for _ in range(MAX_CPIO_ENTRIES):
        if i + CPIO_HEADER > len(data):
            raise BootImageError(f'cpio truncated at offset {i}')
        h = data[i:i + CPIO_HEADER]
        if h[:6] != b'070701':
            raise BootImageError(f'bad cpio magic at offset {i}')
        fields = [int(h[6 + 8 * k:14 + 8 * k], 16) for k in range(13)]
        namesize, filesize = fields[11], fields[6]
        name = data[i + CPIO_HEADER:i + CPIO_HEADER + namesize - 1].decode()
        i = (i + CPIO_HEADER + namesize + 3) & ~3
        if i + filesize > len(data):
            raise BootImageError(f'cpio entry {name!r} runs past the end')
        body = data[i:i + filesize]
        i = (i + filesize + 3) & ~3
        entries.append((fields, name, body))
        if name == 'TRAILER!!!':
            return entries
    raise BootImageError(f'more than {MAX_CPIO_ENTRIES} cpio entries')


def write_cpio(entries):
    out = bytearray()
    for fields, name, body in entries:
        fields = list(fields)
        fields[6] = len(body)
        fields[11] = len(name) + 1
        out += b'070701' + b''.join(b'%08X' % f for f in fields)
        out += name.encode() + b'\0'
        out += b'\0' * ((4 - len(out) % 4) % 4)
        out += body
        out += b'\0' * ((4 - len(out) % 4) % 4)
    return bytes(out)


def image_id(kernel, ramdisk, second):
    """AOSP mkbootimg id (what storytel-fixer's Gen 2 images carry). The stock id matches no
    common scheme and the loader boots both, so it is not verified; see docs/STATUS.md."""
    h = hashlib.sha1()
    for blob in (kernel, ramdisk, second):
        h.update(blob)
        h.update(struct.pack('<I', len(blob)))
    return h.digest()


def parse_boot(d):
    """Split an Android v0 boot image into (header_values, kernel, ramdisk, second)."""
    if len(d) < 8 + struct.calcsize(HDR_FMT) or d[:8] != b'ANDROID!':
        raise BootImageError('not an Android boot image')
    hdr = struct.unpack_from(HDR_FMT, d, 8)
    ks, _, rs, _, ss, _, _, ps, hv, _ = hdr
    if hv != 0:
        raise BootImageError(f'unexpected header version {hv}')
    if ps not in (2048, 4096, 8192, 16384):
        raise BootImageError(f'unexpected page size {ps}')
    r_off = ps + pages(ks, ps) * ps
    s_off = r_off + pages(rs, ps) * ps
    if s_off + ss > len(d):
        raise BootImageError('header sizes exceed the image')
    return hdr, d[ps:ps + ks], d[r_off:r_off + rs], d[s_off:s_off + ss]


def patch_ramdisk(ramdisk, adb_key):
    """Return the patched gzip ramdisk. Refuses input that is not the verified stock layout."""
    entries = read_cpio(gzip.decompress(ramdisk))
    names = [n for _, n, _ in entries]
    for required in ('default.prop', 'init.rc', 'TRAILER!!!'):
        if required not in names:
            raise BootImageError(f'ramdisk has no {required}')
    if 'adb_keys' in names:
        raise BootImageError('ramdisk already contains adb_keys (already patched?)')
    new = []
    for fields, name, body in entries:
        if name == 'default.prop':
            if body.count(b'persist.sys.usb.config=none') != 1:
                raise BootImageError('default.prop does not contain persist.sys.usb.config=none once')
            body = body.replace(b'persist.sys.usb.config=none', b'persist.sys.usb.config=mtp,adb')
        elif name == 'init.rc':
            if MARKER in body:
                raise BootImageError('init.rc is already patched')
            body = body.rstrip(b'\n') + b'\n\n' + INIT_RC_APPEND
        if name == 'TRAILER!!!':
            ino = max(f[0] for f, _, _ in entries) + 1
            # ino, mode 0100644, uid, gid, nlink, mtime, size, devmaj/min, rdevmaj/min, namesize, check
            new.append(([ino, 0o100644, 0, 0, 1, 0, len(adb_key), 0, 0, 0, 0, 0, 0], 'adb_keys', adb_key))
        new.append((fields, name, body))
    return gzip.compress(write_cpio(new), compresslevel=9, mtime=0)


def pack_boot(stock, hdr, kernel, new_ramdisk, second):
    """Rebuild the image with the new ramdisk; same size as the stock partition backup."""
    ks, ka, _, ra, ss, sa, ta, ps, hv, osv = hdr
    head = bytearray(stock[:ps])
    struct.pack_into(HDR_FMT, head, 8, ks, ka, len(new_ramdisk), ra, ss, sa, ta, ps, hv, osv)
    head[ID_OFFSET:ID_OFFSET + 32] = image_id(kernel, new_ramdisk, second).ljust(32, b'\0')

    def padded(blob):
        return blob + b'\0' * (pages(len(blob), ps) * ps - len(blob))

    img = bytes(head) + padded(kernel) + padded(new_ramdisk) + padded(second)
    if len(img) > len(stock):
        raise BootImageError('patched image larger than the boot partition')
    return img + b'\0' * (len(stock) - len(img))


def read_adb_key(path):
    with open(path, 'rb') as f:
        key = f.read().strip()
    # adbkey.pub is "<base64 RSA key> <user@host>"; refuse anything else.
    if not key or b'\n' in key or len(key) > 4096:
        raise BootImageError('adb public key file is not a single-line adbkey.pub')
    return key + b'\n'


def main(stock_path, key_path, out_path):
    with open(stock_path, 'rb') as f:
        stock = f.read()
    hdr, kernel, ramdisk, second = parse_boot(stock)
    if stock[ID_OFFSET:ID_OFFSET + 20] != image_id(kernel, ramdisk, second):
        print('note: stock header id is not the AOSP sha1 (expected on this device)')
    new_ramdisk = patch_ramdisk(ramdisk, read_adb_key(key_path))
    img = pack_boot(stock, hdr, kernel, new_ramdisk, second)
    with open(out_path, 'wb') as f:
        f.write(img)
    print(f'ramdisk {len(ramdisk)} -> {len(new_ramdisk)} bytes; image {len(img)} bytes -> {out_path}')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    try:
        main(*sys.argv[1:4])
    except BootImageError as e:
        sys.exit(f'refused: {e}')
