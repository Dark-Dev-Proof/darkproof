// Home page behaviour: the menu, sections that rise into place, the tagline whose words light up, the phone's two
// modes (tappable demo, recordings of the real app), and the install instructions. No scroll listeners:
// IntersectionObserver does the watching.
(function () {
  "use strict";
  var root = document.documentElement;
  root.classList.add("js");
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var $ = function (id) { return document.getElementById(id); };
  var all = function (sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); };
  var noop = function () {};

  // Menu
  var toggle = $("menu-toggle"), menu = $("menu");
  function setMenu(open) {
    menu.classList.toggle("open", open);
    menu.inert = !open;
    toggle.setAttribute("aria-expanded", String(open));
    toggle.setAttribute("aria-label", open ? "Close menu" : "Menu");
  }
  if (toggle && menu) {
    toggle.addEventListener("click", function () { setMenu(toggle.getAttribute("aria-expanded") !== "true"); });
    menu.addEventListener("click", function (e) { if (e.target.closest("a")) setMenu(false); });
    document.addEventListener("keydown", function (e) { if (e.key === "Escape" && menu.classList.contains("open")) { setMenu(false); toggle.focus(); } });
  }

  // The phone: tappable demo, or recordings of the real app (shown only when the page says it has them)
  var screenEl = document.querySelector(".phone-screen"), clip = $("clip");
  var modeDemo = $("mode-demo"), modeReal = $("mode-real"), panelDemo = $("panel-demo"), panelReal = $("panel-real");
  var real = false, visible = true;
  function play() { if (real && visible && !reduce) clip.play().catch(noop); }
  function scene(name) {
    all("[data-scene]").forEach(function (b) { b.setAttribute("aria-pressed", String(b.dataset.scene === name)); });
    clip.poster = "clips/" + name + ".jpg";
    clip.src = "clips/" + name + ".mp4";
    play();
  }
  function nextScene() { // the recordings play one after another, as one reel
    var list = all("[data-scene]"), at = 0;
    list.forEach(function (b, i) { if (b.getAttribute("aria-pressed") === "true") at = i; });
    scene(list[(at + 1) % list.length].dataset.scene);
  }
  function setMode(on) {
    real = on;
    screenEl.classList.toggle("real", on);
    modeDemo.setAttribute("aria-selected", String(!on));
    modeReal.setAttribute("aria-selected", String(on));
    panelDemo.hidden = on;
    panelReal.hidden = !on;
    if (!on) { clip.pause(); return; }
    clip.controls = reduce; // with reduced motion nothing plays by itself
    if (!clip.getAttribute("src")) scene("home"); else play();
  }
  if (clip && modeReal) {
    modeDemo.addEventListener("click", function () { setMode(false); });
    modeReal.addEventListener("click", function () { setMode(true); });
    all("[data-scene]").forEach(function (b) { b.addEventListener("click", function () { scene(b.dataset.scene); }); });
    all("[data-start-demo]").forEach(function (a) { a.addEventListener("click", function () { setMode(false); }); });
    clip.addEventListener("ended", nextScene);
    clip.addEventListener("error", function () { // recordings missing or unplayable: the demo alone
      if (real) setMode(false);
      document.body.removeAttribute("data-clips");
    });
    // The page opens on the real app when it has recordings, unless the visitor asked for less motion
    setMode(document.body.hasAttribute("data-clips") && !reduce);
  }

  // Install instructions: with AI or by hand, Android or iPhone
  var pick = { way: "ai", os: "android" };
  function showHow() {
    all("[data-way]").forEach(function (b) { b.setAttribute("aria-selected", String(b.dataset.way === pick.way)); });
    all("[data-os]").forEach(function (b) { b.setAttribute("aria-selected", String(b.dataset.os === pick.os)); });
    all("[data-how]").forEach(function (p) { p.hidden = p.dataset.how !== pick.way + "-" + pick.os; });
  }
  all("[data-way]").forEach(function (b) { b.addEventListener("click", function () { pick.way = b.dataset.way; showHow(); }); });
  all("[data-os]").forEach(function (b) { b.addEventListener("click", function () { pick.os = b.dataset.os; showHow(); }); });
  all("[data-ca]").forEach(function (b) { // the contract address in the corner: a tap copies the whole address
    var word = b.querySelector("[data-ca-do]"), full = b.querySelector(".ca-full");
    b.addEventListener("click", function () {
      var done = function (ok) {
        word.textContent = ok ? "Copied" : "Select and copy";
        b.classList.toggle("ok", ok);
        setTimeout(function () { word.textContent = "Copy"; b.classList.remove("ok"); }, 1800);
      };
      var select = function () { // no clipboard access: show the whole address selected, so one keystroke copies it
        b.classList.add("all");
        var r = document.createRange(); r.selectNodeContents(full);
        var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
        done(false);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(b.dataset.ca).then(function () { done(true); }, select);
      else select();
    });
  });
  all("[data-copy]").forEach(function (b) {
    b.addEventListener("click", function () {
      var pre = $(b.dataset.copy), done = function (ok) {
        b.textContent = ok ? "Copied" : "Select and copy";
        b.classList.toggle("ok", ok);
        setTimeout(function () { b.textContent = "Copy"; b.classList.remove("ok"); }, 1800);
      };
      var select = function () { // no clipboard access: select the text so one keystroke copies it
        var r = document.createRange(); r.selectNodeContents(pre);
        var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
        done(false);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(pre.textContent).then(function () { done(true); }, select);
      else select();
    });
  });

  // The phone turns a little toward the pointer, like an object on a table, and faces you when you reach for it.
  // Only where there is a pointer that hovers; a touch screen gets a slow sway from the stylesheet instead.
  var stageEl = $("stage"), slotEl = $("phone");
  if (stageEl && slotEl && !reduce && window.matchMedia("(hover: hover) and (pointer: fine)").matches) {
    var aimAt = [0, 0], aimQueued = false;
    var aim = function (ry, rx) {
      aimAt = [ry, rx];
      if (aimQueued) return;
      aimQueued = true;
      requestAnimationFrame(function () {
        aimQueued = false;
        slotEl.style.setProperty("--ry", aimAt[0].toFixed(2) + "deg");
        slotEl.style.setProperty("--rx", aimAt[1].toFixed(2) + "deg");
      });
    };
    stageEl.addEventListener("pointermove", function (e) {
      if (e.target.closest && e.target.closest(".phone")) { aim(0, 0); return; }
      var r = slotEl.getBoundingClientRect();
      var dx = (e.clientX - (r.left + r.width / 2)) / (window.innerWidth / 2);
      var dy = (e.clientY - (r.top + r.height / 2)) / (window.innerHeight / 2);
      aim(Math.max(-1, Math.min(1, dx)) * 14, Math.max(-1, Math.min(1, dy)) * -8);
    });
    stageEl.addEventListener("pointerleave", function () { aim(0, 0); });
  }

  // The globe in "How it travels": the land as dots, with points of light that come and go
  var globeEl = $("home-globe");
  if (globeEl && window.DPGlobe) window.DPGlobe(globeEl, { sparks: 26 });

  if (!("IntersectionObserver" in window)) { root.classList.remove("js"); return; }

  // Recordings pause while the phone is off screen
  var slot = $("phone");
  if (slot && clip) new IntersectionObserver(function (entries) {
    visible = entries[0].isIntersecting;
    if (visible) play(); else clip.pause();
  }, { threshold: 0.2 }).observe(slot);

  // Sections rise into place, once
  var seen = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) {
      if (!e.isIntersecting) return;
      e.target.classList.add("in");
      seen.unobserve(e.target);
    });
  }, { rootMargin: "0px 0px -10% 0px" });
  all(".reveal").forEach(function (el) { seen.observe(el); });

  // Tagline: each word lights up when it crosses a line two thirds of the way down the screen
  var tagline = $("tagline");
  if (tagline && !reduce) {
    var words = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting) return;
        e.target.classList.add("on");
        words.unobserve(e.target);
      });
    }, { rootMargin: "0px 0px -34% 0px" });
    var out = document.createDocumentFragment(), n = 0;
    Array.prototype.slice.call(tagline.childNodes).forEach(function (node) {
      if (node.nodeType !== 3) { out.appendChild(node); return; }
      node.textContent.split(/(\s+)/).forEach(function (part) {
        if (!part.trim()) { out.appendChild(document.createTextNode(part)); return; }
        var w = document.createElement("span");
        w.className = "w"; w.textContent = part;
        w.style.transitionDelay = (n++ % 5) * 60 + "ms"; // words on one line follow each other, not all at once
        out.appendChild(w);
        words.observe(w);
      });
    });
    tagline.textContent = "";
    tagline.appendChild(out);
  }
})();
