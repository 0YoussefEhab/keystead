const ext = globalThis.browser || globalThis.chrome;
const $ = (s) => document.querySelector(s);
const main = $("#main");
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
function setHTML(el, html) {
  const doc = new DOMParser().parseFromString(`<body>${html}</body>`, "text/html");
  el.replaceChildren(...doc.body.childNodes);
}
const send = (m) => ext.runtime.sendMessage(m);
let tab = null, siteHost = "";

function row(e, manual) {
  return `<div class="row"><div class="av">${esc((e.name || "?")[0].toUpperCase())}</div>
    <div class="t"><b>${esc(e.name)}</b><span>${esc(e.user || "no username")}${e.group ? " · " + esc(e.group) : ""}${e.via ? " · signs in with " + esc(e.via) : ""}</span></div>
    <button data-id="${esc(e.id)}" data-manual="${manual ? 1 : ""}">Fill</button></div>`;
}

async function fill(id, manual) {
  const r = await send({ t: "fill", id, host: siteHost, manual });
  if (r.locked) return render();
  if (r.error) return ($("#err").textContent = r.error === "site mismatch" ? "That login is saved for a different site." : r.error);
  if (!r.pass && r.via) $("#err").textContent = `Signs in with ${r.via}: only the username was filled.`;
  try { await ext.tabs.sendMessage(tab.id, { t: "doFill", user: r.user, pass: r.pass }); } catch (e) {
    return ($("#err").textContent = "Can't fill on this page. Reload it and try again.");
  }
  if (!(!r.pass && r.via)) window.close();
}

function bindFill() {
  main.querySelectorAll("button[data-id]").forEach((b) => (b.onclick = () => fill(b.dataset.id, !!b.dataset.manual)));
}

async function render() {
  [tab] = await ext.tabs.query({ active: true, currentWindow: true });
  try { const u = new URL(tab.url); siteHost = /^https?:$/.test(u.protocol) ? u.hostname : ""; } catch (e) { siteHost = ""; }
  const st = await send({ t: "status" });
  $("#state").textContent = st.offline ? "app not running" : st.paired ? "● connected" : "not connected";
  $("#state").style.color = st.offline ? "#ff6b81" : st.paired ? "#34d399" : "#fbbf24";

  if (st.offline) {
    setHTML(main, `<div class="msg">Open the Keystead app on this computer, then click here again.</div>`);
    return;
  }
  if (!st.paired) {
    setHTML(main, `<div class="msg">Connect this browser to your Keystead app. Keystead will show a 6-digit code.</div>
      <button id="pair" style="width:100%;margin-top:8px">Connect</button><div id="pairBox"></div><div class="err" id="err"></div>`);
    $("#pair").onclick = async () => {
      const r = await send({ t: "pairStart" });
      if (r.error || r.offline) return ($("#err").textContent = r.error || "Keystead isn't running.");
      $("#pair").remove();
      setHTML($("#pairBox"), `<div class="sec">Code shown in Keystead</div><input id="code" class="code" maxlength="6" inputmode="numeric" autocomplete="off"><button id="ok" style="width:100%">Confirm</button>`);
      $("#code").focus();
      const go = async () => {
        const r2 = await send({ t: "pairFinish", code: $("#code").value });
        if (!r2.ok) return ($("#err").textContent = r2.error || "Couldn't connect.");
        render();
      };
      $("#ok").onclick = go;
      $("#code").onkeydown = (e) => e.key === "Enter" && go();
    };
    return;
  }

  const r = siteHost ? await send({ t: "logins", host: siteHost }) : { items: [] };
  if (r.locked) {
    setHTML(main, `<div class="msg">🔒 Keystead is locked.</div><button id="open" style="width:100%">Open Keystead to unlock</button>`);
    $("#open").onclick = () => send({ t: "focus" }).then(() => window.close());
    return;
  }
  const items = r.items || [];
  setHTML(main, `<div class="site">${siteHost ? `Logins for <b>${esc(siteHost)}</b>` : "This page can't be filled."}</div>
    <div id="matches">${items.length ? items.map((e) => row(e, false)).join("") : siteHost ? `<div class="msg">No login saved for this site.</div>` : ""}</div>
    ${siteHost ? `<div class="sec">Search all accounts</div><input id="q" placeholder="Name, username, group…" autocomplete="off"><div id="results"></div>` : ""}
    <div class="err" id="err"></div>
    <footer><a id="unpair">Disconnect this browser</a><a id="openApp">Open Keystead</a></footer>`);
  bindFill();
  $("#openApp").onclick = () => send({ t: "focus" });
  $("#unpair").onclick = async () => { await send({ t: "unpair" }); render(); };
  if ($("#q")) {
    let timer;
    $("#q").oninput = () => {
      clearTimeout(timer);
      timer = setTimeout(async () => {
        const q = $("#q").value.trim();
        if (!q) return setHTML($("#results"), "");
        const s = await send({ t: "search", q });
        const list = (s.items || []).filter((e) => !items.some((m) => m.id === e.id));
        setHTML($("#results"), list.length
          ? `<div class="warn">Not saved for this site. Only fill if you're sure this is the right website; Keystead will remember it.</div>` + list.map((e) => row(e, true)).join("")
          : `<div class="msg">No matches.</div>`);
        bindFill();
      }, 200);
    };
    $("#q").focus();
  }
}

render();
