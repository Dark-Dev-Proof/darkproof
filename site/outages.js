/* The outages page: the list of countries, and what the globe (globe.js) shows.
   It reads one file, outages.json, from this site: our hourly copy of IODA's summary of outages by country. The
   page's Content-Security-Policy lets it reach no other address. */
(function () {
  "use strict";

  var canvas = document.getElementById("globe");
  var globe = window.DPGlobe ? window.DPGlobe(canvas) : null;
  var statusEl = document.getElementById("status");
  var rowsEl = document.getElementById("rows");
  var moreEl = document.getElementById("more");
  var problemEl = document.getElementById("problem");
  var readoutEl = document.getElementById("readout");

  var STRONG = 10000, MEDIUM = 1000;   // IODA's overall score for the last 24 hours (the page says so too)
  var FIRST = 10;                      // rows shown before "Show all"
  var HOUR = 3600;
  var LEVEL = ["", "Weak", "Medium", "Strong"];

  var data = null;                     // the copy on screen
  var info = {};                       // code -> { name, events, lv, drawn }
  var selected = null;
  var open = false;                    // "Show all" was pressed

  function events(n) { return n + (n === 1 ? " event" : " events"); }
  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  }

  /* Globe or map */
  var views = Array.prototype.slice.call(document.querySelectorAll(".seg-b"));
  views.forEach(function (b) {
    b.addEventListener("click", function () {
      views.forEach(function (o) { o.setAttribute("aria-pressed", o === b ? "true" : "false"); });
      if (globe) globe.view(b.getAttribute("data-view"));
    });
  });

  /* Picking a country in the list turns the globe to it. Picking it again, or Escape, lets go. */
  function pick(code) {
    selected = selected === code ? null : code;
    Array.prototype.forEach.call(rowsEl.querySelectorAll(".row"), function (b) {
      b.setAttribute("aria-pressed", b.getAttribute("data-code") === selected ? "true" : "false");
    });
    var it = selected && info[selected];
    if (it) {
      readoutEl.textContent = it.name + " · " + LEVEL[it.lv] + " · " + events(it.events) + (it.drawn ? "" : " · too small to draw");
      readoutEl.hidden = false;
    } else {
      readoutEl.hidden = true;
    }
    if (globe) globe.pick(it && it.drawn ? selected : null);
  }
  document.addEventListener("keydown", function (ev) { if (ev.key === "Escape" && selected) pick(selected); });

  /* The list and the colours, from one copy of the data */
  function show(d) {
    data = d;
    info = {};
    var rows = d.rows.filter(function (r) {
      return Array.isArray(r) && typeof r[0] === "string" && typeof r[1] === "string" && isFinite(r[2]);
    });
    var levels = {};
    rows.forEach(function (r) {
      var lv = r[2] >= STRONG ? 3 : r[2] >= MEDIUM ? 2 : 1;
      info[r[0]] = { name: r[1], events: r[3] | 0, lv: lv, drawn: !!(globe && globe.has(r[0])) };
      levels[r[0]] = lv;
    });
    if (globe) globe.levels(levels);
    if (selected && !info[selected]) { selected = null; readoutEl.hidden = true; if (globe) globe.pick(null); }

    rowsEl.textContent = "";
    rows.forEach(function (r, i) {
      var it = info[r[0]];
      var li = el("li");
      var b = el("button", "row");
      b.type = "button";
      b.setAttribute("data-code", r[0]);
      b.setAttribute("aria-pressed", r[0] === selected ? "true" : "false");
      b.setAttribute("aria-label", it.name + ", " + LEVEL[it.lv].toLowerCase() + " signal, " + events(it.events));
      b.appendChild(el("span", "rk", (i < 9 ? "0" : "") + (i + 1)));
      var name = el("span", "nm", it.name);
      if (!it.drawn) name.appendChild(el("span", "sub", "Too small to draw"));
      b.appendChild(name);
      var meta = el("span", "meta", events(it.events));
      var lv = el("span", "lv lv" + it.lv);
      lv.appendChild(el("span")); lv.appendChild(el("span")); lv.appendChild(el("span"));
      meta.appendChild(lv);
      b.appendChild(meta);
      b.addEventListener("click", function () { pick(r[0]); });
      li.appendChild(b);
      if (i >= FIRST && !open) li.hidden = true;
      rowsEl.appendChild(li);
    });
    if (!rows.length) rowsEl.appendChild(el("li", "dim", "No outages were detected in the last 24 hours."));
    moreEl.textContent = "Show all " + rows.length;
    moreEl.hidden = open || rows.length <= FIRST;
    problemEl.hidden = true;
    canvas.setAttribute("aria-label", "A globe of dots. " + rows.length +
      " countries and territories with outages are red. The list after it has the same information.");
    stamp();
  }
  moreEl.addEventListener("click", function () {
    open = true;
    Array.prototype.forEach.call(rowsEl.children, function (li) { li.hidden = false; });
    moreEl.hidden = true;
  });

  function stamp() {
    if (!data) return;
    var age = Date.now() / 1000 - data.updated;
    var when;
    if (age < 90) when = "just now";
    else if (age < HOUR) when = Math.round(age / 60) + " min ago";
    else if (age < 48 * HOUR) { var h = Math.round(age / HOUR); when = h + (h === 1 ? " hour ago" : " hours ago"); }
    else when = Math.round(age / (24 * HOUR)) + " days ago";
    statusEl.textContent = "Updated " + when + " · " + Object.keys(info).length + " countries and territories";
    statusEl.classList.toggle("live", age < 3 * HOUR);
    statusEl.classList.remove("down");
  }
  setInterval(stamp, 60000);

  function fail() {
    if (data) return;                  // an older copy is still on screen: keep it
    rowsEl.textContent = "";
    moreEl.hidden = true;
    problemEl.hidden = false;
    statusEl.textContent = "The outage list did not load";
    statusEl.classList.remove("live");
    statusEl.classList.add("down");
  }

  /* The copy on the server is renewed by outages-refresh.php: by a scheduled job if there is one, and otherwise
     by the first visitor who finds it over an hour old. The script fetches at most once an hour whoever asks. */
  function load(second) {
    return fetch("outages.json", { cache: "no-store" })
      .then(function (r) { if (!r.ok) throw new Error("status " + r.status); return r.json(); })
      .then(function (d) {
        if (!d || !Array.isArray(d.rows) || !isFinite(d.updated)) throw new Error("unexpected file");
        show(d);
        if (!second && Date.now() / 1000 - d.updated > HOUR) renew();
      })
      .catch(fail);
  }
  function renew() {
    fetch("outages-refresh.php", { method: "POST", cache: "no-store" })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (a) { if (a && a.state === "updated") load(true); })
      .catch(function () {});
  }
  document.getElementById("retry").addEventListener("click", function () {
    problemEl.hidden = true;
    statusEl.textContent = "Loading the latest copy";
    statusEl.classList.remove("down");
    load(false);
  });

  load(false);
})();
