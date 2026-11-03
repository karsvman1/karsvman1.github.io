#!/usr/bin/env bash
# WRITES the boot partition of the Storytel Reader Gen 2 (maskrom, via USB cable).
# Only run after explicit approval. Restore: bash tools/flash_boot.sh backup/20261103-113527/boot.img
set -euo pipefail
cd "$(dirname "$0")/.."
RK=tools/rkflashtool/rkflashtool.exe
IMG=${1:-build/boot-adb.img}
STOCK=backup/20261103-113527/boot.img
LOG=backup/flash-$(date +%Y%m%d-%H%M%S).log

[ -f "$STOCK" ] || { echo "no stock backup, refusing"; exit 1; }
[ -f "$IMG" ] || { echo "image $IMG not found, refusing"; exit 1; }
[ "$(stat -c %s "$IMG")" = "$(stat -c %s "$STOCK")" ] || { echo "image size differs from partition backup, refusing"; exit 1; }
[ "$(head -c 8 "$IMG")" = "ANDROID!" ] || { echo "image is not an Android boot image, refusing"; exit 1; }
want=$(sha256sum < "$IMG")

# Same partition layout as when the backup was made, or we stop.
"$RK" p > "$LOG.parts" 2>/dev/null
grep -q '0x00010000@0x00016000(boot)' "$LOG.parts" || { echo "unexpected partition table, refusing"; cat "$LOG.parts"; exit 1; }

echo "writing $IMG to boot" | tee "$LOG"
"$RK" w boot < "$IMG" 2>> "$LOG"
echo "reading back" | tee -a "$LOG"
"$RK" r boot > "$LOG.readback" 2>> "$LOG"
got=$(sha256sum < "$LOG.readback")
if [ "$want" = "$got" ]; then
    echo "OK: read-back identical to written image" | tee -a "$LOG"
    rm "$LOG.readback"
    "$RK" b 2>> "$LOG" && echo "reboot command sent" | tee -a "$LOG"
else
    echo "READ-BACK MISMATCH - device NOT rebooted; restore with: bash tools/flash_boot.sh $STOCK" | tee -a "$LOG"
    exit 1
fi
