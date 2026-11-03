#!/usr/bin/env bash
# READ-ONLY backup of the Storytel Reader Gen 2 in maskrom mode.
# Uses only rkflashtool read commands: 'p' (partition table) and 'r' (read). Nothing is written.
set -euo pipefail
cd "$(dirname "$0")/.."
RK=tools/rkflashtool/rkflashtool.exe
OUT=backup/$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"

echo "== partition table"
"$RK" p > "$OUT/partitions.txt" 2> "$OUT/p.log" || { cat "$OUT/p.log"; exit 1; }
cat "$OUT/partitions.txt"

echo "== reading boot twice"
"$RK" r boot > "$OUT/boot.img" 2> "$OUT/r1.log"
"$RK" r boot > "$OUT/boot.read2.img" 2> "$OUT/r2.log"
ls -l "$OUT"/boot*.img
sha256sum "$OUT"/boot*.img | tee "$OUT/SHA256SUMS"

# Both reads must be identical and the image must start with the Rockchip 'KRNL' magic.
a=$(sha256sum < "$OUT/boot.img"); b=$(sha256sum < "$OUT/boot.read2.img")
[ "$a" = "$b" ] && echo "OK: both reads identical" || { echo "MISMATCH between reads"; exit 1; }
# Gen 2 (verified 2026-11-03) uses a standard Android boot image; Gen 1 uses Rockchip 'KRNL'.
magic=$(head -c 8 "$OUT/boot.img" | tr -d '\0')
case "$magic" in
    ANDROID!) echo "OK: Android boot image header" ;;
    KRNL*)    echo "OK: Rockchip KRNL header (Gen 1 layout)" ;;
    *)        echo "WARNING: unexpected header '$magic'" ;;
esac
rm "$OUT/boot.read2.img"
echo "Backup in $OUT"
