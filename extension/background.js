const ext = globalThis.browser || globalThis.chrome;
// Keystead extension: talks only to the Keystead app on this computer (127.0.0.1).
// The site a login is requested for is always taken from the browser itself (sender.url or the
// active tab), never from what a web page says, so a page can't ask for another site's login.
const PORTS = [47900, 47901, 47902, 47903, 47904, 47905];
let port = null;

async function token() {
  return (await ext.storage.local.get("token")).token || "";
}

async function post(path, body = {}, p = port) {
  const r = await fetch(`http://127.0.0.1:${p}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Keystead-Token": await token() },
    body: JSON.stringify(body),
  });
  return r.json();
}

async function findPort() {
  if (port) {
    try { const r = await post("/ext/hello", {}, port); if (r.app === "keystead") return port; } catch (e) {}
  }
  for (const p of PORTS) {
    try { const r = await post("/ext/hello", {}, p); if (r.app === "keystead") { port = p; return p; } } catch (e) {}
  }
  port = null;
  return null;
}

async function call(path, body) {
  if (!(await findPort())) return { offline: true };
  try { return await post(path, body); } catch (e) { port = null; return { offline: true }; }
}

function hostOf(url) {
  try { const u = new URL(url); return /^https?:$/.test(u.protocol) ? u.hostname : ""; } catch (e) { return ""; }
}

function browserName() {
  const ua = navigator.userAgent;
  if (/Firefox\//.test(ua)) return "Firefox";
  if (/OPR\//.test(ua)) return "Opera GX";
  if (/Edg\//.test(ua)) return "Microsoft Edge";
  if (navigator.brave) return "Brave";
  return "Google Chrome";
}

async function status() {
  const p = await findPort();
  if (!p) return { offline: true };
  const r = await post("/ext/hello");
  return { paired: !!r.paired };
}

ext.runtime.onMessage.addListener((msg, sender, reply) => {
  (async () => {
    // Content scripts: the host comes from the frame that sent the message.
    // The popup (an extension page) passes the active tab's host, which it reads from ext.tabs.
    const fromPage = !(sender.url || "").startsWith(ext.runtime.getURL("")); // anything not our own extension page
    const host = fromPage ? hostOf(sender.url || "") : msg.host || "";
    switch (msg.t) {
      case "status": return reply(await status());
      case "logins": return reply(host ? await call("/ext/logins", { host }) : { items: [] });
      case "fill": return reply(await call("/ext/fill", { id: msg.id, host, manual: !fromPage && !!msg.manual }));
      case "search":
        if (fromPage) return reply({ error: "not allowed" }); // only the popup may search all accounts
        return reply(await call("/ext/search", { q: msg.q || "" }));
      case "focus": return reply(await call("/ext/focus", {}));
      case "pairStart":
        if (fromPage) return reply({ error: "not allowed" });
        return reply(await call("/ext/pair/start", { browser: browserName() }));
      case "pairFinish": {
        if (fromPage) return reply({ error: "not allowed" });
        const r = await call("/ext/pair/finish", { code: msg.code, browser: browserName() });
        if (r.ok && r.token) await ext.storage.local.set({ token: r.token });
        return reply({ ok: !!r.ok, error: r.error, offline: r.offline });
      }
      case "unpair":
        if (fromPage) return reply({ error: "not allowed" });
        await ext.storage.local.remove("token");
        return reply({ ok: true });
      default: return reply({ error: "unknown" });
    }
  })();
  return true; // async reply
});
