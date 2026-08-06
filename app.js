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
  var scroll = 0;
  var earthR = 0;

  /* simplified real-world continental polygons: [lat, lon] vertices in degrees.
     Point-in-polygon decides whether a particle is land or open ocean. */
  var POLYGONS = [
    /* africa */
    [[37, 10], [32, 25], [22, 37], [12, 44], [10, 52], [4, 48], [0, 42], [-5, 40], [-12, 40], [-20, 36], [-27, 33], [-34, 20], [-35, 18], [-30, 15], [-25, 14], [-20, 12], [-15, 12], [-10, 13], [-5, 8], [5, 5], [10, -15], [15, -17], [20, -17], [25, -12], [30, -10], [32, -5], [35, 5], [37, 10]],
    /* eurasia */
    [[70, 25], [72, 55], [76, 100], [70, 140], [65, 170], [60, 165], [55, 160], [52, 155], [45, 145], [43, 132], [38, 128], [35, 130], [25, 120], [20, 110], [15, 108], [10, 105], [8, 100], [12, 95], [15, 92], [12, 80], [8, 75], [10, 65], [15, 60], [8, 55], [12, 45], [15, 40], [20, 35], [25, 35], [30, 30], [35, 30], [38, 25], [42, 28], [45, 30], [48, 30], [52, 20], [55, 10], [58, 5], [62, 0], [65, 0], [68, 10], [70, 25]],
    /* north america */
    [[72, -60], [70, -90], [68, -120], [65, -140], [60, -160], [58, -155], [55, -165], [50, -178], [48, -170], [45, -160], [42, -150], [40, -140], [38, -130], [37, -122], [35, -120], [32, -117], [30, -112], [27, -110], [24, -110], [22, -105], [20, -100], [18, -95], [15, -90], [12, -85], [10, -84], [8, -80], [10, -76], [12, -72], [15, -70], [18, -68], [20, -70], [25, -72], [28, -76], [30, -80], [32, -82], [35, -78], [38, -75], [40, -73], [42, -70], [45, -70], [48, -68], [50, -65], [52, -58], [55, -60], [58, -65], [60, -65], [63, -65], [65, -60], [68, -62], [70, -65], [72, -60]],
    /* south america */
    [[12, -70], [10, -75], [5, -78], [0, -80], [-5, -82], [-10, -78], [-15, -75], [-20, -70], [-25, -70], [-30, -68], [-35, -62], [-40, -62], [-42, -64], [-45, -65], [-48, -68], [-52, -70], [-54, -68], [-55, -66], [-52, -62], [-48, -58], [-45, -55], [-40, -50], [-35, -48], [-30, -50], [-25, -52], [-20, -50], [-15, -45], [-10, -40], [-8, -35], [-5, -32], [-5, -28], [0, -30], [5, -30], [8, -32], [10, -35], [12, -38], [12, -42], [10, -45], [8, -50], [10, -55], [12, -60], [12, -65], [12, -70]],
    /* australia */
    [[-12, 130], [-15, 135], [-20, 145], [-25, 150], [-30, 153], [-35, 150], [-38, 145], [-38, 140], [-35, 135], [-30, 130], [-25, 128], [-20, 125], [-15, 125], [-12, 128], [-12, 130]],
    /* greenland */
    [[83, -35], [80, -25], [75, -20], [70, -20], [65, -35], [62, -42], [60, -45], [62, -52], [65, -55], [68, -60], [70, -65], [72, -62], [75, -58], [78, -55], [80, -50], [82, -40], [83, -35]],
    /* antarctica */
    [[-60, -60], [-65, -75], [-68, -90], [-70, -110], [-72, -130], [-74, -150], [-76, -170], [-77, 180], [-78, 160], [-77, 140], [-76, 120], [-74, 100], [-72, 80], [-70, 60], [-68, 40], [-65, 25], [-62, 10], [-60, 0], [-58, -15], [-56, -30], [-58, -45], [-60, -60]]
  ];

  /* ── point-cloud earth: Fibonacci-sphere particles ── */
  var GOLDEN = Math.PI * (3 - Math.sqrt(5));
  var particles = [];
  var PARTICLE_N = 2400;

  function inLand(lat, lon) {
    var i, j, poly, lati, loni, latj, lonj, xi, xj, inside;
    for (var p = 0; p < POLYGONS.length; p++) {
      poly = POLYGONS[p];
      inside = false;
      for (i = 0, j = poly.length - 1; i < poly.length; j = i++) {
        lati = poly[i][0]; loni = poly[i][1];
        latj = poly[j][0]; lonj = poly[j][1];
        while (lonj - loni > 180) lonj -= 360;
        while (lonj - loni < -180) lonj += 360;
        xi = loni;
        if (lon - loni > 180) xi += 360;
        else if (lon - loni < -180) xi -= 360;
        xj = lonj;
        if (xi - xj > 180) xj += 360;
        else if (xj - xi > 180) xi += 360;
        if ((lati > lat) !== (latj > lat) && lon < (xj - xi) * (lat - lati) / (latj - lati) + xi) inside = !inside;
      }
      if (inside) return true;
    }
    return false;
  }

  function buildParticles(n) {
    particles = [];
    /* random regions: each particle joins the nearest of N region centres;
       every region breathes as one, regions are phase-offset — the sphere
       lights up and dims in random patches */
    regions = [];
    var rng = mulberry32(987654321);
    for (var k = 0; k < 10; k++) {
      var yy = 1 - 2 * rng();
      regions.push({
        lat: Math.asin(yy) * 180 / Math.PI,
        lon: rng() * 360 - 180,
        ph: rng() * Math.PI * 2,
        fr: 0.7 + rng() * 0.6
      });
    }
    for (var i = 0; i < n; i++) {
      var y = 1 - 2 * (i + 0.5) / n;
      var r = Math.sqrt(Math.max(0, 1 - y * y));
      var lat = Math.asin(y) * 180 / Math.PI;
      var lon = (i * GOLDEN * 180 / Math.PI) % 360;
      particles.push({
        lat: lat,
        lon: lon,
        land: inLand(lat, lon),
        region: regionOf(lat, lon)
      });
    }
  }

  var regions = [];

  function regionOf(lat, lon) {
    var best = 0, bestD = 1e9, k, d;
    for (k = 0; k < regions.length; k++) {
      var rr = regions[k];
      var dlon = ((lon - rr.lon + 540) % 360) - 180;
      d = Math.abs(Math.sin((lat - rr.lat) * Math.PI / 180)) + Math.abs(Math.sin(dlon * Math.PI / 180));
      if (d < bestD) { bestD = d; best = k; }
    }
    return best;
  }

  /* ── particle contour lines (Endfield-style): glowing particles
         drifting along elliptical contour rings near the hero floor ── */

  var CONTOUR_GROUPS = [
    { cx: 0.22, cy: 1.02, rx: [0.20, 0.15, 0.105, 0.065], ry: [0.095, 0.071, 0.05, 0.031] },
    { cx: 0.76, cy: 1.02, rx: [0.17, 0.13, 0.09, 0.056], ry: [0.081, 0.062, 0.043, 0.027] },
    { cx: 0.52, cy: 1.02, rx: [0.095, 0.068, 0.046], ry: [0.045, 0.033, 0.022] }
  ];
  var contourParticles = [];
  var contourLines = [];

  function buildContourParticles() {
    contourParticles = [];
    contourLines = [];
    var base = Math.min(W, H);
    CONTOUR_GROUPS.forEach(function (g, gi) {
      for (var li = 0; li < g.rx.length; li++) {
        var n = 22 + li * 2;
        var rx = base * g.rx[li];
        var ry = base * g.ry[li] * 0.55;
        contourLines.push({
          gx: W * g.cx,
          gy: H * g.cy,
          rx: rx,
          ry: ry,
          alpha: 0.05 + li * 0.02
        });
        for (var i = 0; i < n; i++) {
          contourParticles.push({
            gx: W * g.cx,
            gy: H * g.cy,
            rx: rx,
            ry: ry,
            theta: i / n * Math.PI * 2 + gi * 0.6 + li * 0.15,
            speed: 0.05 + ((i + li) % 4) * 0.012,
            size: 1.0 + ((i + li) % 3) * 0.35,
            bright: 0.45 + ((i * 7 + li * 13) % 10) / 10 * 0.55
          });
        }
      }
    });
  }

  function drawContourParticles(t) {
    var i, p, x, y, a;
    for (i = 0; i < contourLines.length; i++) {
      p = contourLines[i];
      ctx.globalAlpha = p.alpha;
      ctx.strokeStyle = 'rgba(158,182,214,0.9)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.ellipse(p.gx, p.gy, p.rx, p.ry, 0, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
    for (i = 0; i < contourParticles.length; i++) {
      p = contourParticles[i];
      p.theta += p.speed * 0.016;
      x = p.gx + Math.cos(p.theta) * p.rx;
      y = p.gy + Math.sin(p.theta) * p.ry;
      if (y > H + 6 || y < -6) continue;
      a = p.bright * (0.3 + 0.7 * Math.abs(Math.sin(p.theta)));
      ctx.globalAlpha = a * 0.45;
      ctx.fillStyle = 'rgba(190,212,240,0.85)';
      ctx.beginPath(); ctx.arc(x, y, p.size + 1.2, 0, Math.PI * 2); ctx.fill();
      ctx.globalAlpha = a * 0.85;
      ctx.fillStyle = '#d8e6f8';
      ctx.beginPath(); ctx.arc(x, y, p.size, 0, Math.PI * 2); ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  function drawEarth(t, cx, cy, R, rotRad) {
    if (!ctx || R < 4) return;

    /* atmosphere glow (outer) — warm moonlight */
    var glow = ctx.createRadialGradient(cx, cy, R * 0.9, cx, cy, R * 1.5);
    glow.addColorStop(0, 'rgba(212,224,240,0.20)');
    glow.addColorStop(0.55, 'rgba(212,224,240,0.07)');
    glow.addColorStop(1, 'rgba(212,224,240,0)');
    ctx.fillStyle = glow;
    ctx.beginPath(); ctx.arc(cx, cy, R * 1.5, 0, Math.PI * 2); ctx.fill();

    /* subtle sphere base so the point cloud reads as a ball */
    var base = ctx.createRadialGradient(cx - R * 0.4, cy - R * 0.45, R * 0.1, cx, cy, R);
    base.addColorStop(0, 'rgba(58,64,78,0.5)');
    base.addColorStop(0.7, 'rgba(40,45,56,0.4)');
    base.addColorStop(1, 'rgba(26,30,38,0.3)');
    ctx.fillStyle = base;
    ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.fill();

    /* particles: back hemisphere first (dim), then front (bright) */
    var px, py, z, cosLat, l, s, col;
    var i, p;
    for (var pass = 0; pass < 2; pass++) {
      for (i = 0; i < particles.length; i++) {
        p = particles[i];
        l = p.lon * Math.PI / 180 + rotRad;
        cosLat = Math.cos(p.lat * Math.PI / 180);
        px = cx + R * Math.sin(l) * cosLat;
        py = cy - R * Math.sin(p.lat * Math.PI / 180);
        z = Math.cos(l) * cosLat;
        if (pass === 0) {
          if (z >= 0) continue;
          col = p.land ? 'rgba(120,128,146,0.20)' : 'rgba(84,92,106,0.10)';
          s = p.land ? 1.5 : 0.9;
        } else {
          if (z <= 0) continue;
          /* random-region breathing: the whole region shares one phase, so
             patches of the sphere light up and dim together */
          var rg = regions[p.region];
          var pulse = 0.5 + 0.5 * Math.sin(t * rg.fr + rg.ph);
          if (p.land) {
            /* continents: big bright dots that read as landmasses */
            col = 'rgba(240,246,255,0.98)';
            s = 2.4 * (0.85 + 0.35 * pulse);
            if (pulse > 0.7) {
              ctx.globalAlpha = (0.20 + 0.80 * z) * (pulse - 0.7) * 0.6;
              ctx.fillStyle = '#eef4ff';
              ctx.beginPath(); ctx.arc(px, py, s * 2.1, 0, Math.PI * 2); ctx.fill();
            }
            ctx.globalAlpha = (0.20 + 0.80 * z) * (0.30 + 0.70 * pulse);
          } else {
            /* ocean: barely-there dots so only the plates stand out */
            col = 'rgba(112,124,142,0.6)';
            s = 0.9 * (0.9 + 0.2 * pulse);
            ctx.globalAlpha = (0.20 + 0.80 * z) * (0.08 + 0.06 * pulse);
          }
        }
        ctx.fillStyle = col;
        ctx.fillRect(px - s / 2, py - s / 2, s, s);
        ctx.globalAlpha = 1;
      }
    }

    /* day highlight (light from top-left) */
    var shine = ctx.createRadialGradient(cx - R * 0.55, cy - R * 0.6, R * 0.1, cx - R * 0.2, cy - R * 0.2, R * 1.6);
    shine.addColorStop(0, 'rgba(240,246,255,0.15)');
    shine.addColorStop(0.5, 'rgba(240,246,255,0.05)');
    shine.addColorStop(1, 'rgba(240,246,255,0)');
    ctx.fillStyle = shine;
    ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.fill();

    /* rim — soft moonlight edge */
    ctx.strokeStyle = 'rgba(200,215,240,0.20)';
    ctx.lineWidth = 1;
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
    buildParticles(reduceMotion ? 800 : Math.min(3400, Math.round(W * H / 700)));
    buildContourParticles();
    var rng = mulberry32(20260207);
    stars = [];
    var n = reduceMotion ? 40 : Math.round(W * H / 14000);
    for (var i = 0; i < n; i++) {
      stars.push({
        x: rng() * W, y: rng() * H,
        size: rng() < 0.85 ? 1 : 1.6,
        a: 0.25 + rng() * 0.6,
        tw: 1.2 + rng() * 2.8,
        phase: rng() * Math.PI * 2
      });
    }
  }

  var rotRad = 0;

  function frame() {
    var now = performance.now() / 1000;
    rotRad += 0.0015; /* ~70 s per revolution — barely-there lunar drift */

    if (!canvas) return;
    var cx = W / 2;
    var cy = H * 0.40 + scroll * 0.12;
    var R = earthR * (1 + Math.min(scroll / 1200, 0.18));

    ctx.clearRect(0, 0, W, H);
    drawStars(0.8 - Math.min(scroll / 900, 0.45));
    drawEarth(now, cx, cy, R, rotRad);
    drawContourParticles(now);

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
