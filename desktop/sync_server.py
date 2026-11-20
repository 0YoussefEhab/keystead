"""MyVault Wi-Fi sync: a tiny LAN server + discovery.

Only ciphertext ever crosses the network: the app encrypts its sync data with a key derived
from the pairing code before handing it to this module. Requests must also carry the sync id
(a hash of the code), so devices that aren't paired get nothing but a 404.
"""
import glob, json, os, socket, threading, time, urllib.request, uuid
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

HTTP_PORTS = range(47800, 47810)
UDP_PORT = 47850
MAX_BYTES = 25 * 1024 * 1024
INSTANCE = uuid.uuid4().hex


class SyncServer:
    def __init__(self, app_dir):
        self.dir = os.path.join(app_dir, "sync")
        self.out = os.path.join(self.dir, "out.dat")
        self.inbox = os.path.join(self.dir, "inbox")
        self.conf = os.path.join(self.dir, "sync.json")
        self.port = None
        self.name = socket.gethostname()
        # Tap-to-pair state (no crypto here; the app does ECDH/SAS, this only relays)
        self.pairing = False
        self.pair_pub = ""
        self.pair_requests = []   # incoming {rid, from, name, pub, ip}
        self.pair_status = {}     # rid -> "approved"
        os.makedirs(self.inbox, exist_ok=True)

    # ---------- tap-to-pair ----------
    def set_pairing(self, on, pub):
        self.pairing = bool(on)
        self.pair_pub = pub or ""
        self.pair_requests = []
        if on:
            self.pair_status = {}  # fresh session; keep approvals after turning off so the other device can still read them

    def take_pair_requests(self):
        reqs, self.pair_requests = self.pair_requests, []
        return reqs

    def approve_pair(self, rid):
        self.pair_status[rid] = "approved"
        return True

    # ---------- config ----------
    def sid(self):
        try:
            with open(self.conf, encoding="utf-8") as f:
                return json.load(f).get("sid", "")
        except Exception:
            return ""

    def set_sid(self, sid):
        if sid:
            _write(self.conf, json.dumps({"sid": sid}).encode())
        else:
            for p in [self.conf, self.out] + glob.glob(os.path.join(self.inbox, "*.dat")):
                try: os.remove(p)
                except OSError: pass

    def set_out(self, blob):
        _write(self.out, blob.encode())

    def take_inbox(self):
        items = []
        for p in sorted(glob.glob(os.path.join(self.inbox, "*.dat"))):
            try:
                with open(p, encoding="utf-8") as f:
                    items.append(f.read())
            except Exception:
                pass
            try: os.remove(p)
            except OSError: pass
        return items

    # ---------- servers ----------
    def start(self):
        srv = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a): pass

            def _send(self, code, body=b""):
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _ok(self):
                s = srv.sid()
                return bool(s) and self.headers.get("X-Sync-Id", "") == s

            def do_GET(self):
                if not self._ok(): return self._send(404)
                if self.path == "/myvault/hello":
                    return self._send(200, json.dumps({"name": srv.name, "from": INSTANCE}).encode())
                if self.path == "/myvault/blob" and os.path.exists(srv.out):
                    with open(srv.out, "rb") as f:
                        return self._send(200, f.read())
                self._send(404)

            def do_POST(self):
                # Pairing endpoint is open (no sync-id yet): it only queues prompts the user must approve.
                if self.path == "/myvault/pair":
                    n = int(self.headers.get("Content-Length") or 0)
                    if n <= 0 or n > 65536: return self._send(413)
                    try:
                        m = json.loads(self.rfile.read(n))
                    except Exception:
                        return self._send(400)
                    t = m.get("t")
                    if t == "request":
                        if not srv.pairing: return self._send(200, b'{"ok":false}')
                        if len(srv.pair_requests) < 8:
                            srv.pair_requests.append({"rid": str(m.get("rid", ""))[:64], "from": str(m.get("from", ""))[:64],
                                                      "name": str(m.get("name", ""))[:64], "pub": str(m.get("pub", ""))[:200],
                                                      "ip": self.client_address[0]})
                        return self._send(200, b'{"ok":true}')
                    if t == "status":
                        ok = srv.pair_status.get(str(m.get("rid", ""))) == "approved"
                        return self._send(200, json.dumps({"approved": ok}).encode())
                    if t == "confirm":
                        return self._send(200, b'{"ok":true}')
                    return self._send(200, b'{"ok":false}')
                if not self._ok() or self.path != "/myvault/push": return self._send(404)
                n = int(self.headers.get("Content-Length") or 0)
                if n <= 0 or n > MAX_BYTES: return self._send(413)
                data = self.rfile.read(n)
                old = sorted(glob.glob(os.path.join(srv.inbox, "*.dat")))
                for p in old[:-20]:
                    try: os.remove(p)
                    except OSError: pass
                _write(os.path.join(srv.inbox, f"{time.time_ns()}.dat"), data)
                self._send(200, b'{"ok":true}')

        for port in HTTP_PORTS:
            try:
                httpd = ThreadingHTTPServer(("0.0.0.0", port), H)
                break
            except OSError:
                continue
        else:
            return False
        httpd.daemon_threads = True
        self.port = port
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        threading.Thread(target=self._udp, daemon=True).start()
        return True

    def _udp(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("", UDP_PORT))
        except OSError:
            return
        while True:
            try:
                data, addr = s.recvfrom(2048)
                m = json.loads(data)
                if m.get("from") == INSTANCE:
                    continue
                sid = self.sid()
                if m.get("q") == "myvault" and sid and m.get("sid") == sid:
                    s.sendto(json.dumps({"a": "myvault", "port": self.port, "name": self.name,
                                         "from": INSTANCE}).encode(), addr)
                elif m.get("q") == "myvault-find" and self.pairing:  # only discoverable while "Add device" is open
                    s.sendto(json.dumps({"a": "myvault-find", "port": self.port, "name": self.name,
                                         "from": INSTANCE, "pub": self.pair_pub}).encode(), addr)
            except Exception:
                continue

    def find(self):
        found = {}
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.25); s.bind(("", 0))
        msg = json.dumps({"q": "myvault-find", "from": INSTANCE}).encode()
        targets = {"255.255.255.255"} | {ip.rsplit(".", 1)[0] + ".255" for ip in local_ips()}
        for _ in range(2):
            for t in targets:
                try: s.sendto(msg, (t, UDP_PORT))
                except OSError: pass
            end = time.time() + 0.7
            while time.time() < end:
                try:
                    data, addr = s.recvfrom(2048)
                    m = json.loads(data)
                    if m.get("a") == "myvault-find" and m.get("from") != INSTANCE:
                        found[m["from"]] = {"ip": addr[0], "port": m["port"], "name": m["name"], "id": m["from"], "pub": m.get("pub", "")}
                except socket.timeout:
                    continue
                except Exception:
                    continue
        s.close()
        return list(found.values())

    def pair_send(self, ip, port, obj):
        try:
            r = _http("POST", ip, int(port), "/myvault/pair", "", json.dumps(obj).encode(), timeout=5)
            return json.loads(r)
        except Exception:
            return None

    # ---------- client side ----------
    def discover(self, sid, known=()):
        found = {}
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(0.25)
        s.bind(("", 0))
        msg = json.dumps({"q": "myvault", "sid": sid, "from": INSTANCE}).encode()
        targets = {"255.255.255.255"} | {ip.rsplit(".", 1)[0] + ".255" for ip in local_ips()}
        for _ in range(2):
            for t in targets:
                try: s.sendto(msg, (t, UDP_PORT))
                except OSError: pass
            end = time.time() + 0.8
            while time.time() < end:
                try:
                    data, addr = s.recvfrom(2048)
                    m = json.loads(data)
                    if m.get("a") == "myvault" and m.get("from") != INSTANCE:
                        found[addr[0]] = {"ip": addr[0], "port": m["port"], "name": m["name"], "id": m["from"]}
                except socket.timeout:
                    continue
                except Exception:
                    continue
        s.close()
        for k in known or []:  # devices we've synced with before, tried directly
            if k.get("ip") in found: continue
            for port in [k.get("port")] + list(HTTP_PORTS):
                if not port: continue
                try:
                    r = _http("GET", k["ip"], port, "/myvault/hello", sid, timeout=0.6)
                    m = json.loads(r)
                    if m.get("from") != INSTANCE:
                        found[k["ip"]] = {"ip": k["ip"], "port": port, "name": m["name"], "id": m["from"]}
                    break
                except Exception:
                    continue
        uniq = {}
        for d in found.values():  # one entry per running app
            uniq.setdefault(d["id"], d)
        return list(uniq.values())

    def fetch(self, ip, port, sid):
        try:
            return _http("GET", ip, port, "/myvault/blob", sid, timeout=6).decode("utf-8")
        except Exception:
            return None

    def push(self, ip, port, sid, blob):
        try:
            _http("POST", ip, port, "/myvault/push", sid, blob.encode(), timeout=8)
            return True
        except Exception:
            return False


def _http(method, ip, port, path, sid, body=None, timeout=5):
    req = urllib.request.Request(f"http://{ip}:{port}{path}", data=body, method=method,
                                 headers={"X-Sync-Id": sid, "Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # never route LAN traffic via a proxy
    with opener.open(req, timeout=timeout) as r:
        return r.read()


def _write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no packet is sent; just picks the main network interface
        ips.add(s.getsockname()[0]); s.close()
    except Exception:
        pass
    return [i for i in ips if not i.startswith("127.")]
