"""Tests for backend/dashboard_server.py (no network). Run: py -3.10 -m unittest discover -s tests"""
import datetime as dt
import io
import logging
import os
import sys
import unittest
from zoneinfo import ZoneInfo

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'backend'))
import dashboard_server as ds  # noqa: E402

AMS = ZoneInfo('Europe/Amsterdam')


class RedactionTest(unittest.TestCase):
    def test_child_logger_secrets_are_redacted_by_handler_filter(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.addFilter(ds._RedactFilter())
        root = logging.getLogger()
        root.addHandler(handler)
        try:
            ds.log.warning('refresh_token=abc123')
        finally:
            root.removeHandler(handler)
        self.assertNotIn('abc123', stream.getvalue())
        self.assertIn('redacted', stream.getvalue())


class EventTimeTest(unittest.TestCase):
    def test_timed_event_in_utc_converted_to_local(self):
        t, all_day = ds._parse_event_time({'dateTime': '2026-11-03T07:00:00Z'}, AMS)
        self.assertFalse(all_day)
        self.assertEqual(t.isoformat(), '2026-11-03T08:00:00+01:00')

    def test_all_day_event(self):
        t, all_day = ds._parse_event_time({'date': '2026-10-25'}, AMS)
        self.assertTrue(all_day)
        self.assertEqual(t.date(), dt.date(2026, 10, 25))


class _Exec:
    def __init__(self, value):
        self.value = value

    def execute(self):
        return self.value


class _EndlessTasks:
    """Fake Google Tasks service whose task pages never end (always a nextPageToken)."""

    def __init__(self):
        self.pages = 0

    def tasklists(self):
        return self

    def tasks(self):
        return self

    def list(self, **kw):
        if 'tasklist' not in kw:
            return _Exec({'items': [{'id': 'L1', 'title': 'Lijst'}]})
        self.pages += 1
        return _Exec({'items': [{'title': f'taak {self.pages}'}], 'nextPageToken': 'more'})


class BoundedPaginationTest(unittest.TestCase):
    def test_endless_pages_stop_at_max_pages(self):
        fake = _EndlessTasks()
        original = sys.modules.get('googleapiclient.discovery')

        class FakeDiscovery:
            @staticmethod
            def build(*a, **k):
                return fake

        sys.modules['googleapiclient.discovery'] = FakeDiscovery
        try:
            tasks = ds.fetch_tasks(ds.load_config(), creds=None)
        finally:
            if original is not None:
                sys.modules['googleapiclient.discovery'] = original
        self.assertEqual(fake.pages, ds.MAX_PAGES)
        self.assertEqual(len(tasks), ds.MAX_PAGES)


if __name__ == '__main__':
    unittest.main()
