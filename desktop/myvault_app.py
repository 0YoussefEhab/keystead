"""MyVault desktop launcher: opens MyVault.html in its own native window (Edge WebView2).
The vault is stored encrypted at %APPDATA%\\MyVault\\myvault.vault
"""
import base64, ctypes, glob, os, subprocess, sys, time
import webview
from sync_server import SyncServer, local_ips

APP_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "MyVault")
VAULT = os.path.join(APP_DIR, "myvault.vault")
SYNC = SyncServer(APP_DIR)
BASE = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))


def _dpapi(data, protect):
    """Windows DPAPI: encrypt/decrypt tied to the current Windows user account."""
    from ctypes import wintypes

    class BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    buf = ctypes.create_string_buffer(bytes(data), len(data))
    src = BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    out = BLOB()
    fn = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    if not fn(ctypes.byref(src), None, None, None, None, 0x1, ctypes.byref(out)):  # CRYPTPROTECT_UI_FORBIDDEN
        raise ctypes.WinError()
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


def protect_local(text):
    """Wrap the on-disk vault so a copied file is useless on another PC/account. Backups are NOT wrapped."""
    if sys.platform != "win32":
        return text
    try:
        return "DPAPI1:" + base64.b64encode(_dpapi(text.encode("utf-8"), True)).decode()
    except Exception:
        return text  # fall back to password-only encryption if DPAPI is unavailable


def unprotect_local(stored):
    if not stored or not stored.startswith("DPAPI1:"):
        return stored  # legacy / unwrapped: already password-encrypted
    if sys.platform != "win32":
        return stored
    try:
        return _dpapi(base64.b64decode(stored[7:]), False).decode("utf-8")
    except Exception:
        return stored  # wrong PC/account: hand the marker to the app, which shows a clear message


def write_atomic(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def set_clipboard(text, secret):
    """Windows clipboard (shows up in Win+V history)."""
    if sys.platform != "win32":
        return
    k32, u32 = ctypes.windll.kernel32, ctypes.windll.user32
    k32.GlobalAlloc.restype = ctypes.c_void_p
    k32.GlobalLock.argtypes = [ctypes.c_void_p]; k32.GlobalLock.restype = ctypes.c_void_p
    k32.GlobalUnlock.argtypes = [ctypes.c_void_p]
    u32.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]; u32.SetClipboardData.restype = ctypes.c_void_p

    def put(fmt, data):
        h = k32.GlobalAlloc(0x0002, len(data))
        p = k32.GlobalLock(h); ctypes.memmove(p, data, len(data)); k32.GlobalUnlock(h)
        u32.SetClipboardData(fmt, h)

    for _ in range(10):
        if u32.OpenClipboard(None):
            break
        import time; time.sleep(0.05)
    else:
        raise RuntimeError("clipboard busy")
    try:
        u32.EmptyClipboard()
        if text:
            put(13, text.encode("utf-16-le") + b"\0\0")  # CF_UNICODETEXT
    finally:
        u32.CloseClipboard()


def hide_from_capture(on):
    """Exclude MyVault's windows from screen capture (Discord, OBS, screenshots). You still see them normally."""
    if sys.platform != "win32":
        return False
    u32 = ctypes.windll.user32
    pid = os.getpid()
    WDA_NONE, WDA_MONITOR, WDA_EXCLUDEFROMCAPTURE = 0x0, 0x1, 0x11
    hwnds = []
    proc = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)

    def cb(hwnd, _):
        p = ctypes.c_ulong()
        u32.GetWindowThreadProcessId(ctypes.c_void_p(hwnd), ctypes.byref(p))
        if p.value == pid:
            hwnds.append(hwnd)
        return True

    u32.EnumWindows(proc(cb), 0)
    ok = False
    for h in hwnds:
        h = ctypes.c_void_p(h)
        if not on:
            ok = bool(u32.SetWindowDisplayAffinity(h, WDA_NONE)) or ok
        # Windows 10 (2004+) / 11: window is simply invisible to capture. Older: shows as black.
        elif u32.SetWindowDisplayAffinity(h, WDA_EXCLUDEFROMCAPTURE) or u32.SetWindowDisplayAffinity(h, WDA_MONITOR):
            ok = True
    return ok


BROWSERS = {
    "chrome":  ("Google Chrome", "chrome://password-manager/settings",
                [r"%ProgramFiles%\Google\Chrome\Application\chrome.exe",
                 r"%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe",
                 r"%LocalAppData%\Google\Chrome\Application\chrome.exe"]),
    "edge":    ("Microsoft Edge", "edge://settings/passwords",
                [r"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe",
                 r"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"]),
    "operagx": ("Opera GX", "opera://settings/passwords",
                [r"%LocalAppData%\Programs\Opera GX\opera.exe",
                 r"%ProgramFiles%\Opera GX\opera.exe"]),
    "brave":   ("Brave", "brave://password-manager/settings",
                [r"%ProgramFiles%\BraveSoftware\Brave-Browser\Application\brave.exe",
                 r"%ProgramFiles(x86)%\BraveSoftware\Brave-Browser\Application\brave.exe",
                 r"%LocalAppData%\BraveSoftware\Brave-Browser\Application\brave.exe"]),
    "firefox": ("Firefox", "about:logins",
                [r"%ProgramFiles%\Mozilla Firefox\firefox.exe",
                 r"%ProgramFiles(x86)%\Mozilla Firefox\firefox.exe"]),
}


def browser_exe(which):
    for pat in BROWSERS.get(which, (None, None, []))[2]:
        path = os.path.expandvars(pat)
        if os.path.exists(path):
            return path
    return None


def downloads_dir():
    for p in [os.path.join(os.environ.get("USERPROFILE", os.path.expanduser("~")), "Downloads"),
              os.path.expanduser("~/Downloads")]:
        if os.path.isdir(p):
            return p
    return os.path.expanduser("~")


def newest_csv_after(since_ms):
    """Newest .csv in Downloads modified after since_ms (a browser password export), as text."""
    newest, newest_t = None, since_ms / 1000.0
    for f in glob.glob(os.path.join(downloads_dir(), "*.csv")):
        try:
            t = os.path.getmtime(f)
        except OSError:
            continue
        if t > newest_t:
            newest, newest_t = f, t
    if not newest:
        return None
    try:
        with open(newest, encoding="utf-8-sig", errors="replace") as fh:
            return {"name": os.path.basename(newest), "text": fh.read()}
    except Exception:
        return None


class Api:
    def load(self):
        if os.path.exists(VAULT):
            with open(VAULT, encoding="utf-8") as f:
                return unprotect_local(f.read())
        return None

    def save(self, text):
        write_atomic(VAULT, protect_local(text))
        return True

    def copy(self, text, secret=False):
        set_clipboard(text, secret)
        return True

    def save_backup(self, text, name):
        r = window.create_file_dialog(webview.SAVE_DIALOG, save_filename=name,
                                      file_types=("MyVault backup (*.vault)",))
        if not r:
            return None
        path = r if isinstance(r, str) else r[0]
        if not path.lower().endswith(".vault"):
            path += ".vault"
        write_atomic(path, text)
        return path

    def browsers(self):
        return {k: {"name": v[0], "found": browser_exe(k) is not None} for k, v in BROWSERS.items()}

    def open_browser_export(self, which):
        info = BROWSERS.get(which)
        exe = browser_exe(which)
        if not info or not exe:
            return {"ok": False, "name": info[0] if info else which}
        try:
            subprocess.Popen([exe, info[1]], close_fds=True)
        except Exception:
            try:
                subprocess.Popen([exe, "--new-window", info[1]], close_fds=True)
            except Exception as e:
                return {"ok": False, "error": str(e), "name": info[0]}
        return {"ok": True, "name": info[0], "since": int(time.time() * 1000)}

    def poll_browser_csv(self, since_ms):
        return newest_csv_after(int(since_ms))

    def set_capture_hidden(self, on):
        return hide_from_capture(bool(on))

    # ----- Wi-Fi sync (all data passed here is already encrypted by the app) -----
    def sync_info(self):
        return {"name": SYNC.name, "ips": local_ips(), "port": SYNC.port, "running": SYNC.port is not None}

    def sync_config(self, sid):
        SYNC.set_sid(sid)
        return True

    def sync_set_out(self, blob):
        SYNC.set_out(blob)
        return True

    def sync_discover(self, sid, known=None):
        return SYNC.discover(sid, known or [])

    def sync_fetch(self, ip, port, sid):
        return SYNC.fetch(ip, int(port), sid)

    def sync_push(self, ip, port, sid, blob):
        return SYNC.push(ip, int(port), sid, blob)

    def sync_inbox(self):
        return SYNC.take_inbox()

    # ----- tap-to-pair (crypto is done in the app; these only move messages) -----
    def sync_set_pairing(self, on, pub=""):
        SYNC.set_pairing(on, pub)
        return True

    def sync_find(self):
        return SYNC.find()

    def sync_pair_requests(self):
        return SYNC.take_pair_requests()

    def sync_approve_pair(self, rid):
        return SYNC.approve_pair(rid)

    def sync_pair_send(self, ip, port, obj):
        return SYNC.pair_send(ip, int(port), obj)


if __name__ == "__main__":
    SYNC.start()  # Windows may ask to allow MyVault on your network: choose Private networks
    # Served from a local http://127.0.0.1 server so browser encryption (WebCrypto) is allowed.
    window = webview.create_window("Keystead", url=os.path.join(BASE, "MyVault.html"), js_api=Api(),
                                   width=1220, height=780, min_size=(860, 560), background_color="#0a0c11")
    window.events.shown += lambda: hide_from_capture(True)  # hidden from streams by default, even on the lock screen
    webview.start(http_server=True, private_mode=True)
