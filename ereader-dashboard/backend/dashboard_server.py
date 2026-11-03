"""Dashboard backend for the Storytel Reader e-ink dashboard.

Runs on a PC / Raspberry Pi on the home network. It:
  * holds the Google OAuth tokens (never sent to the e-reader),
  * fetches Google Calendar + Google Tasks read-only,
  * fetches weather for 's-Hertogenbosch from Open-Meteo,
  * serves one compact JSON document at /dashboard.json for the reader.

Usage:
  python dashboard_server.py auth      # one-time Google sign-in in a browser on this PC
  python dashboard_server.py fetch     # fetch once and print the JSON (for testing)
  python dashboard_server.py serve     # run the HTTP server for the reader
  python dashboard_server.py serve --demo   # serve demo calendar/tasks (no Google needed)
"""
import datetime as dt
import json
import logging
import os
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from zoneinfo import ZoneInfo

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
SECRETS_DIR = os.path.join(HERE, "secrets")          # git-ignored, never published
TOKEN_PATH = os.path.join(SECRETS_DIR, "google_token.json")
CLIENT_PATH = os.path.join(SECRETS_DIR, "google_client_secret.json")
CACHE_PATH = os.path.join(HERE, "cache", "dashboard.json")

# Read-only scopes only. Calendar events can never be modified with these.
SCOPES = [
    "https://www.googleapis.com/auth/calendar.readonly",
    "https://www.googleapis.com/auth/tasks.readonly",
]

DEFAULT_CONFIG = {
    "timezone": "Europe/Amsterdam",
    "location": {"name": "'s-Hertogenbosch", "latitude": 51.6978, "longitude": 5.3037},
    "listen_host": "0.0.0.0",
    "listen_port": 8765,
    "refresh_minutes": 10,
    "days_ahead": 5,
    # Empty list = all calendars selected in Google Calendar's sidebar.
    "calendar_ids": [],
    # Optional short labels per calendar id, e.g. {"xyz@group.calendar.google.com": "TimeTree"}.
    "calendar_labels": {},
}

log = logging.getLogger("dashboard")
MAX_PAGES = 10  # upper bound for Google API pagination loops
HTTP_TIMEOUT_S = 15  # socket timeout per reader connection


# Never write tokens to logs: strip anything that looks like a credential.
# Must be attached to *handlers*: a logger's own filters are not applied to records that
# propagate from child loggers such as "dashboard".
class _RedactFilter(logging.Filter):
    def filter(self, record):
        msg = str(record.getMessage())
        for marker in ("access_token", "refresh_token", "Bearer ", "client_secret"):
            if marker in msg:
                record.msg, record.args = "[redacted log line containing credentials]", ()
                break
        return True


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, encoding="utf-8") as f:
            cfg.update(json.load(f))
    return cfg


def device_key():
    """Shared secret the reader must send; generated on first run, stored in secrets/."""
    os.makedirs(SECRETS_DIR, exist_ok=True)
    path = os.path.join(SECRETS_DIR, "device_key.txt")
    if not os.path.exists(path):
        with open(path, "w", encoding="utf-8") as f:
            f.write(secrets.token_urlsafe(24))
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


# ---------------------------------------------------------------- weather

# WMO weather interpretation codes as documented by Open-Meteo.
WMO_NL = {
    0: "Helder", 1: "Overwegend helder", 2: "Half bewolkt", 3: "Bewolkt",
    45: "Mist", 48: "Rijpmist",
    51: "Lichte motregen", 53: "Motregen", 55: "Dichte motregen",
    56: "Lichte ijzel", 57: "IJzel",
    61: "Lichte regen", 63: "Regen", 65: "Zware regen",
    66: "Lichte ijsregen", 67: "IJsregen",
    71: "Lichte sneeuw", 73: "Sneeuw", 75: "Zware sneeuw", 77: "Korrelsneeuw",
    80: "Lichte buien", 81: "Buien", 82: "Zware buien",
    85: "Sneeuwbuien", 86: "Zware sneeuwbuien",
    95: "Onweer", 96: "Onweer met hagel", 99: "Zwaar onweer met hagel",
}


def fetch_weather(cfg):
    loc = cfg["location"]
    r = requests.get(
        "https://api.open-meteo.com/v1/forecast",
        params={
            "latitude": loc["latitude"],
            "longitude": loc["longitude"],
            "current": "temperature_2m,weather_code",
            "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
            "timezone": cfg["timezone"],
            "forecast_days": 5,
        },
        timeout=15,
    )
    r.raise_for_status()
    d = r.json()
    cur = d["current"]
    days = []
    for i, date in enumerate(d["daily"]["time"]):
        code = d["daily"]["weather_code"][i]
        days.append({
            "date": date,
            "code": code,
            "text": WMO_NL.get(code, "Onbekend"),
            "max": d["daily"]["temperature_2m_max"][i],
            "min": d["daily"]["temperature_2m_min"][i],
            "rain_pct": d["daily"]["precipitation_probability_max"][i],
        })
    return {
        "location": loc["name"],
        "temp": cur["temperature_2m"],
        "code": cur["weather_code"],
        "text": WMO_NL.get(cur["weather_code"], "Onbekend"),
        "observed": cur["time"],
        "days": days,
    }


# ---------------------------------------------------------------- google

def google_credentials():
    from google.auth.transport.requests import Request
    from google.oauth2.credentials import Credentials

    if not os.path.exists(TOKEN_PATH):
        raise RuntimeError("Not signed in to Google. Run: python dashboard_server.py auth")
    creds = Credentials.from_authorized_user_file(TOKEN_PATH, SCOPES)
    if not creds.valid:
        if creds.expired and creds.refresh_token:
            creds.refresh(Request())
            _save_token(creds)
        else:
            raise RuntimeError("Google sign-in expired. Run: python dashboard_server.py auth")
    return creds


def _save_token(creds):
    os.makedirs(SECRETS_DIR, exist_ok=True)
    with open(TOKEN_PATH, "w", encoding="utf-8") as f:
        f.write(creds.to_json())


def google_auth():
    """Installed-app (loopback) OAuth flow. Google's device flow does not allow
    Calendar/Tasks scopes, so sign-in happens once in a browser on this PC."""
    from google_auth_oauthlib.flow import InstalledAppFlow

    if not os.path.exists(CLIENT_PATH):
        sys.exit(f"Missing OAuth client file: {CLIENT_PATH}\nSee docs/SETUP.md, step 'Google Cloud'.")
    flow = InstalledAppFlow.from_client_secrets_file(CLIENT_PATH, SCOPES)
    creds = flow.run_local_server(port=0, prompt="consent", access_type="offline")
    _save_token(creds)
    print("Google sign-in saved to secrets/google_token.json")


def _parse_event_time(t, tz):
    if "dateTime" in t:
        return dt.datetime.fromisoformat(t["dateTime"].replace("Z", "+00:00")).astimezone(tz), False
    return dt.datetime.fromisoformat(t["date"]).replace(tzinfo=tz), True


def fetch_calendar(cfg, creds):
    from googleapiclient.discovery import build

    tz = ZoneInfo(cfg["timezone"])
    svc = build("calendar", "v3", credentials=creds, cache_discovery=False)
    cals = svc.calendarList().list().execute().get("items", [])
    wanted = cfg["calendar_ids"] or [c["id"] for c in cals if c.get("selected")]
    names = {c["id"]: c.get("summaryOverride") or c.get("summary", "") for c in cals}

    start = dt.datetime.now(tz).replace(hour=0, minute=0, second=0, microsecond=0)
    end = start + dt.timedelta(days=cfg["days_ahead"] + 1)
    events, seen = [], set()
    for cal_id in wanted:
        page = None
        for _ in range(MAX_PAGES):
            resp = svc.events().list(
                calendarId=cal_id, timeMin=start.isoformat(), timeMax=end.isoformat(),
                singleEvents=True, orderBy="startTime", showDeleted=False,
                maxResults=250, pageToken=page,
            ).execute()
            for e in resp.get("items", []):
                if e.get("status") == "cancelled":
                    continue
                s, all_day = _parse_event_time(e["start"], tz)
                en, _ = _parse_event_time(e["end"], tz)
                # The same event can appear in several calendars (shared / subscribed).
                key = (e.get("iCalUID", e["id"]), s.isoformat())
                if key in seen:
                    continue
                seen.add(key)
                events.append({
                    "title": e.get("summary", "(geen titel)"),
                    "start": s.isoformat(),
                    "end": en.isoformat(),
                    "all_day": all_day,
                    "location": e.get("location", ""),
                    "calendar": cfg["calendar_labels"].get(cal_id, names.get(cal_id, "")),
                })
            page = resp.get("nextPageToken")
            if not page:
                break
    # Sort on the instant, not the text: offsets differ around daylight saving changes.
    events.sort(key=lambda e: (dt.datetime.fromisoformat(e["start"]), not e["all_day"]))
    return events


def fetch_tasks(cfg, creds):
    from googleapiclient.discovery import build

    svc = build("tasks", "v1", credentials=creds, cache_discovery=False)
    out = []
    for tl in svc.tasklists().list(maxResults=100).execute().get("items", []):
        page = None
        for _ in range(MAX_PAGES):
            resp = svc.tasks().list(tasklist=tl["id"], showCompleted=False, showHidden=False,
                                    maxResults=100, pageToken=page).execute()
            for t in resp.get("items", []):
                if not t.get("title", "").strip():
                    continue
                # Google Tasks stores only the date part of 'due' (time is always 00:00 UTC).
                out.append({"title": t["title"], "due": t.get("due", "")[:10], "list": tl["title"]})
            page = resp.get("nextPageToken")
            if not page:
                break
    out.sort(key=lambda t: (t["due"] == "", t["due"], t["title"].lower()))
    return out


# ---------------------------------------------------------------- demo data

def demo_calendar_and_tasks(cfg):
    tz = ZoneInfo(cfg["timezone"])
    today = dt.datetime.now(tz).replace(hour=0, minute=0, second=0, microsecond=0)

    def ev(day, h, m, dur, title, cal="Persoonlijk", loc=""):
        s = today + dt.timedelta(days=day, hours=h, minutes=m)
        return {"title": title, "start": s.isoformat(), "end": (s + dt.timedelta(minutes=dur)).isoformat(),
                "all_day": False, "location": loc, "calendar": cal}

    events = [
        {"title": "Verjaardag oma", "start": today.isoformat(),
         "end": (today + dt.timedelta(days=1)).isoformat(),
         "all_day": True, "location": "", "calendar": "TimeTree"},
        ev(0, 9, 0, 60, "Stand-up team", "Werk"),
        ev(0, 12, 30, 45, "Lunch met Sanne", loc="Markt 12"),
        ev(0, 16, 0, 90, "Tandarts", loc="Vughterstraat"),
        ev(1, 10, 0, 120, "Klantpresentatie", "Werk"),
        ev(2, 19, 30, 120, "Etentje familie", "TimeTree"),
        ev(3, 8, 30, 30, "Auto naar garage"),
    ]

    def d(n):
        return (today + dt.timedelta(days=n)).date().isoformat()

    tasks = [
        {"title": "Belastingaangifte afronden", "due": d(-1), "list": "Mijn taken"},
        {"title": "Cadeau kopen", "due": d(0), "list": "Mijn taken"},
        {"title": "Paspoort verlengen", "due": d(4), "list": "Mijn taken"},
        {"title": "Fietsband plakken", "due": "", "list": "Mijn taken"},
    ]
    return events, tasks


# ---------------------------------------------------------------- assembly / cache

def build_dashboard(cfg, demo=False):
    """Fetch every source independently; a failure in one keeps the last good copy."""
    previous = {}
    if os.path.exists(CACHE_PATH):
        try:
            with open(CACHE_PATH, encoding="utf-8") as f:
                previous = json.load(f)
        except (OSError, ValueError) as e:  # corrupt cache must not stop the server
            log.warning("ignoring unreadable cache: %s", type(e).__name__)
    now = dt.datetime.now(ZoneInfo(cfg["timezone"])).isoformat(timespec="seconds")
    out = {"generated": now, "errors": [], "updated": dict(previous.get("updated", {}))}

    def section(name, fn):
        try:
            out[name] = fn()
            out["updated"][name] = now
        except Exception as e:  # keep the dashboard alive with cached data
            log.warning("%s failed: %s", name, type(e).__name__)
            out["errors"].append(f"{name}: {type(e).__name__}")
            out[name] = previous.get(name)

    section("weather", lambda: fetch_weather(cfg))
    if demo:
        events, tasks = demo_calendar_and_tasks(cfg)
        section("events", lambda: events)
        section("tasks", lambda: tasks)
    else:
        creds_box = {}

        def creds():
            if "c" not in creds_box:
                creds_box["c"] = google_credentials()
            return creds_box["c"]

        section("events", lambda: fetch_calendar(cfg, creds()))
        section("tasks", lambda: fetch_tasks(cfg, creds()))

    os.makedirs(os.path.dirname(CACHE_PATH), exist_ok=True)
    tmp = CACHE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    os.replace(tmp, CACHE_PATH)
    return out


def lan_ip():
    """Best guess of this laptop's address on the home network (no traffic is sent)."""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 80))  # TEST-NET address; connect() on UDP only picks a route
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def serve(cfg, demo):
    key = device_key()
    state = {"body": json.dumps(build_dashboard(cfg, demo), ensure_ascii=False).encode()}

    def refresher():
        while True:
            time.sleep(cfg["refresh_minutes"] * 60)
            state["body"] = json.dumps(build_dashboard(cfg, demo), ensure_ascii=False).encode()

    threading.Thread(target=refresher, daemon=True).start()
    reader = {"ip": cfg.get("reader_ip", "")}

    def keepalive():
        # The reader's ARP requests for this laptop sometimes go unanswered (wifi power saving),
        # which shows up on the reader as "No route to host". A ping from this side refreshes
        # the reader's neighbour entry, so its next sync goes through.
        import subprocess
        while True:
            if reader["ip"]:
                subprocess.run(["ping", "-n", "1", "-w", "1000", reader["ip"]],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(30)

    threading.Thread(target=keepalive, daemon=True).start()

    class Handler(BaseHTTPRequestHandler):
        timeout = HTTP_TIMEOUT_S  # a stalled client cannot hold a thread forever

        def do_GET(self):
            u = urlparse(self.path)
            if u.path != "/dashboard.json":
                self.send_error(404)
                return
            if not secrets.compare_digest(parse_qs(u.query).get("key", [""])[0], key):
                self.send_error(403)
                return
            # Only an authenticated reader may become the keepalive ping target.
            if self.client_address[0] != "127.0.0.1":
                reader["ip"] = self.client_address[0]
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(state["body"])))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(state["body"])

        def log_message(self, fmt, *args):  # do not log query strings (they contain the key)
            log.info("%s %s", self.address_string(), self.command)

    srv = ThreadingHTTPServer((cfg["listen_host"], cfg["listen_port"]), Handler)
    print(f"Reader URL: http://{lan_ip()}:{cfg['listen_port']}/dashboard.json", flush=True)
    print("Device key: see secrets/device_key.txt", flush=True)
    srv.serve_forever()


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    for handler in logging.getLogger().handlers:
        handler.addFilter(_RedactFilter())
    cfg = load_config()
    cmd = sys.argv[1] if len(sys.argv) > 1 else "serve"
    demo = "--demo" in sys.argv
    if cmd == "auth":
        google_auth()
    elif cmd == "fetch":
        print(json.dumps(build_dashboard(cfg, demo), ensure_ascii=False, indent=2))
    elif cmd == "serve":
        serve(cfg, demo)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
