#!/usr/bin/env bash
# Renews the read-only Google login and sends it to the reader over the USB cable.
# Steps: browser sign-in on this laptop -> backend/secrets/google_token.json -> adb broadcast to
# the app's ConfigReceiver (only the ADB shell may send it) -> app private storage.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
A=tools/platform-tools/adb.exe

"$A" get-state >/dev/null 2>&1 || { echo "Reader not connected over USB"; exit 1; }
(cd backend && py -3.10 -W ignore dashboard_server.py auth)

# Values are passed as adb arguments, never printed. Only URL-safe characters are accepted,
# so the eval below cannot run anything else.
eval "$(py -3.10 -I -c "
import json, re
t = json.load(open('backend/secrets/google_token.json'))
for k in ('client_id', 'client_secret', 'refresh_token'):
    v = t[k]
    if not re.fullmatch(r'[A-Za-z0-9._/\-]+', v):
        raise SystemExit(f'unexpected characters in {k}')
    print(f'{k.upper()}={v}')
")"
result=$("$A" shell am broadcast -a nl.japandi.dashboard.CONFIGURE -n nl.japandi.dashboard/.ConfigReceiver \
    --es client_id "$CLIENT_ID" --es client_secret "$CLIENT_SECRET" --es refresh_token "$REFRESH_TOKEN")
unset CLIENT_ID CLIENT_SECRET REFRESH_TOKEN
case "$result" in
    *"saved 3 setting"*) echo "Google login sent to the reader." ;;
    *) echo "Reader did not confirm: $result"; exit 1 ;;
esac
