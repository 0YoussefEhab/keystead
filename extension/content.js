// Keystead content script: shows a small login picker under login fields and fills the one you pick.
(() => {
  const ext = globalThis.browser || globalThis.chrome;
  if (window.__keystead) return;
  window.__keystead = true;

  const isVisible = (el) => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return r.width > 20 && r.height > 10 && cs.visibility !== "hidden" && cs.display !== "none" && !el.disabled && !el.readOnly;
  };
  const passFields = () => [...document.querySelectorAll('input[type="password"]')].filter(isVisible);
  const USER_RE = /user|email|e-mail|login|account|identifier|phone|name/i;
  function isUserField(el) {
    if (!(el instanceof HTMLInputElement) || !isVisible(el)) return false;
    const t = (el.type || "text").toLowerCase();
    if (!["text", "email", "tel", ""].includes(t)) return false;
    const ac = (el.autocomplete || "").toLowerCase();
    if (ac.includes("username") || ac === "email") return true;
    const hint = [el.name, el.id, el.placeholder, el.getAttribute("aria-label")].join(" ");
    return (passFields().length > 0 || t === "email") && USER_RE.test(hint);
  }
  // The username box that goes with a password box: the closest visible text/email field before it.
  function userFieldFor(pass) {
    const scope = pass.form || document;
    const inputs = [...scope.querySelectorAll("input")].filter(isVisible);
    const i = inputs.indexOf(pass);
    for (let j = i - 1; j >= 0; j--) {
      const t = (inputs[j].type || "text").toLowerCase();
      if (["text", "email", "tel"].includes(t)) return inputs[j];
    }
    return null;
  }
  function setValue(el, v) {
    if (!el) return;
    const proto = Object.getPrototypeOf(el);
    const setter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
    el.focus();
    setter ? setter.call(el, v) : (el.value = v); // native setter so React/Vue forms notice
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }
  function fillCreds(user, pass, anchor) {
    let pf = anchor && anchor.type === "password" ? anchor : passFields()[0] || null;
    let uf = anchor && anchor.type !== "password" ? anchor : pf ? userFieldFor(pf) : [...document.querySelectorAll("input")].find(isUserField);
    if (user && uf) setValue(uf, user);
    if (pass && pf) setValue(pf, pass);
    return !!(uf || pf);
  }

  // ---------- dropdown (in a shadow root so the page's CSS can't change it) ----------
  const host = document.createElement("keystead-ui");
  const root = host.attachShadow({ mode: "closed" });
  const tpl = `<style>
    .box{position:fixed;z-index:2147483647;min-width:260px;max-width:340px;background:#151922;color:#eef1f7;border:1px solid #2a3142;
      border-radius:12px;box-shadow:0 16px 40px -10px #000c;font:13px/1.4 "Segoe UI",system-ui,sans-serif;padding:6px;display:none}
    .head{display:flex;align-items:center;gap:8px;padding:6px 8px 8px;color:#8a93a8;font-size:11px;letter-spacing:.06em;text-transform:uppercase}
    .head img{width:16px;height:16px;border-radius:4px}
    .row{display:flex;align-items:center;gap:10px;padding:8px;border-radius:8px;cursor:pointer}
    .row:hover,.row.on{background:#10b98126}
    .av{width:28px;height:28px;border-radius:8px;display:grid;place-items:center;font-weight:700;color:#fff;background:linear-gradient(135deg,#10b981,#65c94a);flex:none}
    .t{min-width:0}.t b{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.t span{color:#8a93a8;font-size:12px}
    .msg{padding:8px;color:#aeb6c8}.msg a{color:#34d399;cursor:pointer;text-decoration:underline}
    .x{margin-left:auto;cursor:pointer;color:#8a93a8;padding:0 4px}
  </style><div class="box"></div>`;
  root.replaceChildren(...new DOMParser().parseFromString(`<body>${tpl}</body>`, "text/html").body.childNodes);
  const box = root.querySelector(".box");
  let anchor = null, items = [], idx = 0, dismissed = new WeakSet();
  const icon = "data:image/png;base64,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";
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  function place() {
    if (!anchor) return;
    const r = anchor.getBoundingClientRect();
    box.style.left = Math.max(4, Math.min(r.left, innerWidth - 350)) + "px";
    box.style.top = (r.bottom + 6 + 220 > innerHeight && r.top > 230 ? r.top - 6 - box.offsetHeight : r.bottom + 6) + "px";
  }
  function hide() { box.style.display = "none"; anchor = null; }
  function head() { return `<div class="head"><img src="${icon}">Keystead<span class="x" data-x>✕</span></div>`; }
  function show(html) {
    if (!host.isConnected) document.documentElement.appendChild(host);
    const doc = new DOMParser().parseFromString(`<body>${head() + html}</body>`, "text/html"); // inert parse; values are escaped
    box.replaceChildren(...doc.body.childNodes);
    box.style.display = "block";
    place();
    box.querySelector("[data-x]").onclick = () => { if (anchor) dismissed.add(anchor); hide(); };
  }
  function renderItems() {
    show(items.map((e, i) => `<div class="row ${i === idx ? "on" : ""}" data-i="${i}"><div class="av">${esc((e.name || "?")[0].toUpperCase())}</div>
      <div class="t"><b>${esc(e.name)}</b><span>${esc(e.user || "no username")}${e.group ? " · " + esc(e.group) : ""}${e.via ? " · signs in with " + esc(e.via) : ""}</span></div></div>`).join(""));
    box.querySelectorAll("[data-i]").forEach((el) => el.addEventListener("mousedown", (ev) => { ev.preventDefault(); pick(items[+el.dataset.i]); }));
  }
  async function pick(e) {
    if (!e) return;
    const target = anchor;
    const r = await ext.runtime.sendMessage({ t: "fill", id: e.id });
    if (r.locked) return show(`<div class="msg">🔒 Keystead is locked. <a data-open>Open Keystead</a> and unlock it.</div>`), bindOpen();
    if (r.error || r.offline) return show(`<div class="msg">Couldn't fill: ${esc(r.error || "Keystead isn't running")}</div>`);
    hide();
    if (!r.pass && r.via) {
      fillCreds(r.user, "", target);
      alertMsg(target, `This account signs in with ${r.via}. Use the "Continue with Google/Microsoft" button.`);
      return;
    }
    fillCreds(r.user, r.pass, target);
  }
  function alertMsg(target, text) { anchor = target; show(`<div class="msg">${esc(text)}</div>`); setTimeout(hide, 4000); }
  function bindOpen() { const a = box.querySelector("[data-open]"); if (a) a.onclick = () => ext.runtime.sendMessage({ t: "focus" }); }

  async function openFor(field) {
    if (dismissed.has(field)) return;
    anchor = field;
    let r;
    try { r = await ext.runtime.sendMessage({ t: "logins" }); } catch (e) { return; } // extension reloaded
    if (anchor !== field) return;
    if (r.offline) return; // app not running: stay out of the way
    if (r.error === "not paired" || r.paired === false) return show(`<div class="msg">Connect this browser to Keystead: click the Keystead button in the toolbar.</div>`);
    if (r.locked) return show(`<div class="msg">🔒 Keystead is locked. <a data-open>Open Keystead</a> to unlock, then click here again.</div>`), bindOpen();
    items = r.items || [];
    idx = 0;
    if (!items.length) return show(`<div class="msg">No saved login for this site. Use the Keystead button in the toolbar to search all accounts.</div>`);
    renderItems();
  }

  document.addEventListener("focusin", (ev) => {
    const el = ev.target;
    if (!(el instanceof HTMLInputElement)) return;
    if (el.type === "password" ? isVisible(el) : isUserField(el)) openFor(el);
    else if (box.style.display === "block" && !host.contains(el)) hide();
  }, true);
  document.addEventListener("mousedown", (ev) => {
    if (box.style.display === "block" && ev.target !== anchor && !ev.composedPath().includes(host)) hide();
  }, true);
  document.addEventListener("keydown", (ev) => {
    if (box.style.display !== "block" || ev.target !== anchor || !items.length) return;
    if (ev.key === "ArrowDown") { ev.preventDefault(); idx = (idx + 1) % items.length; renderItems(); }
    else if (ev.key === "ArrowUp") { ev.preventDefault(); idx = (idx - 1 + items.length) % items.length; renderItems(); }
    else if (ev.key === "Enter" && box.querySelector(".row.on")) { ev.preventDefault(); pick(items[idx]); }
    else if (ev.key === "Escape") { dismissed.add(anchor); hide(); }
  }, true);
  addEventListener("scroll", place, true);
  addEventListener("resize", place);

  // Fill requested from the toolbar popup.
  ext.runtime.onMessage.addListener((msg, sender, reply) => {
    if (msg.t === "doFill") {
      const a = document.activeElement;
      reply({ ok: fillCreds(msg.user, msg.pass, a instanceof HTMLInputElement && (a.type === "password" || isUserField(a)) ? a : null) });
    }
  });
})();
