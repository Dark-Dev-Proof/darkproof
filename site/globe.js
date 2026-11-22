/* A globe of dots, drawn on a canvas with no library. The land comes from outage-map.js.
   The outages page uses it with countries in red, turns it to the country picked in the list and can unroll it into
   a flat map. The home page uses it with the land only and points of light that come and go.

     var globe = DPGlobe(canvas, { sparks: 26 });
     globe.levels({ IT: 2, ... })   red in three strengths (1 weak, 2 medium, 3 strong); anything else is plain land
     globe.pick("IT")               turn to a country and make it stand out; pick(null) lets go
     globe.view("map")              unroll into a flat map; view("globe") rolls it back
     globe.has("IT")                is this country drawn at this scale? */
(function () {
  "use strict";

  window.DPGlobe = function (canvas, opt) {
    var MAP = window.DP_MAP;
    opt = opt || {};
    if (!MAP || !canvas || !canvas.getContext) return null;
    var ctx = canvas.getContext("2d");
    var RAD = Math.PI / 180;
    var LAND = "#616161", RED = "#FF453B", LIGHT = "#FFFFFF";
    var SPARK = 1500;                    // how long a point of light lasts, in milliseconds
    var still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    /* The dots: a place on the globe (x, y, z) and a place on the flat map (fx, fy), in radians */
    var dots = [], centre = {};
    Object.keys(MAP.c).forEach(function (code) {
      var a = MAP.c[code].split(" "), sx = 0, sy = 0, sz = 0;
      for (var i = 0; i < a.length; i += 2) {
        var row = +a[i];
        var lat = (MAP.lat0 + row * MAP.step) * RAD;
        var lon = (-180 + (+a[i + 1] + 0.5) * 360 / MAP.per[row]) * RAD;
        var cl = Math.cos(lat);
        var d = { x: cl * Math.sin(lon), y: Math.sin(lat), z: cl * Math.cos(lon), fx: lon * cl, fy: lat, code: code, lv: 0, lit: 0 };
        dots.push(d);
        sx += d.x; sy += d.y; sz += d.z;
      }
      centre[code] = { lon: Math.atan2(sx, sz), lat: Math.atan2(sy, Math.sqrt(sx * sx + sz * sz)) };
    });

    var yaw = 0.35, pitch = 0.3;         // the point of the globe that faces us (longitude, latitude)
    var flat = 0, view = "globe";        // 0 = globe, 1 = map
    var selected = null;
    var W = 0, H = 0, dpr = 1;
    var anim = null, raf = 0, last = 0, resumeAt = 0;
    var dragging = false, px = 0, py = 0;
    var onScreen = true, pulsing = false, lights = 0;

    function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }

    function size() {
      var box = canvas.getBoundingClientRect();
      if (!box.width || !box.height) return;
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      W = box.width; H = box.height;
      canvas.width = Math.round(W * dpr);
      canvas.height = Math.round(H * dpr);
      draw(last);
    }

    function draw(now) {
      if (!W) return;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, W, H);
      var R = Math.min(W, H) * 0.46, cx = W / 2, cy = H / 2;
      var k = Math.min(W * 0.96, H * 0.96 * 2) / (2 * Math.PI);   // flat map: pixels per radian
      var fcy = cy + 14 * RAD * k;                                // the land runs from 55 south to 83 north
      var cw = Math.cos(yaw), sw = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
      var t = flat, g = 1 - flat;
      var rG = Math.max(0.9, R * MAP.step * RAD * 0.21), rF = Math.max(0.6, k * MAP.step * RAD * 0.3);
      var r0 = rG * g + rF * t;
      var beat = still ? 1 : 0.7 + 0.3 * Math.sin(now / 420);

      if (g > 0.01) {
        ctx.beginPath();
        ctx.arc(cx, cy, R + 2, 0, 6.2832);
        ctx.strokeStyle = "rgba(255,255,255," + (0.12 * g).toFixed(3) + ")";
        ctx.lineWidth = 1;
        ctx.stroke();
      }

      var colour = "";
      for (var pass = 0; pass < 2; pass++) {              // the far side first, so the near side paints over it
        for (var i = 0; i < dots.length; i++) {
          var d = dots[i];
          var x1 = d.x * cw - d.z * sw, z1 = d.x * sw + d.z * cw;
          var y2 = d.y * cp - z1 * sp, z2 = d.y * sp + z1 * cp;
          if ((z2 >= 0) !== (pass === 1)) continue;
          var depth = z2 >= 0 ? 0.5 + 0.5 * z2 : 0.16;
          var a = depth * g + t, r = r0, want = LAND;
          if (d.lv) {
            want = RED;
            if (selected) {
              if (d.code === selected) { r = r0 * 1.8; } else { a *= 0.4; r = r0 * 1.15; }
            } else if (d.lv === 3) { a *= beat; r = r0 * 1.5; }
            else if (d.lv === 2) { a *= 0.75; r = r0 * 1.3; }
            else { a *= 0.5; r = r0 * 1.15; }
          } else if (d.lit) {
            var age = (now - d.lit) / SPARK;
            if (age >= 1 || age < 0) { d.lit = 0; lights--; a *= 0.9; }
            else if (z2 >= 0) { var glow = Math.sin(Math.PI * age); want = LIGHT; a = a * 0.9 + (1 - a * 0.9) * glow; r = r0 * (1 + 0.7 * glow); }
            else { a *= 0.9; }
          } else { a *= 0.9; }
          var sx = (cx + R * x1) * g + (cx + d.fx * k) * t;
          var sy = (cy - R * y2) * g + (fcy - d.fy * k) * t;
          if (want !== colour) { colour = want; ctx.fillStyle = want; }
          ctx.globalAlpha = a > 1 ? 1 : a;
          if (r < 1.1) { ctx.fillRect(sx - r, sy - r, r * 2, r * 2); }
          else { ctx.beginPath(); ctx.arc(sx, sy, r, 0, 6.2832); ctx.fill(); }
        }
      }
      ctx.globalAlpha = 1;
    }

    /* One loop, running only while something moves and the globe is on screen */
    function frame(now) {
      raf = 0;
      var dt = last ? Math.min(now - last, 64) : 16;
      last = now;
      var moving = false;
      if (anim) {
        var p = anim.dur ? clamp((now - anim.t0) / anim.dur, 0, 1) : 1;
        var e = 1 - Math.pow(1 - p, 5);                   // the site's curve, cubic-bezier(0.22, 1, 0.36, 1)
        yaw = anim.y0 + (anim.y1 - anim.y0) * e;
        pitch = anim.p0 + (anim.p1 - anim.p0) * e;
        flat = anim.f0 + (anim.f1 - anim.f0) * e;
        if (p < 1) moving = true; else anim = null;
      } else if (!still && !dragging && !selected && view === "globe" && now > resumeAt) {
        yaw += dt * 0.00012;                              // one turn in about 52 seconds
        moving = true;
      }
      if (opt.sparks && !still && lights < opt.sparks && Math.random() < 0.25) {
        var pickOne = dots[(Math.random() * dots.length) | 0];
        if (!pickOne.lit && !pickOne.lv) { pickOne.lit = now; lights++; }
      }
      draw(now);
      if (moving || dragging || (!still && (opt.sparks || (pulsing && !selected)))) ask();
    }
    function ask() { if (!raf && onScreen && !document.hidden) raf = requestAnimationFrame(frame); }
    function go(to, ms) {
      anim = { t0: performance.now(), dur: still ? 0 : ms, y0: yaw, p0: pitch, f0: flat,
               y1: to.yaw === undefined ? yaw : to.yaw, p1: to.pitch === undefined ? pitch : to.pitch,
               f1: to.flat === undefined ? flat : to.flat };
      ask();
    }

    /* Turning the globe by hand. Up and down strokes on a touch screen still scroll the page. */
    canvas.addEventListener("pointerdown", function (ev) {
      if (view !== "globe") return;
      dragging = true; px = ev.clientX; py = ev.clientY; anim = null;
      canvas.classList.add("drag");
      if (canvas.setPointerCapture) canvas.setPointerCapture(ev.pointerId);
      ask();
    });
    canvas.addEventListener("pointermove", function (ev) {
      if (!dragging) return;
      var s = 1 / (Math.min(W, H) * 0.46);
      yaw -= (ev.clientX - px) * s;
      pitch = clamp(pitch + (ev.clientY - py) * s, -1.2, 1.2);
      px = ev.clientX; py = ev.clientY;
      ask();
    });
    function drop() {
      if (!dragging) return;
      dragging = false;
      canvas.classList.remove("drag");
      resumeAt = performance.now() + 5000;
      ask();
    }
    canvas.addEventListener("pointerup", drop);
    canvas.addEventListener("pointercancel", drop);

    if (window.ResizeObserver) new ResizeObserver(size).observe(canvas); else window.addEventListener("resize", size);
    if (window.IntersectionObserver) {
      new IntersectionObserver(function (list) { onScreen = list[0].isIntersecting; ask(); }).observe(canvas);
    }
    document.addEventListener("visibilitychange", ask);
    size();
    ask();

    return {
      has: function (code) { return !!centre[code]; },
      levels: function (byCode) {
        pulsing = false;
        dots.forEach(function (d) {
          d.lv = byCode[d.code] || 0;
          if (d.lv === 3) pulsing = true;
        });
        ask();
      },
      pick: function (code) {
        selected = code || null;
        if (selected && centre[selected] && view === "globe") {
          var c = centre[selected];
          go({ yaw: c.lon + 2 * Math.PI * Math.round((yaw - c.lon) / (2 * Math.PI)), pitch: clamp(c.lat, -1.2, 1.2) }, 900);
        } else if (!selected) {
          resumeAt = performance.now() + 2000;
        }
        ask();
      },
      view: function (name) {
        view = name === "map" ? "map" : "globe";
        canvas.classList.toggle("flat", view === "map");
        go({ flat: view === "map" ? 1 : 0 }, 900);
      }
    };
  };
})();
