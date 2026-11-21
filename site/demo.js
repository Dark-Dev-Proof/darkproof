// The DarkProof demo on the home page: a simulation of the app that runs only in this browser tab. The people, places
// and answers are made up and scripted. Nothing is sent anywhere: the page's Content-Security-Policy blocks all network
// requests from scripts (connect-src 'none'). Screens and wording follow the iPhone app (ios/Blackout).
(function () {
  "use strict";
  var phone = document.getElementById("dp-phone");
  if (!phone) return;

  var $ = function (id) { return document.getElementById(id); };
  var app = $("app"), tabbar = $("tabbar"), sheetEl = $("sheet"), toastEl = $("toast"), bannerEl = $("banner");
  var sbTime = $("sb-time"), sbIcons = $("sb-icons"), live = $("dp-live");
  var netBtn = $("net"), netText = $("net-text"), tipsDone = $("tips-done"), slot = $("phone");

  var GROUP = "Family", MIN = 60000;
  var NEEDS = { injured: "Injured", trapped: "Trapped", water: "Need water", food: "Need food", medicine: "Need medicine", shelter: "Need shelter" };
  var KINDS = { water: ["Water", "drop-fill"], shelter: ["Shelter", "house-fill"], charging: ["Charging", "lightning-fill"], medical: ["Medical", "first-aid-kit-fill"] };
  var TABS = [["home", "Home", "house-fill"], ["chat", "Chat", "chats-fill"], ["places", "Places", "sys-pin"], ["people", "People", "users-fill"], ["ask", "Ask", "sys-sparkles"]];
  var SUGGEST = ["Who in " + GROUP + " needs help?", "How do I treat a burn?", "Catch me up on the last 6 hours", "How do I make water safe to drink?"];

  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function ic(name, cls) { return '<svg class="ic ic-' + name + (cls ? " " + cls : "") + '" aria-hidden="true"><use href="#i-' + name + '"></use></svg>'; }
  var SOS = '<span class="sos" aria-hidden="true">SOS</span>';
  function clock(ts) { return new Date(ts).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }); }
  function ago(ts) {
    var s = Math.max(0, Math.floor((Date.now() - ts) / 1000));
    if (s < 60) return "just now";
    if (s < 3600) return Math.floor(s / 60) + " min ago";
    if (s < 86400) return Math.floor(s / 3600) + " h ago";
    return Math.floor(s / 86400) + " d ago";
  }
  function meters(d) { return d < 100 ? Math.round(d / 5) * 5 + " m" : d < 1000 ? Math.round(d / 10) * 10 + " m" : (d / 1000).toFixed(1) + " km"; }
  function battery(p) {
    var n = "sys-battery-" + (p <= 10 ? 0 : p <= 35 ? 25 : p <= 60 ? 50 : p <= 85 ? 75 : 100);
    return '<span class="bat' + (p <= 20 ? " low" : "") + '">' + ic(n) + p + "%</span>";
  }

  // ---- State ----
  var S, timers = [];
  function later(f, ms) { timers.push(setTimeout(f, ms)); }
  function fresh() {
    var t = Date.now();
    return {
      online: true, tab: "home", channel: "group", sheet: null, map: false, filter: null, story: false, answered: false,
      unread: 0, my: null, draft: { group: "", everyone: "", ask: "" }, enter: true, n: 0, fallback: 0,
      people: [
        { id: "anna", name: "Anna", last: t - 52 * MIN, check: null, battery: 71 },
        { id: "marco", name: "Marco", last: t - 50 * MIN, check: null, battery: 38 }
      ],
      msgs: [
        { k: "line", ch: "group", text: "Marco joined", ts: t - 26 * 60 * MIN },
        { k: "text", ch: "group", from: "anna", text: "Storm warning for tonight. Is everyone home?", ts: t - 52 * MIN },
        { k: "text", ch: "group", from: "marco", text: "Home. Phones charged, power bank too.", ts: t - 50 * MIN },
        { k: "text", ch: "group", from: "anna", text: "Good. Stay in, it looks like a rough night.", ts: t - 47 * MIN },
        { k: "text", ch: "everyone", name: "Elena", text: "Is the pharmacy on Corso Italia open?", ts: t - 2 * 60 * MIN - 9 * MIN },
        { k: "place", ch: "everyone", place: "p4", ts: t - 2 * 60 * MIN - 4 * MIN },
        { k: "place", ch: "everyone", place: "p3", ts: t - 64 * MIN },
        { k: "place", ch: "everyone", place: "p2", ts: t - 35 * MIN },
        { k: "place", ch: "everyone", place: "p1", ts: t - 12 * MIN }
      ],
      places: [
        { id: "p1", kind: "water", note: "Tap at the school gym, bring bottles", by: "Marco", ts: t - 12 * MIN, d: 250, dir: "Northeast", deg: 45 },
        { id: "p2", kind: "charging", note: "Library, ground floor. Open until 20:00", by: "Elena", ts: t - 35 * MIN, d: 400, dir: "West", deg: 270 },
        { id: "p3", kind: "shelter", note: "Parish hall on Via Roma, beds and blankets", by: "Paolo", ts: t - 64 * MIN, d: 900, dir: "South", deg: 180 },
        { id: "p4", kind: "medical", note: "Pharmacy on Corso Italia, open with a generator", by: "Sara", ts: t - 2 * 60 * MIN - 4 * MIN, d: 1400, dir: "Southeast", deg: 135 }
      ],
      ask: [],
      done: { cut: false, check: false, msg: false, find: false }
    };
  }
  function person(id) { return S.people.filter(function (p) { return p.id === id; })[0]; }
  function place(id) { return S.places.filter(function (p) { return p.id === id; })[0]; }

  // ---- Small UI helpers ----
  function toast(text) {
    toastEl.innerHTML = "<span>" + esc(text) + "</span>";
    clearTimeout(toast.t);
    toast.t = setTimeout(function () { toastEl.innerHTML = ""; }, 2600);
  }
  function say(text) { if (live) live.textContent = text; }
  function mark(tip) {
    if (S.done[tip]) return;
    S.done[tip] = true;
    var li = document.querySelector('[data-tip="' + tip + '"]');
    if (li) li.classList.add("done");
    if (tipsDone && Object.keys(S.done).every(function (k) { return S.done[k]; })) tipsDone.hidden = false;
  }
  function banner(title, text) {
    bannerEl.innerHTML = '<img src="logo.png" alt=""><span><b>' + esc(title) + "</b><span>" + esc(text) + "</span></span>";
    bannerEl.classList.remove("out");
    bannerEl.hidden = false;
    clearTimeout(banner.t); clearTimeout(banner.o);
    banner.t = setTimeout(function () { // slides back up, then goes
      bannerEl.classList.add("out");
      banner.o = setTimeout(function () { bannerEl.hidden = true; bannerEl.classList.remove("out"); }, 300);
    }, 5200);
  }

  // ---- Messages ----
  function add(m) {
    m.id = "m" + (++S.n); m.ts = Date.now(); m.isNew = true;
    S.msgs.push(m);
    return m;
  }
  function incoming(m) {
    add(m);
    var p = m.from && person(m.from);
    if (p) {
      p.last = m.ts;
      if (m.k === "check") { p.check = m.check; p.checkTs = m.ts; if (m.spot) p.spot = m.spot; }
    }
    var who = p ? p.name : m.name;
    var text = m.k === "check" ? who + (m.check === "safe" ? " is safe" : " needs help") : m.k === "ask" ? who + " asks: is everyone safe?" : m.text;
    say(m.k === "text" ? who + ": " + text : text);
    if (S.tab !== "chat" || S.channel !== m.ch) {
      S.unread++;
      banner(m.k === "text" ? who : GROUP, text);
    }
    render();
  }
  function deliver(m) {
    later(function () { m.st = "sent"; render(); }, 500);
    if (m.ch !== "group") return; // messages to everyone have no receipts
    later(function () { m.st = "delivered"; render(); }, 1700);
  }
  var REPLIES = [
    [/water|drink|thirst/i, "marco", "There is water at the school gym. I added it to Places."],
    [/where|address|position|location/i, "anna", "At home. I will share my position with my next check in."],
    [/help|hurt|injur|bleed|emergency/i, "marco", "Tell me where you are. I can come over."],
    [/power|light|electric|battery|charg/i, "marco", "Still no power on our street. The library has charging."],
    [/safe|ok|okay|fine|alright|good/i, "anna", "Good to hear. We are fine here too."],
    [/hello|hi\b|hey|ciao|test/i, "anna", "Got it. So this works without the network."],
    [/\?/, "anna", "Not sure yet. I will ask around and write back."]
  ];
  var FALLBACK = [["anna", "Got it."], ["marco", "Received. Slow, but it arrives."], ["anna", "Read you. Stay in touch."]];
  function replyTo(text) {
    var r = REPLIES.filter(function (x) { return x[0].test(text); })[0];
    if (!r) { var f = FALLBACK[S.fallback++ % FALLBACK.length]; r = [null, f[0], f[1]]; }
    later(function () { incoming({ k: "text", ch: "group", from: r[1], text: r[2] }); }, 2600);
  }
  function sendDraft() {
    var key = S.tab === "ask" ? "ask" : S.channel, text = S.draft[key].trim();
    if (!text) return;
    S.draft[key] = "";
    if (S.tab === "ask") { askAi(text); return; }
    var m = add({ k: "text", ch: S.channel, from: "me", text: text.slice(0, 500), st: "waiting" });
    deliver(m);
    mark("msg");
    if (S.channel === "group") replyTo(text);
    render(true);
  }
  function checkIn(kind, needs) {
    var m = add({ k: "check", ch: "group", from: "me", check: kind, battery: 82, needs: needs || [], st: "waiting" });
    S.my = { check: kind, ts: m.ts };
    S.answered = true;
    toast(kind === "safe" ? "Sent “I'm safe”" : "Sent “I need help”");
    deliver(m);
    mark("check");
    if (kind === "safe") {
      later(function () { incoming({ k: "text", ch: "group", from: "anna", text: "All three of us. Good. Stay inside until it passes." }); }, 5600);
    } else {
      later(function () { incoming({ k: "text", ch: "group", from: "marco", text: "On my way. Stay where you are." }); }, 2600);
      later(function () { incoming({ k: "text", ch: "group", from: "anna", text: "Marco is coming. Keep your phone on." }); }, 5200);
    }
    render();
  }

  // ---- The network switch beside the phone ----
  function setOnline(on) {
    S.online = on;
    netBtn.setAttribute("aria-checked", String(on));
    netText.textContent = on ? "The network is on. Messages also travel through internet relays."
                             : "The network is down. Messages now hop over Bluetooth to the 2 phones in range.";
    if (!on) {
      mark("cut");
      if (!S.story) {
        S.story = true;
        later(function () { incoming({ k: "ask", ch: "group", from: "anna" }); }, 1400);
        later(function () { incoming({ k: "check", ch: "group", from: "marco", check: "safe", battery: 38, spot: { d: 250, dir: "Northeast", deg: 45 } }); }, 4400);
      }
    }
    render();
  }

  // ---- Ask (scripted answers) ----
  function names(list) { return list.length < 2 ? list.join("") : list.slice(0, -1).join(", ") + " and " + list[list.length - 1]; }
  function answer(q) {
    if (/need.*help|who.*help/i.test(q)) {
      var safe = S.people.filter(function (p) { return p.check === "safe"; }).map(function (p) { return p.name; });
      var quiet = S.people.filter(function (p) { return !p.check; }).map(function (p) { return p.name; });
      var out = S.my && S.my.check === "help" ? "You asked for help " + ago(S.my.ts) + ". Nobody else in " + GROUP + " has." : "Nobody in " + GROUP + " has asked for help.";
      if (safe.length) out += "\n\n" + names(safe) + (safe.length === 1 ? " is safe." : " are safe.");
      if (quiet.length) out += "\n\nNo check-in yet from " + names(quiet) + ".";
      return out;
    }
    if (/catch me up|last 6 hours|summar/i.test(q)) {
      var s = "Anna shared a storm warning for tonight. Marco is home with the phones charged.";
      if (S.story) s += "\n\nThen Anna asked if everyone is safe." + (person("marco").check ? " Marco checked in safe." : "");
      s += "\n\n" + (S.my ? (S.my.check === "safe" ? "You checked in safe " : "You asked for help ") + ago(S.my.ts) + "." : "You have not checked in yet.");
      return s + "\n\nNearby: water at the school gym, 250 m Northeast.";
    }
    if (/burn/i.test(q)) return "Cool the burn under cool running water for 20 minutes. Take off rings and loose clothing near it, unless they are stuck to the skin.\n\nCover it loosely with cling film or a clean cloth. No ice, butter or creams.\n\nGet medical help for burns that are large, deep, or on the face or hands.";
    if (/water|drink/i.test(q)) return "Boil it. Bring clear water to a rolling boil for 1 minute, or 3 minutes above 2,000 m, then let it cool.\n\nIf you can't boil it, add 2 drops of plain unscented household bleach per litre, stir and wait 30 minutes.\n\nIf the water is cloudy, filter it through a clean cloth first.";
    return "This demo knows only a few answers. Try one of the suggestions.\n\nIn the app, a model that runs on the phone answers your own questions, with no internet.";
  }
  function askAi(q) {
    if (S.asking) return;
    S.asking = true;
    S.ask.push({ me: true, text: q.slice(0, 300) });
    var a = { me: false, text: "", busy: true };
    S.ask.push(a);
    render(true);
    later(function () {
      var words = answer(q).split(/(\s+)/), i = 0;
      var step = function () {
        i = Math.min(words.length, i + 3);
        a.text = words.slice(0, i).join("");
        var el = document.querySelector("#ask-last .b-text"), list = document.querySelector(".msgs");
        if (el) { el.textContent = a.text; el.classList.remove("p-dim"); if (list) list.scrollTop = list.scrollHeight; }
        if (i < words.length) later(step, 45); else { a.busy = false; S.asking = false; say(a.text); render(); }
      };
      step();
    }, 900);
  }

  // ---- Screens ----
  function statusLine(m, noTime) {
    if (m.from !== "me") return "";
    var d = m.st === "delivered" ? ic("check-circle-fill") + "Delivered to Anna and Marco" : m.st === "sent" ? ic("check-bold") + "Sent" : ic("clock-bold") + "Waiting for a phone or internet";
    return '<div class="b-st' + (m.st === "delivered" ? " del" : "") + '">' + (noTime ? "" : clock(m.ts) + " · ") + d + "</div>";
  }
  function checkCard(m) {
    var me = m.from === "me", p = person(m.from), safe = m.check === "safe";
    var title = me ? (safe ? "You're safe" : "You asked for help") : p.name + (safe ? " is safe" : " needs help");
    var extra = (m.needs || []).map(function (n) { return NEEDS[n]; }).join(" · ");
    return '<div class="ci' + (safe ? "" : " help") + (m.isNew ? " new" : "") + '"><div class="ci-top">' + (safe ? ic("shield-check-fill") : SOS) +
      '<span class="grow">' + esc(title) + "</span>" + battery(m.battery) + '<span class="ci-time">' + clock(m.ts) + "</span></div>" +
      (extra ? '<div class="ci-extra">' + esc(extra) + "</div>" : "") +
      (m.spot ? '<div class="ci-extra p-dim">' + meters(m.spot.d) + " " + m.spot.dir + "</div>" : "") +
      (me ? statusLine(m, true) : "") + "</div>";
  }
  function askCard(m) {
    return '<div class="askc' + (m.isNew ? " new" : "") + '"><div class="ci-top">' + ic("chat-circle-dots-fill") + '<span class="grow">' + esc(person(m.from).name) +
      ' asks: is everyone safe?</span><span class="ci-time">' + clock(m.ts) + "</span></div>" +
      (S.answered ? "" : '<div class="askc-btns"><button class="ghost safe" data-act="safe">' + ic("shield-check-fill") + "I'm safe</button>" +
        '<button class="ghost" data-act="help">' + SOS + "I need help</button></div>") + "</div>";
  }
  function placeCard(m) {
    var p = place(m.place);
    if (!p) return "";
    return '<button class="askc' + (m.isNew ? " new" : "") + '" data-act="place" data-arg="' + p.id + '"><span class="ci-top">' + ic(KINDS[p.kind][1]) +
      '<span class="grow">' + KINDS[p.kind][0] + '</span><span class="ci-time">' + (p.d ? meters(p.d) + " " + p.dir : "Here") + "</span></span>" +
      '<span class="row-text">' + esc(p.note) + '</span><span class="row-sub">By ' + esc(p.by) + " · " + ago(p.ts) + "</span></button>";
  }
  function bubble(m, prev) {
    var me = m.from === "me", name = me ? "" : (m.from ? person(m.from).name : m.name);
    var showName = !me && !(prev && prev.k === "text" && prev.from === m.from && prev.name === m.name);
    return '<div class="b' + (me ? " me" : "") + (m.isNew ? " new" : "") + '">' + (showName ? '<span class="b-name">' + esc(name) + "</span>" : "") +
      '<span class="b-text">' + esc(m.text) + "</span>" + statusLine(m) + "</div>";
  }
  function composer(key, placeholder, pub) {
    var v = S.draft[key];
    return '<div class="composer"><input id="draft" type="text" autocomplete="off" enterkeyhint="send" maxlength="500" aria-label="' + placeholder +
      '" placeholder="' + placeholder + '" value="' + esc(v) + '"><button class="send' + (v.trim() ? " on" : "") + '" id="send" data-act="send" aria-label="Send">' +
      ic(pub ? "megaphone-fill" : "arrow-up-bold") + "</button></div>";
  }
  function personRow(p, act) {
    var sub = p.check ? '<div class="row-sub ' + p.check + '">' + (p.check === "safe" ? "Safe" : "Needs help") + " · " + ago(p.checkTs) + "</div>"
                      : '<div class="row-sub">Last heard ' + ago(p.last) + "</div>";
    return '<button class="row" data-act="' + (act || "person") + '" data-arg="' + p.id + '"><span class="av' + (p.check ? " " + p.check : "") + '">' + p.name.charAt(0) +
      '</span><span class="row-main"><div class="row-name">' + esc(p.name) + "</div>" + sub + "</span>" + (p.check ? battery(p.battery) : "") +
      (p.spot ? '<span class="round">' + ic("sys-arrow") + "</span>" : "") + "</button>";
  }
  function lastCheckIn() {
    return '<p class="p-note">' + (S.my ? "Your last check-in: " + (S.my.check === "safe" ? "safe" : "needs help") + ", " + ago(S.my.ts) + "." : "One tap tells your group how you are.") + "</p>";
  }

  var SCREENS = {
    home: function () {
      return '<div class="h-head"><img src="logo.png" alt=""><button class="h-group" data-act="na">' + GROUP + ic("caret-down-bold") + "</button>" +
        '<button class="round" data-act="na" aria-label="Settings">' + ic("gear-fill") + "</button></div>" +
        '<button class="h-hero vault" data-act="connect"><span class="h-st">Connected</span><span class="h-big"><b>2</b><span>phones<br>in range</span></span>' +
        '<span class="h-sum">Over Bluetooth. Your messages hop from phone to phone.' + (S.online ? " Also online." : "") + "</span></button>" +
        '<div class="stack"><div class="qa-row">' +
        '<button class="qa safe" data-act="safe"><span class="qa-ic">' + ic("shield-check-fill") + "</span>I'm safe</button>" +
        '<button class="qa help" data-act="help"><span class="qa-ic">' + SOS + "</span>Need help</button>" +
        '<button class="qa" data-act="invite"><span class="qa-ic">' + ic("qr-code-bold") + "</span>Invite</button>" +
        '<button class="qa" data-act="connect"><span class="qa-ic">' + ic("broadcast-bold") + "</span>Connect</button></div>" + lastCheckIn() + "</div>" +
        '<h3 class="p-sec">Your people</h3><div class="list">' + S.people.map(function (p) { return personRow(p); }).join("") + "</div>";
    },
    chat: function () {
      var pub = S.channel === "everyone", prev = null;
      var list = S.msgs.filter(function (m) { return m.ch === S.channel; }).map(function (m) {
        var h = m.k === "line" ? '<div class="line">' + esc(m.text) + "</div>" : m.k === "check" ? checkCard(m) : m.k === "ask" ? askCard(m) : m.k === "place" ? placeCard(m) : bubble(m, prev);
        prev = m;
        return h;
      }).join("");
      return '<div class="chat-top"><div class="seg" role="tablist" aria-label="Channel" data-pos="' + (pub ? 1 : 0) + '">' +
        '<button role="tab" aria-selected="' + !pub + '" data-act="channel" data-arg="group">' + GROUP + "</button>" +
        '<button role="tab" aria-selected="' + pub + '" data-act="channel" data-arg="everyone">Everyone</button></div>' +
        (pub ? '<p class="pub-note">' + ic("megaphone-fill") + "To everyone: anyone with DarkProof can read it</p>" : "") + "</div>" +
        '<div class="msgs">' + list + "</div>" + composer(S.channel, pub ? "Message everyone nearby" : "Message " + GROUP, pub);
    },
    places: function () {
      var shown = S.places.filter(function (p) { return !S.filter || p.kind === S.filter; }).sort(function (a, b) { return a.d - b.d; });
      var chips = '<div class="chips"><button class="chip" aria-pressed="' + !S.filter + '" data-act="filter" data-arg="">All</button>' +
        Object.keys(KINDS).map(function (k) { return '<button class="chip" aria-pressed="' + (S.filter === k) + '" data-act="filter" data-arg="' + k + '">' + KINDS[k][0] + "</button>"; }).join("") + "</div>";
      var rows = shown.map(function (p) {
        return '<button class="row" data-act="place" data-arg="' + p.id + '"><span class="av">' + ic(KINDS[p.kind][1]) + '</span><span class="row-main"><div class="row-name">' +
          KINDS[p.kind][0] + '</div><div class="row-text">' + esc(p.note) + '</div><div class="row-sub">By ' + esc(p.by) + " · " + ago(p.ts) + "</div></span>" +
          '<span class="row-end">' + (p.d ? "<b>" + meters(p.d) + "</b><span>" + p.dir + "</span>" : "<b>Here</b>") + "</span></button>";
      }).join("");
      var body = S.map
        ? '<div class="map" id="map"><svg viewBox="0 0 300 320" aria-hidden="true" fill="none" stroke="#313131" stroke-linecap="round">' +
          '<path stroke-width="12" d="M-10 96 L310 150"/><path stroke-width="12" d="M118 -10 L172 330"/><path stroke-width="6" d="M-10 236 L310 212"/>' +
          '<path stroke-width="6" d="M40 -10 L70 330"/><path stroke-width="6" d="M236 -10 L262 330"/><path stroke-width="6" d="M-10 30 L310 52"/></svg>' +
          shown.map(function (p) { return '<button class="pin" data-pin="' + p.id + '" data-act="place" data-arg="' + p.id + '" aria-label="' + KINDS[p.kind][0] + ", " + esc(p.note) + '">' + ic(KINDS[p.kind][1]) + "</button>"; }).join("") +
          '<span class="pin you" data-pin="you" role="img" aria-label="You are here"></span></div><p class="p-note">A sketch of the map for this demo. The app shows real streets, saved on the phone for when there is no internet.</p>'
        : chips + '<button class="pbtn" data-act="addplace">' + ic("plus-bold") + "Share a place</button>" +
          (rows ? '<div class="list">' + rows + "</div>" : '<div class="list"><div class="row"><span class="row-main"><div class="row-name">No ' + KINDS[S.filter][0].toLowerCase() + " places yet</div></span></div></div>") +
          '<p class="p-note">Places disappear after 7 days.</p>';
      return '<div><h2 class="p-title">Places</h2><p class="p-sub">Shared by anyone with DarkProof, nearby or online</p></div>' +
        '<div class="seg" role="tablist" aria-label="View" data-pos="' + (S.map ? 1 : 0) + '"><button role="tab" aria-selected="' + !S.map + '" data-act="view" data-arg="">List</button>' +
        '<button role="tab" aria-selected="' + S.map + '" data-act="view" data-arg="map">Map</button></div>' + body;
    },
    people: function () {
      return '<div><h2 class="p-title">People</h2><p class="p-sub">' + GROUP + " · " + S.people.length + " people</p></div>" +
        '<div class="stack"><div class="big2"><button class="bigc" data-act="safe">' + ic("shield-check-fill") + "I'm safe</button>" +
        '<button class="bigc help" data-act="help">' + SOS + "I need help</button></div>" + lastCheckIn() + "</div>" +
        '<div class="list">' + S.people.map(function (p) { return personRow(p); }).join("") + "</div>" +
        '<div class="stack"><button class="sbtn" data-act="asksafe">' + ic("chat-circle-dots-fill") + "Ask “Are you safe?”</button>" +
        '<button class="sbtn" data-act="invite">' + ic("qr-code-bold") + "Invite friends</button></div>";
    },
    ask: function () {
      var body = S.ask.length
        ? '<div class="msgs">' + S.ask.map(function (t, i) {
            var last = i === S.ask.length - 1, first = !t.me && (i === 0 || S.ask[i - 1].me);
            return '<div class="b' + (t.me ? " me" : "") + '"' + (last ? ' id="ask-last"' : "") + ">" + (first ? '<span class="b-name">Assistant</span>' : "") +
              '<span class="b-text' + (t.busy && !t.text ? " p-dim" : "") + '">' + esc(t.busy && !t.text ? "Thinking…" : t.text) + "</span></div>";
          }).join("") + "</div>"
        : '<div class="msgs">' + SUGGEST.map(function (q) { return '<button class="sug" data-act="suggest" data-arg="' + esc(q) + '">' + esc(q) + "</button>"; }).join("") + "</div>";
      return '<div class="ask-top"><div><h2 class="p-title">Ask</h2><p class="p-sub">Works with no internet. It can be wrong: double-check anything important.</p></div>' +
        '<button class="round" data-act="newask" aria-label="New conversation"' + (S.ask.length && !S.asking ? "" : " disabled") + ">" + ic("arrow-counter-clockwise-bold") + "</button></div>" +
        body + composer("ask", S.asking ? "Thinking…" : "Ask a question", false);
    }
  };

  // ---- Sheets ----
  function head(title, close) { return '<div class="sheet-head"><b>' + title + '</b><button class="ghost" data-act="close" id="sheet-close">' + (close || "Done") + "</button></div>"; }
  function qr() {
    var n = 21, seed = 7, out = "", f = function (x, y) { return (x < 7 && y < 7) || (x > 13 && y < 7) || (x < 7 && y > 13); };
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) {
      seed = (seed * 1103515245 + 12345) & 0x7fffffff;
      if (!f(x, y) && seed % 100 < 46) out += '<rect x="' + x + '" y="' + y + '" width="1" height="1"/>';
    }
    [[0, 0], [14, 0], [0, 14]].forEach(function (p) {
      out += '<path fill-rule="evenodd" d="M' + p[0] + " " + p[1] + "h7v7h-7zM" + (p[0] + 1) + " " + (p[1] + 1) + 'v5h5v-5z"/><rect x="' + (p[0] + 2) + '" y="' + (p[1] + 2) + '" width="3" height="3"/>';
    });
    return '<div class="qr" role="img" aria-label="Sample invite code. It opens nothing."><svg viewBox="0 0 21 21" fill="#000" shape-rendering="crispEdges">' + out + "</svg></div>";
  }
  var SHEETS = {
    help: function (s) {
      var any = s.needs.length;
      return head("I need help", "Cancel") + "<h3>Tell " + GROUP + " you need help?</h3>" +
        '<button class="dbtn" data-act="sendhelp">' + SOS + (any ? "Send with these details" : "Send “I need help”") + "</button>" +
        '<p class="p-sub">Add details below if you can. Your battery level goes with it, and your position if you share it (Settings).</p>' +
        '<p class="p-sub">What is needed? (optional)</p><div class="need-grid">' + Object.keys(NEEDS).map(function (k) {
          return '<button class="chip" aria-pressed="' + (s.needs.indexOf(k) >= 0) + '" data-act="need" data-arg="' + k + '">' + NEEDS[k].replace("Need ", "").replace(/^./, function (c) { return c.toUpperCase(); }) + "</button>";
        }).join("") + "</div>" +
        '<p class="p-note">Then write in Chat where you are and what happened. If you can reach emergency services, call them too.</p>';
    },
    invite: function () {
      return head("Invite") + "<h3>Invite to " + GROUP + '</h3><p class="p-sub">Your friend points their phone camera at this code, or scans it in DarkProof.</p>' + qr() +
        '<button class="pbtn" data-act="demo">' + ic("export-bold") + "Send invite</button>" +
        '<div class="card"><b>Compare these with the other phone:</b><span class="emoji" aria-label="dog, smile, panda, hammer, penguin">🐶 😀 🐼 🔨 🐧</span>' +
        '<span class="p-note">Different emoji: you are not in the same group.</span></div>' +
        '<p class="p-note">An admin of the group lets each new person in.</p>';
    },
    connect: function () {
      var row = function (on, icon, name, sub) { return '<div class="conn' + (on ? " on" : "") + '">' + ic(icon) + '<span class="row-main"><div class="row-name">' + name + '</div><div class="row-sub">' + sub + "</div></span></div>"; };
      return head("Connect") + '<p class="p-sub">The ways your messages travel. All but Internet keep working in a blackout.</p><div class="list">' +
        row(true, "bluetooth-bold", "Bluetooth", "2 phones in range") + row(false, "wifi-high-bold", "Same Wi-Fi network", "No phones on this network") +
        row(S.online, "globe-bold", "Internet", S.online ? "Linked, through Tor" : "No internet right now") + row(false, "broadcast-bold", "LoRa radio", "No radio paired") + "</div>" +
        '<p class="p-note">iPhones and Android phones with DarkProof within about 10–50 m connect by themselves.</p>';
    },
    find: function (s) {
      var t = s.target;
      return head(esc(t.title)) + '<div class="dial">' + ic("sys-arrow") + '</div><div><p class="find-d">' + meters(t.d) + '</p><p class="find-w">' + t.dir + " of you</p></div>" +
        '<div class="card"><b>' + esc(t.name) + '</b><span class="p-sub">' + esc(t.detail) + "</span>" +
        '<span class="p-note">This phone has no compass right now, so the arrow assumes the top of the phone points north.</span></div>' +
        '<button class="sbtn" data-act="demo">' + ic("map-trifold-bold") + "Open in Maps</button>";
    },
    addplace: function (s) {
      return head("Share a place", "Cancel") + '<p class="p-sub">What is it?</p><div class="kind-grid">' + Object.keys(KINDS).map(function (k) {
          return '<button class="kind" aria-pressed="' + (s.kind === k) + '" data-act="kind" data-arg="' + k + '">' + ic(KINDS[k][1]) + KINDS[k][0] + "</button>";
        }).join("") + '</div><input class="field" id="place-note" type="text" maxlength="120" autocomplete="off" aria-label="Details" placeholder="Tap at the school gym, bring bottles" value="' + esc(s.note) + '">' +
        '<p class="p-note">Everyone nearby with DarkProof sees it, with your position, so people can find it with the compass.</p>' +
        '<button class="pbtn" data-act="shareplace">Share with everyone</button>';
    }
  };
  var screenEl = phone.querySelector(".phone-screen"), sheetTimer = 0, segPos = {};
  function openSheet(s) {
    S.sheet = s;
    clearTimeout(sheetTimer);
    sheetEl.classList.remove("closing");
    screenEl.classList.add("sheet-open"); // the screen behind steps back, as on an iPhone
    sheetEl.innerHTML = '<button class="sheet-dim" data-act="close" aria-label="Close" tabindex="-1"></button><div class="sheet" role="dialog" aria-modal="true">' + SHEETS[s.type](s) + "</div>";
    sheetEl.hidden = false;
    if (s.type === "find") { var a = sheetEl.querySelector(".dial .ic"); if (a) setTimeout(function () { a.style.transform = "rotate(" + s.target.deg + "deg)"; }, 60); }
    var c = $("sheet-close"); if (c) c.focus({ preventScroll: true });
  }
  function updateSheet() { // same sheet, new content, no slide
    var body = sheetEl.querySelector(".sheet"), note = $("place-note");
    if (note && S.sheet) S.sheet.note = note.value;
    if (body) { var top = body.scrollTop; body.innerHTML = SHEETS[S.sheet.type](S.sheet); body.scrollTop = top; }
  }
  function closeSheet(now) { // slides down, then goes
    S.sheet = null;
    screenEl.classList.remove("sheet-open");
    clearTimeout(sheetTimer);
    var gone = function () { sheetEl.hidden = true; sheetEl.innerHTML = ""; sheetEl.classList.remove("closing"); };
    if (now || sheetEl.hidden) { gone(); return; }
    sheetEl.classList.add("closing");
    sheetTimer = setTimeout(gone, 340);
  }

  // ---- Rendering ----
  function renderStatus() {
    sbTime.textContent = clock(Date.now());
    sbIcons.innerHTML = (S.online ? ic("sys-signal") + ic("sys-wifi") : '<span class="sb-none">No service</span>') + ic("sys-battery-75");
  }
  function render(focusDraft) {
    var old = app.firstElementChild, top = old && !S.enter ? old.scrollTop : 0;
    var had = focusDraft || (document.activeElement && document.activeElement.id === "draft");
    var entering = S.enter, pop = S.pop;
    var cls = "scr" + (S.tab === "chat" ? " chat" : S.tab === "ask" ? " ask" : "") + (entering ? " enter" : "");
    app.innerHTML = '<div class="' + cls + '">' + SCREENS[S.tab]() + "</div>";
    S.enter = false; S.pop = false;
    S.msgs.forEach(function (m) { if (m.ch === S.channel && S.tab === "chat") m.isNew = false; });
    var scr = app.firstElementChild, list = scr.querySelector(".msgs");
    if (list) list.scrollTop = list.scrollHeight; else scr.scrollTop = top;
    Array.prototype.forEach.call(scr.querySelectorAll(".seg"), function (el) { // the white thumb slides to its new side
      var key = el.getAttribute("aria-label"), pos = el.dataset.pos, was = segPos[key];
      segPos[key] = pos;
      if (!entering && was !== undefined && was !== pos) { el.dataset.pos = was; void el.offsetWidth; el.dataset.pos = pos; }
    });
    Array.prototype.forEach.call(scr.querySelectorAll("[data-pin]"), function (el) {
      var p = el.dataset.pin === "you" ? { d: 0, deg: 0 } : place(el.dataset.pin), r = p.d ? 34 + p.d * 0.068 : 0, a = p.deg * Math.PI / 180;
      el.style.left = (150 + Math.sin(a) * r) / 3 + "%";
      el.style.top = (160 - Math.cos(a) * r) / 3.2 + "%";
    });
    var d = $("draft");
    if (d && had) { d.focus({ preventScroll: true }); d.setSelectionRange(d.value.length, d.value.length); }
    tabbar.innerHTML = TABS.map(function (t) {
      return '<button class="tab' + (pop && S.tab === t[0] ? " pop" : "") + '" role="tab" aria-selected="' + (S.tab === t[0]) + '" data-act="tab" data-arg="' + t[0] + '">' + ic(t[2]) + t[1] +
        (t[0] === "chat" && S.unread ? '<span class="tab-badge">' + S.unread + "</span>" : "") + "</button>";
    }).join("");
    renderStatus();
  }
  function go(tab) {
    if (S.tab !== tab) { S.enter = true; S.pop = true; } // the new tab's icon bounces
    S.tab = tab;
    if (tab === "chat") { S.unread = 0; bannerEl.hidden = true; }
    render();
  }

  // ---- Actions ----
  var ACT = {
    tab: function (a) { go(a); },
    channel: function (a) { S.channel = a; S.unread = 0; render(); },
    safe: function () { checkIn("safe"); },
    help: function () { openSheet({ type: "help", needs: [] }); },
    need: function (a) { var n = S.sheet.needs, i = n.indexOf(a); if (i >= 0) n.splice(i, 1); else n.push(a); updateSheet(); },
    sendhelp: function () { var n = S.sheet.needs; closeSheet(); checkIn("help", n); },
    invite: function () { openSheet({ type: "invite" }); },
    connect: function () { openSheet({ type: "connect" }); },
    close: function () { closeSheet(); },
    send: function () { sendDraft(); },
    na: function () { toast("Not part of this demo"); },
    demo: function () { toast("This is a demo: nothing leaves this page"); },
    banner: function () { S.channel = "group"; go("chat"); },
    asksafe: function () {
      var m = add({ k: "text", ch: "group", from: "me", text: "Is everyone safe?", st: "waiting" });
      deliver(m); mark("msg"); toast("Sent “Are you safe?”");
      later(function () { incoming({ k: "text", ch: "group", from: "marco", text: "All good here." }); }, 2600);
      render();
    },
    person: function (a) {
      var p = person(a);
      if (!p.spot) { toast(p.name + " hasn't shared a position"); return; }
      mark("find");
      openSheet({ type: "find", target: { title: p.name, name: p.name, d: p.spot.d, dir: p.spot.dir, deg: p.spot.deg, detail: "Where " + p.name + " checked in " + ago(p.checkTs) + "." } });
    },
    place: function (a) {
      var p = place(a);
      if (!p.d) { toast("You are at this place"); return; }
      mark("find");
      openSheet({ type: "find", target: { title: KINDS[p.kind][0], name: p.note, d: p.d, dir: p.dir, deg: p.deg, detail: "Shared by " + p.by + " " + ago(p.ts) + "." } });
    },
    filter: function (a) { S.filter = a || null; render(); },
    view: function (a) { S.map = !!a; render(); },
    addplace: function () { openSheet({ type: "addplace", kind: "water", note: "" }); },
    kind: function (a) { S.sheet.kind = a; updateSheet(); },
    shareplace: function () {
      var note = ($("place-note").value || "").trim().slice(0, 120), k = S.sheet.kind;
      var p = { id: "u" + (++S.n), kind: k, note: note || KINDS[k][0] + " here", by: "You", ts: Date.now(), d: 0, dir: "", deg: 0 };
      S.places.push(p);
      var m = add({ k: "place", ch: "everyone", place: p.id }); m.isNew = false;
      closeSheet(); toast("Place shared with everyone"); render();
    },
    suggest: function (a) { askAi(a); },
    newask: function () { S.ask = []; render(); }
  };
  phone.addEventListener("click", function (e) {
    var t = e.target.closest("[data-act]");
    if (!t || t.disabled) return;
    var f = ACT[t.dataset.act];
    if (f) f(t.dataset.arg || "", t);
  });
  phone.addEventListener("input", function (e) {
    if (e.target.id !== "draft") return;
    var key = S.tab === "ask" ? "ask" : S.channel;
    S.draft[key] = e.target.value;
    $("send").classList.toggle("on", !!e.target.value.trim());
  });
  phone.addEventListener("keydown", function (e) {
    if (e.key === "Enter" && e.target.id === "draft" && !e.isComposing) { e.preventDefault(); sendDraft(); }
    if (e.key === "Escape" && S.sheet) closeSheet();
  });
  bannerEl.setAttribute("data-act", "banner");

  // ---- The page around the phone ----
  function fit() {
    var k = Math.min(1, (document.documentElement.clientWidth - 32) / 350);
    if (window.matchMedia("(min-width: 1024px)").matches) k = Math.min(k, Math.max(0.74, (window.innerHeight - 160) / 740));
    slot.style.setProperty("--k", k.toFixed(3));
  }
  function start() {
    timers.forEach(clearTimeout); timers = [];
    S = fresh();
    closeSheet(true); bannerEl.hidden = true; toastEl.innerHTML = "";
    Array.prototype.forEach.call(document.querySelectorAll("[data-tip]"), function (li) { li.classList.remove("done"); });
    if (tipsDone) tipsDone.hidden = true;
    netBtn.setAttribute("aria-checked", "true");
    netText.textContent = "The network is on. Messages also travel through internet relays.";
    render();
  }
  netBtn.addEventListener("click", function () { setOnline(!S.online); });
  var restart = $("restart");
  if (restart) restart.addEventListener("click", function () { start(); toast("Demo started again"); });
  Array.prototype.forEach.call(document.querySelectorAll("[data-start-demo]"), function (a) {
    a.addEventListener("click", function (e) {
      e.preventDefault();
      var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      // On a wide screen the phone is already beside the button; on a narrow one it is further down the page.
      if (!window.matchMedia("(min-width: 1024px)").matches) $("stage").scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
      slot.classList.remove("hint");
      setTimeout(function () { slot.classList.add("hint"); }, 30);
      netBtn.focus({ preventScroll: true });
    });
  });
  window.addEventListener("resize", fit);
  setInterval(function () { if (S) renderStatus(); }, 20000);
  fit();
  start();
})();
