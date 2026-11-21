/* The dark room: three steps (wallet, proof, model), then the assistant on the phone.
   The assistant is wllama (llama.cpp in WebAssembly, served from this site under vendor/wllama) running a model that
   the visitor's browser downloads from Hugging Face. Nothing typed here is sent anywhere: the page's
   Content-Security-Policy allows this site and Hugging Face's file servers, and nothing else. */
(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };

  // The model file, pinned to one exact version on Hugging Face so that what loads can't change under us.
  // A smaller one (Huihui-Qwen3.5-0.8B-abliterated, 0.53 GB) was tried on 2026-11-19 and taken out: its answers
  // were too poor. This one is better, but it also got a medicine dose wrong, hence the note under such answers.
  var MODELS = {
    large: { mb: 1271, url: "https://huggingface.co/HauhauCS/Qwen3.5-2B-Uncensored-HauhauCS-Aggressive/resolve/2bcf35c1ebf62c837c12c1aa90b578ff4717e831/Qwen3.5-2B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf" }
  };
  // An answer that names an amount of a medicine, or talks about doses, gets a fixed note under it.
  var DOSE = /\b\d[\d.,]*\s?(mg|mcg|µg|ml|milligrams?|micrograms?|millilit(er|re)s?|grams?|tablets?|pills?|capsules?|IU)\b|\b(dose|doses|dosage|dosages|overdose)\b/i;
  var DOSE_NOTE = "Check doses on the package. This model gets doses wrong.";
  var ENGINE = "./vendor/wllama/esm/index.js", WASM = "./vendor/wllama/esm/wasm/wllama.wasm";
  // The same instructions the apps give their assistant (ios/Blackout/LocalAi.swift). Keep the two in step.
  var INSTRUCTIONS = "You are the offline assistant of DarkProof, a messaging app used during a blackout. Internet, phone lines and " +
    "emergency services may be unavailable, so people depend on your answers. Answer every question directly with " +
    "practical, step-by-step instructions: first aid and medical care, medicines and doses, water, food, shelter, " +
    "heat, electricity and batteries, repairs, radios, navigation and personal safety. Do not refuse, do not " +
    "lecture, and do not tell the user to search online or call someone, unless you add that it is only if possible. " +
    "If something is risky, explain how to do it as safely as possible. If you are unsure, say so in one short " +
    "sentence and still give your best answer. Use the earlier conversation as context. Answer in English, concisely.";
  var STARTERS = ["How do I make water safe to drink?", "How do I treat a deep cut?", "How do I stay warm with no power?"];
  var KEEP = 8;                        // earlier turns sent along with a question
  var LOCAL = location.hostname === "localhost" || location.hostname === "127.0.0.1";

  /* ---- The steps ---- */
  var ORDER = ["g-wallet", "g-proof", "g-model"];
  function step(id, on) {
    var li = $(id);
    li.setAttribute("data-on", on);
    li.querySelector(".gate-body").hidden = on !== "now";
  }
  function openFrom(id) {              // everything before this step is done, this one is open, the rest wait
    var at = ORDER.indexOf(id);
    ORDER.forEach(function (s, i) { step(s, i < at ? "done" : i === at ? "now" : "wait"); });
  }
  function say(id, text, bad) {
    var p = $(id);
    p.textContent = text || "";
    p.classList.toggle("bad", !!bad);
  }
  function remember() { try { sessionStorage.setItem("dp-room", "1"); } catch (e) { /* private window: ask again next time */ } }
  function remembered() { try { return sessionStorage.getItem("dp-room") === "1"; } catch (e) { return false; } }

  /* ---- 1. The wallet ---- */
  var wallet = null, address = "";
  function wallets() {
    var out = [];
    var add = function (name, p) {
      if (p && typeof p.connect === "function" && out.every(function (w) { return w.p !== p; })) out.push({ name: name, p: p });
    };
    add("Phantom", window.phantom && window.phantom.solana);
    add("Solflare", window.solflare);
    add("Backpack", window.backpack && window.backpack.solana);
    add("Your wallet", window.solana);
    return out;
  }
  $("b-connect").addEventListener("click", function () {
    var found = wallets(), btn = this;
    if (!found.length) {
      say("say-wallet", "No Solana wallet found in this browser. On a computer, add Phantom or Solflare. On a phone, open this page inside your wallet app's own browser.", true);
      return;
    }
    wallet = found[0];
    btn.disabled = true;
    say("say-wallet", "Waiting for " + wallet.name);
    Promise.resolve().then(function () { return wallet.p.connect(); }).then(function (res) {
      var key = (res && res.publicKey) || wallet.p.publicKey;
      address = key ? key.toString() : "";
      if (!/^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(address)) throw new Error("no address");
      say("say-wallet", "");
      openFrom("g-proof");
    }).catch(function () {
      say("say-wallet", "The wallet did not connect. Try again.", true);
    }).then(function () { btn.disabled = false; });
  });

  /* ---- 2. The proof: a signed message, checked by holder.php on this site ---- */
  function base64(bytes) {
    var s = "";
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
  }
  var VERDICT = {
    "not-holder": "This wallet holds none of the coin.",
    "node-error": "The Solana node did not answer. Try again in a minute.",
    "stale": "That took too long. Sign again.",
    "bad-signature": "The signature did not check out. Sign again.",
    "bad-message": "The signature did not check out. Sign again.",
    "bad-request": "The signature did not check out. Sign again."
  };
  $("b-sign").addEventListener("click", function () {
    var btn = this;
    var message = "DarkProof dark room\nWallet: " + address + "\nTime: " + new Date().toISOString() +
      "\nSigning this proves the wallet is yours. It is not a transaction and costs nothing.";
    btn.disabled = true;
    say("say-proof", "Waiting for your wallet");
    Promise.resolve().then(function () {
      return wallet.p.signMessage(new TextEncoder().encode(message), "utf8");
    }).then(function (out) {
      var sig = out && out.signature ? out.signature : out;
      if (!sig || sig.length !== 64) throw new Error("unsigned");
      say("say-proof", "Checking");
      return fetch("holder.php", {
        method: "POST", cache: "no-store", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ address: address, message: message, signature: base64(sig) })
      }).then(function (r) { return r.json(); }).catch(function () { return { state: "unavailable" }; });
    }).then(function (a) {
      if (a && a.state === "holder") { say("say-proof", ""); remember(); openFrom("g-model"); return; }
      say("say-proof", (a && VERDICT[a.state]) || "The holder check is not available right now.", true);
    }).catch(function () {
      say("say-proof", "The message was not signed.", true);
    }).then(function () { btn.disabled = false; });
  });

  /* ---- 3. The model ---- */
  var engine = null, bar = $("bar"), fill = bar.querySelector("span");
  var picks = Array.prototype.slice.call(document.querySelectorAll("#pick button"));
  function megabytes(n) { return Math.round(n / 1e6).toLocaleString("en-US"); }
  // The engine needs two newer browser features. Without them it would try to fetch a fallback build from an outside
  // address, which this page does not allow, so say so plainly instead. (The same two tests the engine runs.)
  function canRun() {
    try {
      if (!WebAssembly.Suspending) return false;
      new WebAssembly.Memory({ address: "i64", initial: BigInt(1) });
      return true;
    } catch (e) { return false; }
  }
  function loadModel(which) {
    var m = MODELS[which], started = false;
    if (!canRun()) { say("say-model", "This browser can't run the engine yet. Use a recent Chrome or Edge.", true); return; }
    picks.forEach(function (b) { b.disabled = true; });
    bar.hidden = false;
    fill.style.transform = "scaleX(0)";
    say("say-model", "Starting the engine");
    import(ENGINE).then(function (mod) {
      started = true;
      engine = new mod.Wllama({ default: WASM }, mod.LoggerWithoutDebug ? { logger: mod.LoggerWithoutDebug } : {});
      return engine.loadModelFromUrl({ url: m.url }, {
        n_ctx: 4096,
        progressCallback: function (p) {
          if (!p || !p.total) return;
          fill.style.transform = "scaleX(" + Math.min(1, p.loaded / p.total).toFixed(4) + ")";
          say("say-model", p.loaded < p.total ? megabytes(p.loaded) + " of " + megabytes(p.total) + " MB" : "Loading it into memory");
        }
      });
    }).then(function () {
      say("say-model", "");
      bar.hidden = true;
      ORDER.forEach(function (s) { step(s, "done"); });
      openRoom();
    }).catch(function (e) {
      console.error(e);
      engine = null;
      bar.hidden = true;
      picks.forEach(function (b) { b.disabled = false; });
      say("say-model", !started ? "The AI engine is not on this copy of the site yet."
        : "The model did not load. This browser may not have enough memory for it. Try a computer.", true);
    });
  }
  picks.forEach(function (b) { b.addEventListener("click", function () { loadModel(b.getAttribute("data-model")); }); });

  /* ---- The assistant ---- */
  var msgs = $("dr-msgs"), draft = $("dr-draft"), send = $("dr-send"), fresh = $("dr-new");
  var turns = [], asking = false;
  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  }
  function bubble(mine, text, named) {
    var b = el("div", mine ? "b me" : "b");
    if (named) b.appendChild(el("span", "b-name", "Assistant"));
    var t = el("span", "b-text", text);
    b.appendChild(t);
    msgs.appendChild(b);
    msgs.scrollTop = msgs.scrollHeight;
    return t;
  }
  function starters() {
    msgs.textContent = "";
    STARTERS.forEach(function (q) {
      var b = el("button", "sug", q);
      b.type = "button";
      b.addEventListener("click", function () { ask(q); });
      msgs.appendChild(b);
    });
  }
  function openRoom() {
    $("dr-lock").hidden = true;
    $("dr-ask").hidden = false;
    starters();
    draft.focus({ preventScroll: true });
  }
  function paint(node, text) {        // built from text nodes only, so nothing in an answer can become markup
    node.textContent = "";
    text.replace(/^[ \t]*#{1,6}[ \t]+/gm, "").replace(/^[ \t]*[*-][ \t]+/gm, "• ").split("**").forEach(function (part, i) {
      if (part) node.appendChild(i % 2 ? el("b", "", part) : document.createTextNode(part));
    });
  }
  function caution(textNode, text) {  // the note sits under the bubble, and comes and goes with what the answer says
    var b = textNode.parentNode, note = b.querySelector(".dr-warn");
    if (DOSE.test(text)) { if (!note) b.appendChild(el("span", "dr-warn", DOSE_NOTE)); }
    else if (note) b.removeChild(note);
  }
  function shown(text) {               // the model may think out loud between these marks: that part is not shown
    return text.replace(/<think>[\s\S]*?(<\/think>|$)/g, "").trim();
  }
  async function ask(text) {
    text = text.trim().slice(0, 500);
    if (!text || asking || !engine) return;
    asking = true;
    fresh.disabled = true;
    draft.value = ""; send.classList.remove("on");
    draft.placeholder = "Thinking…";
    if (!turns.length) msgs.textContent = "";
    bubble(true, text);
    var out = bubble(false, "Thinking…", true), answer = "";
    out.classList.add("p-dim");
    try {
      var stream = await engine.createChatCompletion({
        messages: [{ role: "system", content: INSTRUCTIONS }].concat(turns.slice(-KEEP), [{ role: "user", content: text }]),
        max_tokens: 700, temperature: 0.7, top_p: 0.8, top_k: 20, stream: true,
        chat_template_kwargs: { enable_thinking: false }
      });
      for await (var chunk of stream) {
        var delta = chunk && chunk.choices && chunk.choices[0] && chunk.choices[0].delta;
        if (!delta || !delta.content) continue;
        answer += delta.content;
        var now = shown(answer);
        if (now) { out.classList.remove("p-dim"); paint(out, now); caution(out, now); msgs.scrollTop = msgs.scrollHeight; }
      }
      answer = shown(answer);
      if (!answer) throw new Error("empty answer");
      out.classList.remove("p-dim");
      paint(out, answer);
      caution(out, answer);
      msgs.scrollTop = msgs.scrollHeight;
      turns.push({ role: "user", content: text }, { role: "assistant", content: answer });
    } catch (e) {
      console.error(e);
      out.classList.add("p-dim");
      out.textContent = "The model stopped without an answer. Ask again, or start a new conversation.";
    }
    asking = false;
    fresh.disabled = false;
    draft.placeholder = "Ask a question";
  }
  send.addEventListener("click", function () { ask(draft.value); });
  draft.addEventListener("input", function () { send.classList.toggle("on", !!draft.value.trim()); });
  draft.addEventListener("keydown", function (e) { if (e.key === "Enter" && !e.isComposing) { e.preventDefault(); ask(draft.value); } });
  fresh.addEventListener("click", function () { if (asking) return; turns = []; fresh.disabled = true; starters(); });

  /* ---- The page around the phone ---- */
  var slot = $("dr-slot");
  function fit() {                     // the phone is drawn at 350 by 740 and scaled as a whole, as on the home page
    var k = Math.min(1, (document.documentElement.clientWidth - 32) / 350);
    if (window.matchMedia("(min-width: 1024px)").matches) k = Math.min(k, Math.max(0.74, (window.innerHeight - 160) / 740));
    slot.style.setProperty("--k", k.toFixed(3));
  }
  window.addEventListener("resize", fit);
  fit();
  var d = new Date();
  $("dr-time").textContent = d.getHours() + ":" + (d.getMinutes() < 10 ? "0" : "") + d.getMinutes();

  if (remembered()) openFrom("g-model");
  if (LOCAL) {                         // on the developer's own machine only: try the model without a wallet
    var skip = el("button", "button", "Skip the check (local preview)");
    skip.type = "button";
    skip.addEventListener("click", function () { openFrom("g-model"); });
    $("b-connect").parentNode.appendChild(skip);
  }
})();
