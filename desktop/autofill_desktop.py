"""Keystead desktop autofill.

1. Auto-type: a global Ctrl+Alt+A hotkey. Keystead remembers which app had focus, shows a
   quick account picker, then types username, Tab, password into that app.
2. Browser bridge: a small HTTP API on 127.0.0.1 only, used by the Keystead browser extension.
   The extension pairs once with a 6-digit code shown in Keystead and gets a random token; every
   request must carry that token. The vault stays inside the app: this module asks the app's page
   (via `js`) for the few fields it needs and never stores passwords itself.
"""
import hashlib, json, os, secrets, sys, threading, time, ctypes
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

EXT_PORTS = range(47900, 47906)


# ------------------------------------------------------------------ Windows helpers
def _u32():
    return ctypes.windll.user32


def foreground_window():
    if sys.platform != "win32":
        return None, ""
    u = _u32()
    u.GetForegroundWindow.restype = ctypes.c_void_p
    h = u.GetForegroundWindow()
    buf = ctypes.create_unicode_buffer(512)
    u.GetWindowTextW(ctypes.c_void_p(h), buf, 512)
    return h, buf.value


def own_windows():
    if sys.platform != "win32":
        return []
    u, pid, out = _u32(), os.getpid(), []
    proc = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)

    def cb(h, _):
        p = ctypes.c_ulong()
        u.GetWindowThreadProcessId(ctypes.c_void_p(h), ctypes.byref(p))
        if p.value == pid and u.IsWindowVisible(ctypes.c_void_p(h)):
            out.append(h)
        return True

    u.EnumWindows(proc(cb), 0)
    return out


def bring_to_front(h):
    if sys.platform != "win32" or not h:
        return False
    u = _u32()
    h = ctypes.c_void_p(h)
    if u.IsIconic(h):
        u.ShowWindow(h, 9)  # SW_RESTORE
    # Windows only lets a process take focus in some situations; briefly attaching to the current
    # foreground thread's input is the standard, documented-behaviour workaround.
    u.GetForegroundWindow.restype = ctypes.c_void_p
    fg = u.GetForegroundWindow()
    cur = ctypes.windll.kernel32.GetCurrentThreadId()
    fg_thread = u.GetWindowThreadProcessId(ctypes.c_void_p(fg), None) if fg else 0
    attached = bool(fg_thread and fg_thread != cur and u.AttachThreadInput(cur, fg_thread, True))
    try:
        u.BringWindowToTop(h)
        ok = bool(u.SetForegroundWindow(h))
    finally:
        if attached:
            u.AttachThreadInput(cur, fg_thread, False)
    return ok


# ------------------------------------------------------------------ typing
class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", ctypes.c_ushort), ("wScan", ctypes.c_ushort), ("dwFlags", ctypes.c_ulong),
                ("time", ctypes.c_ulong), ("dwExtraInfo", ctypes.c_size_t)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("pad", ctypes.c_byte * 32)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", ctypes.c_ulong), ("u", _INPUTUNION)]


KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x0002, 0x0004
VK_TAB, VK_RETURN, VK_SHIFT, VK_CONTROL, VK_MENU = 0x09, 0x0D, 0x10, 0x11, 0x12


def _send(inputs):
    arr = (INPUT * len(inputs))(*inputs)
    _u32().SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


def _vk(vk, up=False):
    return INPUT(1, _INPUTUNION(ki=KEYBDINPUT(vk, 0, KEYEVENTF_KEYUP if up else 0, 0, 0)))


def _uni(ch, up=False):
    return INPUT(1, _INPUTUNION(ki=KEYBDINPUT(0, ord(ch), KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if up else 0), 0, 0)))


def type_text(text, delay=0.012):
    for ch in text:
        if ord(ch) > 0xFFFF:  # characters outside the basic plane need a surrogate pair
            data = ch.encode("utf-16-le")
            units = [chr(int.from_bytes(data[i:i + 2], "little")) for i in (0, 2)]
        else:
            units = [ch]
        _send([_uni(u) for u in units] + [_uni(u, True) for u in units])
        time.sleep(delay)


def press(vk):
    _send([_vk(vk), _vk(vk, True)])
    time.sleep(0.03)


def release_modifiers():
    _send([_vk(VK_CONTROL, True), _vk(VK_MENU, True), _vk(VK_SHIFT, True)])


# ------------------------------------------------------------------ auto-type
class AutoType:
    """Ctrl+Alt+A anywhere: remember the target window, ask the app to show its picker."""
    MOD_ALT, MOD_CONTROL, MOD_NOREPEAT, WM_HOTKEY = 0x1, 0x2, 0x4000, 0x0312

    def __init__(self, js):
        self.js = js
        self.target = None
        self.title = ""
        self.ok = False

    def start(self):
        if sys.platform != "win32":
            return False
        ready = threading.Event()
        threading.Thread(target=self._loop, args=(ready,), daemon=True).start()
        ready.wait(2)
        return self.ok

    def _loop(self, ready):
        from ctypes import wintypes
        u = _u32()
        self.ok = bool(u.RegisterHotKey(None, 1, self.MOD_CONTROL | self.MOD_ALT | self.MOD_NOREPEAT, ord("A")))
        ready.set()
        if not self.ok:
            return
        msg = wintypes.MSG()
        while u.GetMessageW(ctypes.byref(msg), None, 0, 0) != 0:
            if msg.message == self.WM_HOTKEY:
                self._fire()

    def _fire(self):
        h, title = foreground_window()
        if h in own_windows():  # pressed inside Keystead itself: nothing to type into
            return
        self.target, self.title = h, title
        for w in own_windows():
            bring_to_front(w)
            break
        try:
            self.js("window.autoTypeStart && autoTypeStart(%s)" % json.dumps(title))
        except Exception:
            pass

    def run(self, user, password, mode="both", enter=False):
        """Called by the app after the user picks an account."""
        if sys.platform != "win32" or not self.target:
            return {"ok": False, "error": "No target app. Press Ctrl+Alt+A while the login box is focused."}
        if not bring_to_front(self.target):
            time.sleep(0.15)
            bring_to_front(self.target)
        time.sleep(0.35)  # let the target window finish taking focus
        release_modifiers()
        if mode in ("both", "user") and user:
            type_text(user)
        if mode == "both":
            press(VK_TAB)
            time.sleep(0.05)
        if mode in ("both", "pass") and password:
            type_text(password)
        if enter:
            press(VK_RETURN)
        self.target = None
        return {"ok": True}


# ------------------------------------------------------------------ browser bridge
class BrowserBridge:
    def __init__(self, app_dir, js):
        self.js = js
        self.path = os.path.join(app_dir, "extensions.json")
        self.port = None
        self.pair_code = None
        self.pair_until = 0
        self.pair_tries = 0
        self.lock = threading.Lock()

    # tokens are stored hashed, so this file is useless to anyone who copies it
    def _tokens(self):
        try:
            with open(self.path, encoding="utf-8") as f:
                return json.load(f).get("tokens", [])
        except Exception:
            return []

    def _save_tokens(self, toks):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with open(self.path, "w", encoding="utf-8") as f:
            json.dump({"tokens": toks[-10:]}, f)

    def paired_count(self):
        return len(self._tokens())

    def unpair_all(self):
        self._save_tokens([])
        return True

    def _valid(self, token):
        if not token:
            return False
        h = hashlib.sha256(token.encode()).hexdigest()
        return any(secrets.compare_digest(h, t.get("h", "")) for t in self._tokens())

    def start(self):
        bridge = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, code, obj):
                body = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)

            def do_OPTIONS(self):
                self.send_response(403)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def do_POST(self):
                # Only the extension is allowed: plain web pages can't add the extension origin.
                origin = self.headers.get("Origin", "")
                if origin and not (origin.startswith("chrome-extension://") or origin.startswith("moz-extension://")):
                    return self._send(403, {"error": "forbidden"})
                n = int(self.headers.get("Content-Length") or 0)
                if n > 8192:
                    return self._send(413, {"error": "too large"})
                try:
                    m = json.loads(self.rfile.read(n) or b"{}")
                except Exception:
                    return self._send(400, {"error": "bad json"})
                try:
                    self._send(200, bridge.handle(self.path, m, self.headers.get("X-Keystead-Token", "")))
                except Exception as e:
                    self._send(500, {"error": str(e)})

        for port in EXT_PORTS:
            try:
                httpd = ThreadingHTTPServer(("127.0.0.1", port), H)  # loopback only, never the network
                break
            except OSError:
                continue
        else:
            return False
        httpd.daemon_threads = True
        self.port = port
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        return True

    def _call(self, expr):
        r = self.js(expr)
        return r if r is not None else {"locked": True}

    def handle(self, path, m, token):
        if path == "/ext/hello":
            return {"app": "keystead", "paired": self._valid(token)}
        if path == "/ext/pair/start":
            with self.lock:
                self.pair_code = "%06d" % secrets.randbelow(1000000)
                self.pair_until = time.time() + 120
                self.pair_tries = 0
            self.js("window.extPairShow && extPairShow(%s, %s)" % (json.dumps(self.pair_code), json.dumps(str(m.get("browser", ""))[:40])))
            return {"ok": True}
        if path == "/ext/pair/finish":
            with self.lock:
                if not self.pair_code or time.time() > self.pair_until:
                    return {"ok": False, "error": "The code expired. Start pairing again."}
                self.pair_tries += 1
                if self.pair_tries > 5:
                    self.pair_code = None
                    return {"ok": False, "error": "Too many wrong codes. Start pairing again."}
                if not secrets.compare_digest(str(m.get("code", "")).strip(), self.pair_code):
                    return {"ok": False, "error": "Wrong code."}
                self.pair_code = None
            tok = secrets.token_urlsafe(32)
            toks = self._tokens()
            toks.append({"h": hashlib.sha256(tok.encode()).hexdigest(), "name": str(m.get("browser", ""))[:40], "t": int(time.time())})
            self._save_tokens(toks)
            self.js("window.extPairDone && extPairDone(%s)" % json.dumps(str(m.get("browser", ""))[:40]))
            return {"ok": True, "token": tok}
        if not self._valid(token):
            return {"error": "not paired", "paired": False}
        host = str(m.get("host", ""))[:253].lower()
        if path == "/ext/logins":
            return self._call("window.extLogins && extLogins(%s)" % json.dumps(host))
        if path == "/ext/search":
            return self._call("window.extSearch && extSearch(%s)" % json.dumps(str(m.get("q", ""))[:80]))
        if path == "/ext/fill":
            return self._call("window.extGet && extGet(%s, %s, %s)" % (
                json.dumps(str(m.get("id", ""))[:64]), json.dumps(host), "true" if m.get("manual") else "false"))
        if path == "/ext/focus":
            for w in own_windows():
                bring_to_front(w)
                break
            return {"ok": True}
        return {"error": "unknown"}
