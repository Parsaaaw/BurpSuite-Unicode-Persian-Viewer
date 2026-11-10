#!/usr/bin/env python3
"""
Demo traffic generator for the "Unicode and Persian Viewer" Burp extension.

Only the Python standard library is used (Python 3.8+).

  python demo_server.py serve                 # start the demo target on :8000
  python demo_server.py client                # send sample requests through Burp (127.0.0.1:8080)
  python demo_server.py client --proxy http://127.0.0.1:8080 --target http://127.0.0.1:8000

The server deliberately returns JSON with ensure_ascii=True, so Persian text
shows up as \\uXXXX escapes in the raw Burp view. That is exactly what the
extension's preview tab is meant to decode.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USERS = [
    {"id": 1, "name": "علی رضایی", "city": "تهران", "phone": "۰۹۱۲۱۲۳۴۵۶۷", "bio": "برنامه‌نویس و علاقه‌مند به امنیت"},
    {"id": 2, "name": "مریم احمدی", "city": "اصفهان", "phone": "۰۹۳۵۷۶۵۴۳۲۱", "bio": "طراح رابط کاربری"},
    {"id": 3, "name": "Sara Karimi", "city": "شیراز", "phone": "09121112233", "bio": "Mixed متن فارسی and English"},
]

ORDERS = {
    "orderId": "ORD-۱۴۰۵-۰۰۱",
    "customer": {"name": "حسین موسوی", "address": {"city": "مشهد", "street": "خیابان امام رضا، پلاک ۱۲"}},
    "items": [
        {"title": "کتاب «شاهنامه»", "qty": 2, "price": 450000},
        {"title": "چای ایرانی (۵۰۰ گرم)", "qty": 1, "price": 180000},
    ],
    "note": "لطفاً قبل از ارسال تماس بگیرید.\nبا تشکر",
    "empty": {"a": {}, "b": []},
    "ok": True,
    "discount": None,
}


def jdump(obj):
    # ensure_ascii=True -> Persian becomes \uXXXX, minified on purpose
    return json.dumps(obj, ensure_ascii=True, separators=(",", ":")).encode("ascii")


class Handler(BaseHTTPRequestHandler):
    server_version = "PersianDemo/1.0"

    def _send(self, status, body, ctype="application/json; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/":
            self._send(200, INDEX.encode("utf-8"), "text/html; charset=utf-8")
        elif path == "/api/users":
            self._send(200, jdump(USERS))
        elif path == "/api/orders":
            self._send(200, jdump(ORDERS))
        elif path == "/api/error":
            self._send(400, jdump({"error": "VALIDATION_FAILED", "message": "شماره موبایل نامعتبر است", "fields": ["phone"]}))
        elif path == "/api/big":
            big = [{"id": i, "text": "این یک پیام آزمایشی است شماره %d" % i} for i in range(3000)]
            self._send(200, jdump(big))
        else:
            self._send(404, jdump({"message": "یافت نشد"}))

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        try:
            data = json.loads(raw.decode("utf-8")) if raw else {}
        except ValueError:
            data = {"raw": raw.decode("utf-8", "replace")}
        self._send(200, jdump({"status": "ok", "message": "درخواست شما دریافت شد", "echo": data}))

    def log_message(self, fmt, *args):
        sys.stderr.write("[demo] %s\n" % (fmt % args))


INDEX = """<!doctype html><meta charset="utf-8"><title>Persian Viewer demo</title>
<body dir="rtl" style="font-family:Tahoma;padding:2em"><h2>هدف آزمایشی</h2><ul>
<li><a href="/api/users">/api/users</a></li><li><a href="/api/orders">/api/orders</a></li>
<li><a href="/api/error">/api/error</a></li><li><a href="/api/big">/api/big</a></li></ul></body>"""


def serve(host, port):
    srv = ThreadingHTTPServer((host, port), Handler)
    print("Demo target on http://%s:%d  (Ctrl+C to stop)" % (host, port))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


def client(proxy, target):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": proxy}))
    # Persian request body, escaped as \uXXXX so the request-side preview has something to decode
    body = json.dumps({"name": "علی رضایی", "message": "سلام دنیا", "phone": "۰۹۱۲۱۲۳۴۵۶۷"}, ensure_ascii=True).encode()
    calls = [
        ("GET", "/api/users", None),
        ("GET", "/api/orders", None),
        ("GET", "/api/error", None),
        ("GET", "/api/big", None),
        ("POST", "/api/echo", body),
    ]
    for method, path, data in calls:
        req = urllib.request.Request(target + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"} if data else {})
        try:
            with opener.open(req, timeout=10) as r:
                print(method, path, r.status)
        except urllib.error.HTTPError as e:
            print(method, path, e.code)
        except Exception as e:
            print(method, path, "FAILED:", e)
            print("Is Burp listening on %s and is the server running?" % proxy)
            return


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8000)
    c = sub.add_parser("client")
    c.add_argument("--proxy", default="http://127.0.0.1:8080")
    c.add_argument("--target", default="http://127.0.0.1:8000")
    a = ap.parse_args()
    if a.cmd == "serve":
        serve(a.host, a.port)
    else:
        client(a.proxy, a.target)


if __name__ == "__main__":
    main()
