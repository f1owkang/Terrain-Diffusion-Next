/* ============================================================
   Terrain Diffusion Next — site animations
   rotating earth (canvas) + starfield + scroll reveals
   ============================================================ */

(function () {
  'use strict';

  var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ── deterministic rng (stable across reloads) ── */
  function mulberry32(seed) {
    var a = seed >>> 0;
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  /* ══════════════════════════════════════════════════════════
     EARTH
     ══════════════════════════════════════════════════════════ */

  var canvas = document.getElementById('earth-canvas');
  var ctx = canvas ? canvas.getContext('2d') : null;
  var W = 0, H = 0, DPR = 1;
  var stars = [];
  var mouseX = 0, mouseY = 0, targetMX = 0, targetMY = 0;
  var scroll = 0;
  var earthR = 0;

  /* continental blobs: {lat, lon, dlat, dlon} in degrees */
  var BLOBS = [
    { lat: 18, lon: 18, dlat: 32, dlon: 34 },   /* africa        */
    { lat: 52, lon: 100, dlat: 30, dlon: 48 },  /* eurasia       */
    { lat: 8, lon: 80, dlat: 18, dlon: 26 },
    { lat: -24, lon: 134, dlat: 28, dlon: 38 }, /* australia     */
    { lat: 38, lon: -98, dlat: 32, dlon: 44 },  /* north america */
    { lat: -14, lon: -58, dlat: 34, dlon: 28 }, /* south america */
    { lat: -76, lon: 40, dlat: 16, dlon: 60 },  /* antarctica    */
    { lat: 72, lon: -40, dlat: 14, dlon: 46 },  /* greenland     */
    { lat: 25, lon: -150, dlat: 16, dlon: 24 }  /* pacific isle  */
  ];

  function project(lon, lat, rotRad, R) {
    var l = lon * Math.PI / 180 + rotRad;
    var p = lat * Math.PI / 180;
    var x = Math.sin(l) * Math.cos(p);
    var z = Math.cos(l) * Math.cos(p);
    return { x: x * R, y: -Math.sin(p) * R, front: z > 0.05 };
  }

  function traceBlob(blob, rotRad, R, cx, cy, pts) {
    var path = new Path2D();
    var first = true;
    for (var k = 0; k <= pts; k++) {
      var t = k / pts * Math.PI * 2;
      var lon = blob.lon + blob.dlon * Math.cos(t);
      var lat = blob.lat + blob.dlat * Math.sin(t);
      var pr = project(lon, lat, rotRad, R);
      if (!pr.front) continue;
      var x = cx + pr.x, y = cy + pr.y;
      if (first) { path.moveTo(x, y); first = false; } else { path.lineTo(x, y); }
    }
    return first ? null : path;
  }

  function traceGraticule(rotRad, R, cx, cy) {
    var paths = { meridians: [], parallels: [] };
    var pr, x, y, p, first;

    for (var lon = -150; lon <= 180; lon += 30) {
      p = new Path2D(); first = true;
      for (var lat = -90; lat <= 90; lat += 6) {
        pr = project(lon, lat, rotRad, R);
        if (!pr.front) { first = true; continue; }
        x = cx + pr.x; y = cy + pr.y;
        if (first) { p.moveTo(x, y); first = false; } else { p.lineTo(x, y); }
      }
      paths.meridians.push(p);
    }
    for (var lat2 = -60; lat2 <= 60; lat2 += 30) {
      p = new Path2D(); first = true;
      for (var lon2 = -180; lon2 <= 180; lon2 += 6) {
        pr = project(lon2, lat2, rotRad, R);
        if (!pr.front) { first = true; continue; }
        x = cx + pr.x; y = cy + pr.y;
        if (first) { p.moveTo(x, y); first = false; } else { p.lineTo(x, y); }
      }
      paths.parallels.push(p);
    }
    return paths;
  }

  function drawEarth(t, cx, cy, R, rotRad) {
    if (!ctx || R < 4) return;

    /* atmosphere glow (outer) */
    var glow = ctx.createRadialGradient(cx, cy, R * 0.92, cx, cy, R * 1.35);
    glow.addColorStop(0, 'rgba(95,178,255,0.20)');
    glow.addColorStop(0.6, 'rgba(95,178,255,0.06)');
    glow.addColorStop(1, 'rgba(95,178,255,0)');
    ctx.fillStyle = glow;
    ctx.beginPath(); ctx.arc(cx, cy, R * 1.35, 0, Math.PI * 2); ctx.fill();

    /* ocean base */
    var ocean = ctx.createRadialGradient(cx - R * 0.45, cy - R * 0.5, R * 0.2, cx, cy, R * 1.2);
    ocean.addColorStop(0, '#1b3a5f');
    ocean.addColorStop(0.55, '#142c4a');
    ocean.addColorStop(1, '#0d1f36');
    ctx.fillStyle = ocean;
    ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.fill();

    /* graticule */
    var grid = traceGraticule(rotRad, R, cx, cy);
    ctx.strokeStyle = 'rgba(140,190,255,0.13)';
    ctx.lineWidth = 1;
    grid.meridians.forEach(function (p) { ctx.stroke(p); });
    grid.parallels.forEach(function (p) { ctx.stroke(p); });

    /* continents */
    for (var i = 0; i < BLOBS.length; i++) {
      var path = traceBlob(BLOBS[i], rotRad, R, cx, cy, 30);
      if (!path) continue;
      ctx.fillStyle = 'rgba(58,110,78,0.92)';
      ctx.fill(path);
      ctx.strokeStyle = 'rgba(90,150,110,0.35)';
      ctx.lineWidth = 1;
      ctx.stroke(path);
    }

    /* day highlight (light from top-left) */
    var shine = ctx.createRadialGradient(cx - R * 0.55, cy - R * 0.6, R * 0.1, cx - R * 0.2, cy - R * 0.2, R * 1.6);
    shine.addColorStop(0, 'rgba(210,235,255,0.16)');
    shine.addColorStop(0.5, 'rgba(210,235,255,0.05)');
    shine.addColorStop(1, 'rgba(210,235,255,0)');
    ctx.fillStyle = shine;
    ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.fill();

    /* rim */
    ctx.strokeStyle = 'rgba(150,200,255,0.28)';
    ctx.lineWidth = 1.2;
    ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.stroke();
  }

  function drawStars(alpha) {
    if (!ctx) return;
    ctx.fillStyle = '#cfe3ff';
    for (var i = 0; i < stars.length; i++) {
      var s = stars[i];
      var tw = 0.55 + 0.45 * Math.sin(s.phase + performance.now() / 1000 * s.tw);
      ctx.globalAlpha = s.a * tw * alpha;
      ctx.fillRect(s.x, s.y, s.size, s.size);
    }
    ctx.globalAlpha = 1;
  }

  function resize() {
    DPR = Math.min(window.devicePixelRatio || 1, 2);
    if (!canvas) return;
    W = window.innerWidth; H = window.innerHeight;
    canvas.width = W * DPR; canvas.height = H * DPR;
    canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    earthR = Math.min(W, H) * 0.42;
    var rng = mulberry32(20260207);
    stars = [];
    var n = reduceMotion ? 40 : Math.round(W * H / 14000);
    for (var i = 0; i < n; i++) {
      stars.push({
        x: rng() * W, y: rng() * H,
        size: rng() < 0.85 ? 1 : 1.6,
        a: 0.25 + rng() * 0.6,
        tw: 0.6 + rng() * 2.2,
        phase: rng() * Math.PI * 2
      });
    }
  }

  var rotRad = 0;

  function frame() {
    var now = performance.now() / 1000;
    rotRad += 0.018; /* rotation speed */

    mouseX += (targetMX - mouseX) * 0.05;
    mouseY += (targetMY - mouseY) * 0.05;

    if (!canvas) return;
    var cx = W / 2 + mouseX * 14;
    var cy = H * 0.40 + mouseY * 10 + scroll * 0.12;
    var R = earthR * (1 + Math.min(scroll / 1200, 0.18));

    ctx.clearRect(0, 0, W, H);
    drawStars(0.8 - Math.min(scroll / 900, 0.45));
    drawEarth(now, cx, cy, R, rotRad);

    var hero = document.getElementById('hero');
    if (hero) {
      var fade = Math.min(scroll / 520, 1);
      hero.style.opacity = String(1 - fade * 0.92);
      hero.style.transform = 'translateY(' + (-scroll * 0.28) + 'px)';
    }
  }

  function loop() {
    frame();
    requestAnimationFrame(loop);
  }

  if (canvas && ctx) {
    resize();
    window.addEventListener('resize', resize);
    window.addEventListener('mousemove', function (e) {
      targetMX = (e.clientX / W - 0.5) * 2;
      targetMY = (e.clientY / H - 0.5) * 2;
    });
    window.addEventListener('scroll', function () {
      scroll = window.scrollY || window.pageYOffset || 0;
      var bar = document.querySelector('.topbar');
      if (bar) bar.classList.toggle('scrolled', scroll > 24);
    }, { passive: true });
    if (reduceMotion) {
      frame();
    } else {
      loop();
    }
  }

  /* ══════════════════════════════════════════════════════════
     SCROLL REVEALS
     ══════════════════════════════════════════════════════════ */

  var revealEls = document.querySelectorAll('.reveal');
  if ('IntersectionObserver' in window && !reduceMotion) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          en.target.classList.add('in');
          io.unobserve(en.target);
        }
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
    revealEls.forEach(function (el) { io.observe(el); });
  } else {
    revealEls.forEach(function (el) { el.classList.add('in'); });
  }
})();
