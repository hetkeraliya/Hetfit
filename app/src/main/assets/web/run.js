/* Run tab — Strava-style activity recording, history, stats and story sharing. Fully offline. */
(function () {
  'use strict';
  const A = window.KestrelAndroidBridge || null;
  const app = () => window.kestrelApp;
  const $ = (id) => document.getElementById(id);
  function h(tag, cls, html) { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
  const pad2 = (n) => String(n).padStart(2, '0');

  const TYPES = [
    { key: 'run', label: 'Run', code: 1, kinds: [16, 17], icon: '🏃' },
    { key: 'walk', label: 'Walk', code: 3, kinds: [32], icon: '🚶' },
    { key: 'ride', label: 'Ride', code: 4, kinds: [128, 129], icon: '🚴' },
    { key: 'hike', label: 'Hike', code: 15, kinds: [1024], icon: '🥾' }
  ];
  const kindType = (k) => TYPES.find((t) => t.kinds.indexOf(k) >= 0);
  const codeType = (c) => TYPES.find((t) => t.code === c) || TYPES[0];

  // ---------- preferences ----------
  const PREF_KEY = 'kestrel.run.prefs';
  let prefs = { type: 'run', voice: true, countdown: true, weight: 0 };
  try { prefs = Object.assign(prefs, JSON.parse(localStorage.getItem(PREF_KEY) || '{}')); } catch (e) {}
  const savePrefs = () => { try { localStorage.setItem(PREF_KEY, JSON.stringify(prefs)); } catch (e) {} };

  // ---------- formatting ----------
  function fmtDur(sec) {
    sec = Math.max(0, Math.round(sec));
    const hh = Math.floor(sec / 3600), mm = Math.floor((sec % 3600) / 60), ss = sec % 60;
    return hh > 0 ? hh + ':' + pad2(mm) + ':' + pad2(ss) : mm + ':' + pad2(ss);
  }
  function fmtPace(secPerKm) {
    if (!secPerKm || !isFinite(secPerKm) || secPerKm > 3600) return '--:--';
    let m = Math.floor(secPerKm / 60), sec = Math.round(secPerKm % 60);
    if (sec === 60) { m++; sec = 0; }
    return m + ':' + pad2(sec);
  }
  const fmtKm = (m) => (m / 1000).toFixed(2);
  const toMs = (v) => (typeof v === 'number' ? v : Date.parse(v));
  const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function dateText(ms) {
    const d = new Date(ms);
    return DOW[d.getDay()] + ' ' + d.getDate() + ' ' + MON[d.getMonth()] + ', ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }
  function dayKey(ms) { const d = new Date(ms); return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function partOfDay(ms) {
    const hr = new Date(ms).getHours();
    return hr >= 5 && hr < 11 ? 'Morning' : hr < 14 ? 'Lunch' : hr < 17 ? 'Afternoon' : hr < 21 ? 'Evening' : 'Night';
  }
  function toast(msg) { if (A && A.showToast) { try { A.showToast(String(msg)); return; } catch (e) {} } console.log(msg); }

  // ---------- activities ----------
  function activities() {
    const ws = (app() && app().state.workouts) || [];
    const out = [];
    ws.forEach((w) => {
      const type = kindType(w.kind);
      const start = toMs(w.start);
      if (!type || !isFinite(start)) return;
      const end = isFinite(toMs(w.end)) ? toMs(w.end) : start + (w.minutes || 0) * 60000;
      const elapsed = Math.max(1, Math.round((end - start) / 1000));
      out.push(Object.assign({}, w, {
        startMs: start, startSec: Math.round(start / 1000), type: type, elapsed: elapsed,
        moving: w.movingSec && w.movingSec > 0 ? w.movingSec : elapsed,
        dist: w.distanceMeters || 0
      }));
    });
    return out.sort((a, b) => b.startMs - a.startMs);
  }
  function titleOf(a) {
    if (a.track || a.source === 'phone') return partOfDay(a.startMs) + ' ' + a.type.label;
    return a.name || a.type.label;
  }
  function avgPace(a) { return a.dist > 50 ? a.moving / (a.dist / 1000) : 0; }
  function kcalOf(a) {
    if (a.calories) return { v: a.calories, est: false };
    if (!(prefs.weight > 0) || a.dist < 100) return null;
    const kmh = (a.dist / 1000) / (a.moving / 3600);
    let met = a.type.key === 'run' ? Math.min(16, Math.max(6, kmh)) : a.type.key === 'walk' ? Math.min(6, Math.max(2.5, kmh * 0.7 + 0.5)) :
      a.type.key === 'ride' ? Math.min(14, Math.max(4, kmh * 0.45)) : 6;
    return { v: Math.round(met * prefs.weight * (a.moving / 3600)), est: true };
  }

  // ---------- track maths ----------
  const trackCache = {};
  function getTrack(startSec) {
    if (trackCache[startSec]) return trackCache[startSec];
    let t = [];
    if (A && A.getRunTrack) { try { t = JSON.parse(A.getRunTrack(startSec)); } catch (e) { t = []; } }
    trackCache[startSec] = t;
    return t;
  }
  function hav(a, b) {
    const R = 6371000, r = Math.PI / 180;
    const dLat = (b[1] - a[1]) * r, dLon = (b[2] - a[2]) * r;
    const x = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * r) * Math.cos(b[1] * r) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.min(1, Math.sqrt(x)));
  }
  function smooth(arr, k) {
    return arr.map((_, i) => { let s = 0, c = 0; for (let j = Math.max(0, i - k); j <= Math.min(arr.length - 1, i + k); j++) { s += arr[j]; c++; } return s / c; });
  }
  function analyze(track) {
    const n = track.length;
    const res = { cum: [0], mt: [0], ascent: 0, descent: 0, maxSpeed: 0, splits: [], dist: 0, moving: 0, alt: [] };
    if (n < 2) return res;
    const alt = smooth(track.map((p) => p[3] || 0), 4);
    res.alt = alt;
    let lastAlt = alt[0];
    for (let i = 1; i < n; i++) {
      const d = hav(track[i - 1], track[i]);
      const dt = track[i][0] - track[i - 1][0];
      res.cum.push(res.cum[i - 1] + d);
      res.mt.push(res.mt[i - 1] + (dt > 15 ? 1 : Math.max(dt, 0)));
      if (dt > 0 && dt <= 15) res.maxSpeed = Math.max(res.maxSpeed, d / dt);
      const delta = alt[i] - lastAlt;
      if (Math.abs(delta) >= 2.5) { if (delta > 0) res.ascent += delta; else res.descent -= delta; lastAlt = alt[i]; }
    }
    res.dist = res.cum[n - 1]; res.moving = res.mt[n - 1];
    let k = 1, prevT = 0, prevI = 0;
    while (res.dist >= k * 1000) {
      let i = res.cum.findIndex((c) => c >= k * 1000);
      if (i <= 0) break;
      const f = (k * 1000 - res.cum[i - 1]) / Math.max(1e-6, res.cum[i] - res.cum[i - 1]);
      const t = res.mt[i - 1] + f * (res.mt[i] - res.mt[i - 1]);
      let hs = 0, hc = 0;
      for (let j = prevI; j <= i; j++) { if (track[j][4] > 30) { hs += track[j][4]; hc++; } }
      res.splits.push({ km: k, sec: t - prevT, dist: 1000, hr: hc ? Math.round(hs / hc) : 0, elev: alt[i] - alt[prevI] });
      prevT = t; prevI = i; k++;
    }
    const rest = res.dist - (k - 1) * 1000;
    if (rest > 80) res.splits.push({ km: k, sec: res.moving - prevT, dist: rest, hr: 0, elev: alt[n - 1] - alt[prevI], partial: true });
    return res;
  }
  function bestTime(an, D) {
    const cum = an.cum, mt = an.mt, n = cum.length;
    if (!n || cum[n - 1] < D) return null;
    let best = Infinity, i = 0;
    for (let j = 1; j < n; j++) {
      if (cum[j] < D) continue;
      const target = cum[j] - D;
      while (i + 1 < n && cum[i + 1] <= target) i++;
      const span = Math.max(1e-6, cum[i + 1] - cum[i]);
      const ti = mt[i] + ((target - cum[i]) / span) * (mt[i + 1] - mt[i]);
      best = Math.min(best, mt[j] - ti);
    }
    return isFinite(best) ? best : null;
  }

  // ---------- route drawing ----------
  function projectTrack(pts, W, H, pad) {
    if (!pts.length) return [];
    let lat0 = 0; pts.forEach((p) => (lat0 += p[1])); lat0 /= pts.length;
    const k = Math.cos((lat0 * Math.PI) / 180);
    const xy = pts.map((p) => [p[2] * k, -p[1]]);
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    xy.forEach((p) => { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]); });
    const w = Math.max(maxX - minX, 1e-7), hh = Math.max(maxY - minY, 1e-7);
    const s = Math.min((W - 2 * pad) / w, (H - 2 * pad) / hh);
    const ox = (W - w * s) / 2 - minX * s, oy = (H - hh * s) / 2 - minY * s;
    return xy.map((p) => [p[0] * s + ox, p[1] * s + oy]);
  }
  function routeSvg(track, opts) {
    opts = opts || {};
    const W = opts.W || 400, H = opts.H || 300, pad = opts.pad == null ? 28 : opts.pad;
    if (!track || track.length < 2) return '';
    const pr = projectTrack(track, W, H, pad);
    let paths = '';
    const stroke = opts.stroke || '#454B5C', sw = opts.sw || 5;
    if (opts.pace) {
      const seg = Math.max(1, Math.floor(pr.length / 90));
      const speeds = [];
      for (let i = 0; i + seg < pr.length; i += seg) {
        const d = hav(track[i], track[i + seg]), dt = Math.max(1, track[i + seg][0] - track[i][0]);
        speeds.push(d / dt);
      }
      const sorted = speeds.slice().sort((a, b) => a - b);
      const lo = sorted[Math.floor(sorted.length * 0.1)] || 0, hi = sorted[Math.floor(sorted.length * 0.9)] || 1;
      let idx = 0;
      for (let i = 0; i + seg < pr.length; i += seg, idx++) {
        const t = Math.min(1, Math.max(0, (speeds[idx] - lo) / Math.max(0.01, hi - lo)));
        const col = 'hsl(' + Math.round(340 - t * 60) + ',' + Math.round(55 + t * 20) + '%,' + Math.round(62 + t * 10) + '%)';
        let d = 'M' + pr[i][0].toFixed(1) + ' ' + pr[i][1].toFixed(1);
        for (let j = i + 1; j <= i + seg && j < pr.length; j++) d += ' L' + pr[j][0].toFixed(1) + ' ' + pr[j][1].toFixed(1);
        paths += '<path d="' + d + '" stroke="' + col + '" stroke-width="' + sw + '" fill="none" stroke-linecap="round" stroke-linejoin="round"/>';
      }
    } else {
      let d = 'M' + pr[0][0].toFixed(1) + ' ' + pr[0][1].toFixed(1);
      for (let i = 1; i < pr.length; i++) d += ' L' + pr[i][0].toFixed(1) + ' ' + pr[i][1].toFixed(1);
      paths = '<path d="' + d + '" stroke="' + stroke + '" stroke-width="' + sw + '" fill="none" stroke-linecap="round" stroke-linejoin="round"/>';
    }
    const a = pr[0], b = pr[pr.length - 1];
    const dots = '<circle cx="' + a[0] + '" cy="' + a[1] + '" r="' + (sw + 2) + '" fill="#4E9F83" stroke="#fff" stroke-width="2"/>' +
      '<circle cx="' + b[0] + '" cy="' + b[1] + '" r="' + (sw + 2) + '" fill="#454B5C" stroke="#fff" stroke-width="2"/>';
    return '<svg viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="xMidYMid meet" xmlns="http://www.w3.org/2000/svg">' + paths + dots + '</svg>';
  }
  function sparkSvg(vals, color) {
    const v = vals.filter((x) => isFinite(x));
    if (v.length < 2) return '';
    const mn = Math.min.apply(null, v), mx = Math.max.apply(null, v), W = 300, H = 70;
    const step = Math.max(1, Math.floor(v.length / 120));
    let d = '', first = true;
    for (let i = 0; i < v.length; i += step) {
      const x = (i / (v.length - 1)) * W, y = H - 6 - ((v[i] - mn) / Math.max(1e-6, mx - mn)) * (H - 12);
      d += (first ? 'M' : ' L') + x.toFixed(1) + ' ' + y.toFixed(1); first = false;
    }
    return '<svg class="rn-spark" viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none"><path d="' + d + '" fill="none" stroke="' + color + '" stroke-width="2.5" stroke-linejoin="round"/></svg>' +
      '<div style="display:flex;justify-content:space-between;font-size:11px;color:var(--muted);font-weight:700"><span>' + Math.round(mn) + '</span><span>' + Math.round(mx) + '</span></div>';
  }

  // ---------- overlay helpers ----------
  function overlay(id, cls) { let o = $(id); if (!o) { o = h('div', 'rn-overlay ' + (cls || '')); o.id = id; document.body.appendChild(o); } return o; }
  function closeOverlay(id) { const o = $(id); if (o) o.classList.remove('open'); }

  // ---------- Run tab (home / activities / stats) ----------
  let subTab = 'home';
  function renderRun() {
    const root = $('run-root');
    if (!root || !app()) return;
    root.innerHTML = '';
    const stack = h('div', 'sheet-stack');
    root.appendChild(stack);

    const tabs = h('div', 'sub-tab-row');
    [['home', 'Start'], ['list', 'Activities'], ['stats', 'Stats']].forEach(([k, label]) => {
      const b = h('button', 'sub-tab-btn' + (subTab === k ? ' active' : ''), label);
      b.type = 'button';
      b.onclick = () => { subTab = k; renderRun(); };
      tabs.appendChild(b);
    });
    stack.appendChild(tabs);

    if (subTab === 'home') renderHome(stack);
    else if (subTab === 'list') renderList(stack);
    else renderStats(stack);
  }

  function renderHome(stack) {
    const acts = activities();
    const live = lastState && lastState.active;
    const typeLabel = codeType(codeOf(prefs.type)).label;

    // 1) Minimal start card
    const hero = h('div', 'glass-card rn-hero rn-hero-clean');
    hero.innerHTML =
      '<div class="rn-hero-row">' +
        '<div class="rn-hero-copy">' +
          '<h2 class="rn-title">' + (live ? 'Resume' : 'Start') + ' ' + typeLabel.toLowerCase() + '</h2>' +
          '<p class="rn-sub">' + (live ? 'Recording now — tap to open' : 'GPS + band HR · offline') + '</p>' +
        '</div>' +
        '<button type="button" class="rn-start" id="rn-start-btn" aria-label="Start">▶</button>' +
      '</div>';
    const chips = h('div', 'rn-chips');
    TYPES.forEach((t) => {
      const c = h('button', 'rn-chip' + (prefs.type === t.key ? ' active' : ''), t.label);
      c.type = 'button';
      c.onclick = () => { prefs.type = t.key; savePrefs(); renderRun(); };
      chips.appendChild(c);
    });
    hero.appendChild(chips);
    // compact options as single quiet row
    const opts = h('div', 'rn-opts rn-opts-compact');
    opts.innerHTML =
      '<label><input type="checkbox" id="rn-o-voice" ' + (prefs.voice ? 'checked' : '') + '> Voice</label>' +
      '<label><input type="checkbox" id="rn-o-cd" ' + (prefs.countdown ? 'checked' : '') + '> Countdown</label>';
    hero.appendChild(opts);
    stack.appendChild(hero);
    hero.querySelector('#rn-start-btn').onclick = () => (live ? openLive() : startActivity());
    hero.querySelector('#rn-o-voice').onchange = (e) => { prefs.voice = e.target.checked; savePrefs(); };
    hero.querySelector('#rn-o-cd').onchange = (e) => { prefs.countdown = e.target.checked; savePrefs(); };

    // 2) One compact week strip (stats + days in one card)
    const now = new Date();
    const ws = new Date(now.getFullYear(), now.getMonth(), now.getDate() - now.getDay()).getTime();
    const week = acts.filter((a) => a.startMs >= ws);
    const km = week.reduce((s, a) => s + a.dist, 0);
    const mv = week.reduce((s, a) => s + (a.dist > 50 ? a.moving : 0), 0);
    const dd = week.reduce((s, a) => s + (a.dist > 50 ? a.dist : 0), 0);
    const days = {}; acts.forEach((a) => (days[dayKey(a.startMs)] = true));
    const st = streak(days);

    const weekCard = h('div', 'glass-card rn-week-card');
    weekCard.innerHTML =
      '<div class="rn-week-stats">' +
        '<div><b>' + (km / 1000).toFixed(1) + '</b><span>km</span></div>' +
        '<div><b>' + fmtPace(dd > 50 ? mv / (dd / 1000) : 0) + '</b><span>pace</span></div>' +
        '<div><b>' + week.length + '</b><span>runs</span></div>' +
        '<div><b>' + st.cur + '</b><span>streak</span></div>' +
      '</div>';
    const wk = h('div', 'rn-week');
    for (let i = 0; i < 7; i++) {
      const d = new Date(ws + i * 86400000);
      const k = dayKey(d.getTime());
      const tdy = dayKey(now.getTime());
      wk.appendChild(h('div', 'rn-day' + (k === tdy ? ' today' : '') + (days[k] ? ' has' : ''),
        '<span>' + 'SMTWTFS'[i] + '</span><i>' + d.getDate() + '</i>'));
    }
    weekCard.appendChild(wk);
    stack.appendChild(weekCard);

    // 3) Recent — only top 3, cleaner empty state
    stack.appendChild(h('div', 'rn-h', 'Recent'));
    stack.appendChild(listEl(acts.slice(0, 3)));
  }
  const codeOf = (key) => (TYPES.find((t) => t.key === key) || TYPES[0]).key;
  function streak(days) {
    let cur = 0, d = new Date();
    if (!days[dayKey(d.getTime())]) d = new Date(d.getTime() - 86400000);
    while (days[dayKey(d.getTime())]) { cur++; d = new Date(d.getTime() - 86400000); }
    const keys = Object.keys(days).sort();
    let best = 0, run = 0, prev = null;
    keys.forEach((k) => {
      const t = Date.parse(k + 'T12:00:00');
      run = prev !== null && Math.round((t - prev) / 86400000) === 1 ? run + 1 : 1;
      best = Math.max(best, run); prev = t;
    });
    return { cur: cur, best: Math.max(best, cur) };
  }

  function listEl(list) {
    const box = h('div', 'rn-list');
    if (!list.length) { box.appendChild(h('div', 'rn-empty', 'No activities yet. Start one above, or sync workouts from your band.')); return box; }
    list.forEach((a) => {
      const it = h('div', 'rn-item');
      const thumb = h('div', 'rn-thumb', a.type.icon);
      const pace = a.type.key === 'ride' ? ((a.dist / 1000) / (a.moving / 3600)).toFixed(1) + ' km/h' : fmtPace(avgPace(a)) + ' /km';
      it.appendChild(thumb);
      it.appendChild(h('div', 'rn-meta',
        '<div class="n">' + titleOf(a) + '</div><div class="d">' + dateText(a.startMs) + '</div>' +
        '<div class="st">' + (a.dist > 50 ? fmtKm(a.dist) + ' km · ' : '') + fmtDur(a.moving) + (a.dist > 50 ? ' · ' + pace : '') + (a.heartRateAvg ? ' · ' + a.heartRateAvg + ' bpm' : '') + '</div>'));
      it.onclick = () => openDetail(a);
      box.appendChild(it);
      if (a.track) {
        setTimeout(() => {
          const svg = routeSvg(getTrack(a.startSec), { W: 100, H: 100, pad: 12, sw: 3.5, stroke: '#454B5C' });
          if (svg) thumb.innerHTML = svg;
        }, 0);
      }
    });
    return box;
  }


  // ---------- GPX import ----------
  function parseGpx(text) {
    const doc = new DOMParser().parseFromString(text, 'application/xml');
    if (doc.getElementsByTagName('parsererror').length) throw new Error('That file is not a valid GPX');
    const nodes = Array.from(doc.getElementsByTagName('trkpt'));
    if (!nodes.length) throw new Error('No track points found (route-only GPX files have no times)');
    let pts = [];
    nodes.forEach((n) => {
      const lat = parseFloat(n.getAttribute('lat')), lon = parseFloat(n.getAttribute('lon'));
      if (!isFinite(lat) || !isFinite(lon)) return;
      let t = NaN, ele = 0, hr = 0;
      Array.from(n.children).forEach((c) => {
        const nm = c.localName;
        if (nm === 'time') t = Date.parse((c.textContent || '').trim());
        else if (nm === 'ele') ele = parseFloat(c.textContent) || 0;
        else if (nm === 'extensions') {
          Array.from(c.getElementsByTagName('*')).forEach((e) => {
            if (e.localName === 'hr' || e.localName === 'heartrate') hr = parseInt(e.textContent, 10) || hr;
          });
        }
      });
      pts.push([isFinite(t) ? Math.round(t / 1000) : 0, lat, lon, Math.round(ele * 10) / 10, hr]);
    });
    if (pts.length < 2) throw new Error('The GPX has fewer than 2 points');
    if (pts.some((p) => !p[0])) throw new Error('The GPX has no timestamps, so pace and duration cannot be worked out');
    pts.sort((a, b) => a[0] - b[0]);
    pts = pts.filter((p, i) => i === 0 || p[0] > pts[i - 1][0]);
    const typeTxt = ((doc.getElementsByTagName('type')[0] || {}).textContent || '').toLowerCase();
    let dist = 0, moving = 0;
    const maxV = /cycl|bik|ride/.test(typeTxt) ? 25 : 12;   // ignore GPS glitches
    for (let i = 1; i < pts.length; i++) {
      const d = hav(pts[i - 1], pts[i]), dt = pts[i][0] - pts[i - 1][0];
      if (dt <= 0 || d / dt > maxV) continue;
      dist += d;
      if (dt <= 60 && d / dt >= 0.5) moving += dt;
    }
    const elapsed = pts[pts.length - 1][0] - pts[0][0];
    if (dist < 50) throw new Error('That GPX covers less than 50 m');
    let type = 1;
    if (/walk/.test(typeTxt)) type = 3; else if (/cycl|bik|ride/.test(typeTxt)) type = 4; else if (/hik/.test(typeTxt)) type = 15;
    else if (!typeTxt) { const sp = dist / Math.max(1, moving || elapsed); type = sp > 7 ? 4 : sp < 2.2 ? 3 : 1; }
    const hrs = pts.map((p) => p[4]).filter((x) => x > 30);
    if (pts.length > 6000) { const step = Math.ceil(pts.length / 6000); const k = pts.filter((p, i) => i % step === 0); if (k[k.length - 1] !== pts[pts.length - 1]) k.push(pts[pts.length - 1]); pts = k; }
    return {
      startSec: pts[0][0], endSec: pts[pts.length - 1][0], type: type, distanceM: Math.round(dist),
      movingSec: Math.round(moving || elapsed), avgHr: hrs.length ? Math.round(hrs.reduce((s, x) => s + x, 0) / hrs.length) : 0,
      maxHr: hrs.length ? Math.max.apply(null, hrs) : 0, points: pts
    };
  }

  function importRow() {
    const row = h('div', 'rn-import');
    row.style.cssText = 'display:flex;justify-content:flex-end;margin:0 0 12px';
    row.innerHTML = '<button class="rn-btn" type="button">Import GPX</button>';
    const inp = document.createElement('input');
    inp.type = 'file'; inp.accept = '.gpx,application/gpx+xml,text/xml,application/xml,application/octet-stream'; inp.style.display = 'none';
    row.appendChild(inp);
    row.firstChild.onclick = () => inp.click();
    inp.onchange = async () => {
      const f = inp.files && inp.files[0]; if (!f) return;
      try {
        if (!A || !A.importGpxRun) throw new Error('GPX import needs the Android app');
        const payload = parseGpx(await f.text());
        const res = String(A.importGpxRun(JSON.stringify(payload)));
        if (res.indexOf('ok:') === 0) { delete trackCache[payload.startSec]; toast('Imported ' + (payload.distanceM / 1000).toFixed(2) + ' km'); }
        else toast(res);
      } catch (e) { toast(e.message || 'Could not import that file'); }
      inp.value = '';
    };
    return row;
  }

  function renderList(stack) {
    stack.appendChild(importRow());
    const acts = activities();
    if (!acts.length) { stack.appendChild(listEl([])); return; }
    let month = '', box = null, mkm = 0, header = null, group = [];
    const flush = () => {
      if (!group.length) return;
      header.querySelector('span:last-child').textContent = (group.reduce((s, a) => s + a.dist, 0) / 1000).toFixed(1) + ' km · ' + group.length + ' activities';
      stack.appendChild(header); stack.appendChild(listEl(group)); group = [];
    };
    acts.forEach((a) => {
      const d = new Date(a.startMs), m = MON[d.getMonth()] + ' ' + d.getFullYear();
      if (m !== month) { flush(); month = m; header = h('div', 'rn-month', '<span>' + m + '</span><span></span>'); }
      group.push(a);
    });
    flush();
  }

  const PR_KEY = 'kestrel.run.pr.v1';
  function renderStats(stack) {
    const acts = activities();
    const runs = acts.filter((a) => a.dist > 50);
    // weekly distance, last 8 weeks
    const now = new Date();
    const ws0 = new Date(now.getFullYear(), now.getMonth(), now.getDate() - now.getDay()).getTime();
    const weeks = [];
    for (let i = 7; i >= 0; i--) {
      const a0 = ws0 - i * 7 * 86400000, a1 = a0 + 7 * 86400000;
      weeks.push({ label: new Date(a0).getDate() + '/' + (new Date(a0).getMonth() + 1), km: runs.filter((a) => a.startMs >= a0 && a.startMs < a1).reduce((s, a) => s + a.dist, 0) / 1000 });
    }
    const mx = Math.max(1, Math.max.apply(null, weeks.map((w) => w.km)));
    stack.appendChild(h('div', 'rn-h', 'Weekly distance'));
    stack.appendChild(h('div', 'rn-bars', weeks.map((w) => '<div><span>' + (w.km ? w.km.toFixed(1) : '') + '</span><em style="height:' + Math.max(3, (w.km / mx) * 78) + 'px"></em><span>' + w.label + '</span></div>').join('')));

    const totalM = runs.reduce((s, a) => s + a.dist, 0), totalT = runs.reduce((s, a) => s + a.moving, 0);
    stack.appendChild(h('div', 'rn-h', 'All time'));
    stack.appendChild(h('div', 'rn-grid3',
      '<div class="rn-tile"><b>' + (totalM / 1000).toFixed(0) + '</b><span>km</span></div>' +
      '<div class="rn-tile"><b>' + Math.round(totalT / 3600) + '</b><span>hours</span></div>' +
      '<div class="rn-tile"><b>' + runs.length + '</b><span>activities</span></div>'));

    // personal records (computed from stored GPS tracks, cached)
    let cache = {};
    try { cache = JSON.parse(localStorage.getItem(PR_KEY) || '{}'); } catch (e) {}
    const best = { k1: null, k5: null, k10: null, half: null, long: null, pace: null };
    runs.filter((a) => a.type.key === 'run').forEach((a) => {
      let c = cache[a.startSec];
      if (!c && a.track) {
        const an = analyze(getTrack(a.startSec));
        c = { k1: bestTime(an, 1000), k5: bestTime(an, 5000), k10: bestTime(an, 10000), half: bestTime(an, 21097.5) };
        cache[a.startSec] = c;
      }
      if (c) ['k1', 'k5', 'k10', 'half'].forEach((k) => { if (c[k] && (!best[k] || c[k] < best[k].t)) best[k] = { t: c[k], a: a }; });
      if (!best.long || a.dist > best.long.v) best.long = { v: a.dist, a: a };
    });
    try { localStorage.setItem(PR_KEY, JSON.stringify(cache)); } catch (e) {}
    stack.appendChild(h('div', 'rn-h', 'Personal records'));
    const pr = h('div', 'rn-list');
    const rows = [['Fastest 1 km', best.k1, (b) => fmtDur(b.t)], ['Fastest 5 km', best.k5, (b) => fmtDur(b.t)], ['Fastest 10 km', best.k10, (b) => fmtDur(b.t)],
      ['Fastest half marathon', best.half, (b) => fmtDur(b.t)], ['Longest run', best.long, (b) => fmtKm(b.v) + ' km']];
    let any = false;
    rows.forEach(([label, b, f]) => {
      if (!b) return; any = true;
      const r = h('div', 'rn-pr', '<div>' + label + '<br><span>' + dateText(b.a.startMs) + '</span></div><div>' + f(b) + '</div>');
      r.style.cursor = 'pointer'; r.onclick = () => openDetail(b.a); pr.appendChild(r);
    });
    if (!any) pr.appendChild(h('div', 'rn-empty', 'Records appear after you record GPS runs of 1 km or more.'));
    stack.appendChild(pr);

    stack.appendChild(h('div', 'rn-h', 'Settings'));
    const set = h('div', 'rn-card');
    set.innerHTML = '<h4>Body weight for calorie estimates</h4><input class="mo-input" id="rn-weight" type="number" inputmode="decimal" placeholder="kg (optional)" value="' + (prefs.weight || '') + '">' +
      '<p class="mo-note">Without a weight no calories are shown for phone-recorded activities. Calories are always labelled as estimates.</p>';
    set.querySelector('#rn-weight').onchange = (e) => { prefs.weight = Math.max(0, Number(e.target.value) || 0); savePrefs(); toast('Saved'); };
    stack.appendChild(set);
  }

  // ---------- recording flow ----------
  let lastState = null;
  let livePts = [];
  let finishing = false;

  function startActivity() {
    if (lastState && lastState.active) { openLive(); return; }
    const type = codeType(TYPES.find((t) => t.key === prefs.type).code);
    const go = () => {
      finishing = false; livePts = [];
      if (A && A.startRun) {
        try { A.startRun(type.code, !!prefs.voice); } catch (e) { console.warn(e); }
      }
      openLive(true);
    };
    if (!prefs.countdown) { go(); return; }
    let cd = $('rn-cd');
    if (!cd) { cd = h('div', 'rn-cd'); cd.id = 'rn-cd'; document.body.appendChild(cd); }
    let n = 3; cd.textContent = n; cd.classList.add('open');
    const iv = setInterval(() => { n--; if (n <= 0) { clearInterval(iv); cd.classList.remove('open'); go(); } else cd.textContent = n; }, 1000);
  }

  function openLive(fresh) {
    const o = overlay('rn-live', 'rn-live');
    o.classList.add('open');
    if (!o.dataset.built) {
      o.dataset.built = '1';
      o.innerHTML =
        '<div class="top"><span id="rn-l-type">Run</span><span id="rn-l-gps">GPS…</span></div>' +
        '<div class="rn-time" id="rn-l-time">0:00</div>' +
        '<div class="rn-lgrid">' +
        '<div><div class="big" id="rn-l-dist">0.00</div><div class="lbl">km</div></div>' +
        '<div><div class="big" id="rn-l-pace">--:--</div><div class="lbl">pace /km</div></div>' +
        '<div><div class="big" id="rn-l-hr">--</div><div class="lbl">heart rate</div></div>' +
        '<div><div class="big" id="rn-l-avg">--:--</div><div class="lbl">avg pace</div></div></div>' +
        '<div class="rn-route" id="rn-l-route"></div>' +
        '<div class="rn-ctrl"><button class="b" id="rn-l-pause">Pause</button><button class="a" id="rn-l-finish">Finish</button></div>';
      $('rn-l-pause').onclick = () => { if (!lastState) return; if (lastState.paused) A.resumeRun(); else A.pauseRun(); };
      $('rn-l-finish').onclick = confirmFinish;
    }
    if (A && A.getLiveTrack) {
      try { livePts = JSON.parse(A.getLiveTrack()).map((p) => [0, p[0], p[1], p[2], 0]); } catch (e) {}
    }
    if (A && A.getRunState && !fresh) { try { const s = JSON.parse(A.getRunState()); if (s.active) window.kestrelRunTick(s); } catch (e) {} }
  }

  function confirmFinish() {
    let m = $('rn-modal');
    if (!m) { m = h('div', 'rn-modal'); m.id = 'rn-modal'; document.body.appendChild(m); }
    m.innerHTML = '<div class="rn-sheet"><b style="font-size:18px">Finish this activity?</b>' +
      '<button class="rn-btn primary" id="rn-m-save">Save activity</button>' +
      '<button class="rn-btn" id="rn-m-resume">Keep going</button>' +
      '<button class="rn-btn danger" id="rn-m-discard">Discard</button></div>';
    m.classList.add('open');
    $('rn-m-resume').onclick = () => m.classList.remove('open');
    $('rn-m-save').onclick = () => { m.classList.remove('open'); finishing = true; A.finishRun(); $('rn-l-time').textContent = 'Saving…'; };
    $('rn-m-discard').onclick = () => { m.classList.remove('open'); finishing = false; A.discardRun(); closeOverlay('rn-live'); };
  }

  let tickN = 0;
  window.kestrelRunTick = function (s) {
    lastState = s;
    if (!s.active) {
      if (!finishing) closeOverlay('rn-live');
      return;
    }
    const o = $('rn-live');
    if (!o || !o.classList.contains('open')) { if (!finishing) openLive(true); }
    if (!$('rn-l-time')) return;
    $('rn-l-type').textContent = codeType(s.type).label + (s.paused ? ' · paused' : '');
    $('rn-l-gps').textContent = s.gps ? (s.accuracy > 0 ? 'GPS ±' + s.accuracy + ' m' : 'GPS searching…') : 'No GPS';
    $('rn-l-time').textContent = fmtDur(s.elapsedSec);
    $('rn-l-dist').textContent = (s.distanceM / 1000).toFixed(2);
    $('rn-l-pace').textContent = s.paused ? '--:--' : fmtPace(s.curPace);
    $('rn-l-avg').textContent = fmtPace(s.avgPace);
    $('rn-l-hr').textContent = s.hr > 0 ? s.hr : '--';
    $('rn-l-pause').textContent = s.paused ? 'Resume' : 'Pause';
    if (s.lat != null) {
      const last = livePts[livePts.length - 1];
      if (!last || last[1] !== s.lat || last[2] !== s.lon) livePts.push([0, s.lat, s.lon, 0, 0]);
    }
    if (++tickN % 3 === 0 && livePts.length > 1) $('rn-l-route').innerHTML = routeSvg(livePts.slice(-1500), { W: 400, H: 220, pad: 20, sw: 4 });
  };

  window.kestrelRunFinished = function (startSec) {
    finishing = false;
    if (!(startSec > 0)) { closeOverlay('rn-live'); renderRun(); return; }
    let tries = 0;
    const iv = setInterval(() => {
      const a = activities().find((x) => x.startSec === startSec);
      if (a || ++tries > 25) {
        clearInterval(iv);
        closeOverlay('rn-live');
        renderRun();
        if (a) openDetail(a); else toast('Saved. Open Activities to see it.');
      }
    }, 400);
  };

  // ---------- activity detail ----------
  function openDetail(a) {
    const o = overlay('rn-detail');
    const track = a.track ? getTrack(a.startSec) : [];
    const an = analyze(track);
    const hasRoute = track.length > 3 && an.dist > 20;
    const dist = a.dist || an.dist;
    const moving = a.moving;
    const pace = dist > 50 ? moving / (dist / 1000) : 0;
    const kc = kcalOf(a);
    const speedKmh = dist > 50 ? (dist / 1000) / (moving / 3600) : 0;
    const tiles = [];
    if (dist > 0) tiles.push([fmtKm(dist), 'km']);
    tiles.push([fmtDur(moving), 'moving time']);
    if (a.elapsed - moving > 20) tiles.push([fmtDur(a.elapsed), 'elapsed']);
    if (dist > 50) tiles.push(a.type.key === 'ride' ? [speedKmh.toFixed(1), 'km/h avg'] : [fmtPace(pace), 'pace /km']);
    if (a.heartRateAvg) tiles.push([a.heartRateAvg, 'avg bpm']);
    if (a.heartRateMax) tiles.push([a.heartRateMax, 'max bpm']);
    if (hasRoute) tiles.push([Math.round(an.ascent), 'elev gain m']);
    if (an.maxSpeed > 0.5) tiles.push([(an.maxSpeed * 3.6).toFixed(1), 'max km/h']);
    if (a.steps) tiles.push([a.steps, 'steps']);
    if (kc) tiles.push([(kc.est ? '≈ ' : '') + kc.v, 'kcal' + (kc.est ? ' (est.)' : '')]);

    o.innerHTML =
      '<div class="rn-bar"><button id="rn-d-back">‹ Back</button><button class="rn-btn primary" id="rn-d-share">Share</button></div>' +
      '<div class="rn-d-title">' + titleOf(a) + '</div><div class="rn-d-sub">' + dateText(a.startMs) + (a.track ? ' · recorded with Kestrel' : ' · from your band') + '</div>' +
      '<div class="rn-route">' + (hasRoute ? routeSvg(track, { W: 400, H: 300, pad: 30, sw: 5, pace: true }) : '<div class="none">' + (a.track ? 'Not enough GPS points for a route.' : 'This activity was recorded on the band, so there is no GPS route.') + '</div>') + '</div>' +
      '<div class="rn-stats">' + tiles.map((t) => '<div class="rn-tile"><b>' + t[0] + '</b><span>' + t[1] + '</span></div>').join('') + '</div>';

    if (an.splits.length) {
      const sp = an.splits.map((s) => s.sec / (s.dist / 1000));
      const fast = Math.min.apply(null, sp), slow = Math.max.apply(null, sp);
      const card = h('div', 'rn-card');
      card.innerHTML = '<h4>Splits</h4>' + an.splits.map((s, i) => {
        const p = sp[i], w = 30 + 70 * (1 - (p - fast) / Math.max(1, slow - fast)) * (slow === fast ? 0 : 1) + (slow === fast ? 70 : 0);
        return '<div class="rn-split"><span class="k">' + (s.partial ? s.km - 1 + '+' : s.km) + '</span><span class="p">' + fmtPace(p) + '</span><div class="bar"><i style="width:' + w + '%"></i></div><span style="width:34px;text-align:right;color:var(--muted)">' + (s.hr || '') + '</span></div>';
      }).join('');
      o.appendChild(card);
    }
    if (hasRoute) {
      const e = h('div', 'rn-card'); e.innerHTML = '<h4>Elevation (m)</h4>' + sparkSvg(an.alt, '#A7B0C4'); o.appendChild(e);
    }
    const hrs = track.map((p) => p[4]).filter((x) => x > 30);
    if (hrs.length > 5) { const e = h('div', 'rn-card'); e.innerHTML = '<h4>Heart rate (bpm)</h4>' + sparkSvg(hrs, '#454B5C'); o.appendChild(e); }

    const act = h('div', 'rn-actions');
    if (a.track && A) {
      act.innerHTML = '<button class="rn-btn" id="rn-d-gpx">Share GPX</button><button class="rn-btn" id="rn-d-gpxs">Save GPX</button>';
    }
    act.insertAdjacentHTML('beforeend', '<button class="rn-btn danger" id="rn-d-del">Delete</button>');
    o.appendChild(act);
    o.classList.add('open'); o.scrollTop = 0;

    $('rn-d-back').onclick = () => closeOverlay('rn-detail');
    $('rn-d-share').onclick = () => openShare(a, an, track);
    const g1 = $('rn-d-gpx'); if (g1) g1.onclick = () => toast(A.exportGpx(a.startSec, titleOf(a), 'share'));
    const g2 = $('rn-d-gpxs'); if (g2) g2.onclick = () => toast(A.exportGpx(a.startSec, titleOf(a), 'save'));
    $('rn-d-del').onclick = () => {
      if (!confirm('Delete this activity from the app?')) return;
      if (A && A.deleteRun) { try { A.deleteRun(a.startSec); } catch (e) {} }
      const st = app().state;
      st.workouts = (st.workouts || []).filter((w) => toMs(w.start) !== a.startMs);
      app().persistAll(); app().renderWorkouts();
      closeOverlay('rn-detail'); renderRun();
    };
  }

  // ---------- story / post share cards ----------
  const SIZES = { story: [1080, 1920], post: [1080, 1350], square: [1080, 1080] };
  const FONT = '"Plus Jakarta Sans", -apple-system, "SF Pro Text", system-ui, sans-serif';
  let shareSel = { tpl: 'glass', fmt: 'story' };

  function openShare(a, an, track) {
    const o = overlay('rn-share');
    o.innerHTML =
      '<div class="rn-bar">' +
        '<button type="button" id="rn-s-back" class="rn-chip-btn">‹ Back</button>' +
        '<b class="rn-share-title">Share activity</b>' +
        '<span style="width:64px"></span>' +
      '</div>' +
      '<div class="rn-share-prev"><canvas id="rn-s-canvas"></canvas></div>' +
      '<div class="rn-share-section"><span class="rn-share-label">Style</span><div class="rn-pills" id="rn-s-tpl"></div></div>' +
      '<div class="rn-share-section"><span class="rn-share-label">Format</span><div class="rn-pills" id="rn-s-fmt"></div></div>' +
      '<div class="rn-share-actions">' +
        '<button type="button" class="rn-share-cta primary" id="rn-s-ig">Instagram Story</button>' +
        '<button type="button" class="rn-share-cta" id="rn-s-wa">WhatsApp</button>' +
        '<button type="button" class="rn-share-cta" id="rn-s-more">More</button>' +
        '<button type="button" class="rn-share-cta ghost" id="rn-s-save">Save image</button>' +
      '</div>' +
      '<p class="mo-note rn-share-tip">Route only uses a transparent background — drop it over any photo in your story.</p>';
    o.classList.add('open'); o.scrollTop = 0;
    const data = shareData(a, an, track);
    const redraw = () => {
      const c = $('rn-s-canvas'); const sz = SIZES[shareSel.fmt];
      c.width = sz[0]; c.height = sz[1];
      drawCard(c.getContext('2d'), sz[0], sz[1], shareSel.tpl, data);
      [
        ['rn-s-tpl', [['glass', 'Moonlight'], ['cream', 'Silver'], ['route', 'Route only'], ['splits', 'Splits']], 'tpl'],
        ['rn-s-fmt', [['story', 'Story 9:16'], ['post', 'Post 4:5'], ['square', 'Square']], 'fmt']
      ].forEach(([id, items, key]) => {
        const box = $(id); box.innerHTML = '';
        items.forEach(([v, label]) => {
          const b = h('button', 'rn-pill' + (shareSel[key] === v ? ' active' : ''), label);
          b.type = 'button';
          b.onclick = () => { shareSel[key] = v; redraw(); };
          box.appendChild(b);
        });
      });
    };
    redraw();
    const png = () => $('rn-s-canvas').toDataURL('image/png');
    const name = 'kestrel_' + a.startSec + '.png';
    const text = data.title + ' · ' + data.distance + ' km in ' + data.time + ' · recorded with Kestrel';
    const send = (target) => {
      if (A && A.shareImage) { const r = A.shareImage(png(), name, text, target); if (r && r !== 'ok') toast(r); }
      else { const l = document.createElement('a'); l.href = png(); l.download = name; l.click(); }
    };
    $('rn-s-back').onclick = () => closeOverlay('rn-share');
    $('rn-s-ig').onclick = () => send('story');
    $('rn-s-wa').onclick = () => send('whatsapp');
    $('rn-s-more').onclick = () => send('system');
    $('rn-s-save').onclick = () => { if (A && A.saveImageToGallery) toast(A.saveImageToGallery(png(), name)); else send('system'); };
  }

  function shareData(a, an, track) {
    const dist = a.dist || an.dist;
    const pace = dist > 50 ? a.moving / (dist / 1000) : 0;
    return {
      title: titleOf(a), date: dateText(a.startMs), type: a.type.label, distance: fmtKm(dist), time: fmtDur(a.moving),
      pace: a.type.key === 'ride' ? ((dist / 1000) / (a.moving / 3600)).toFixed(1) : fmtPace(pace), paceUnit: a.type.key === 'ride' ? 'km/h' : '/km',
      hr: a.heartRateAvg || 0, elev: an.ascent ? Math.round(an.ascent) : 0, track: track, splits: an.splits
    };
  }

  function rr(ctx, x, y, w, hgt, r) {
    ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + hgt, r); ctx.arcTo(x + w, y + hgt, x, y + hgt, r);
    ctx.arcTo(x, y + hgt, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }
  function drawCard(ctx, W, H, tpl, d) {
    ctx.clearRect(0, 0, W, H);
    const dark = tpl !== 'cream';
    const ink = tpl === 'cream' ? '#2F3442' : '#D9DCE5';
    const soft = tpl === 'cream' ? 'rgba(61,51,64,.6)' : 'rgba(255,238,215,.65)';
    if (tpl === 'glass' || tpl === 'splits') {
      const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, '#4a3f4d'); g.addColorStop(.5, '#2a232b'); g.addColorStop(1, '#1c181e');
      ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
      let rg = ctx.createRadialGradient(W * .15, H * .05, 10, W * .15, H * .05, W * .9); rg.addColorStop(0, 'rgba(255,238,215,.22)'); rg.addColorStop(1, 'rgba(255,238,215,0)');
      ctx.fillStyle = rg; ctx.fillRect(0, 0, W, H);
      rg = ctx.createRadialGradient(W * .95, H * .45, 10, W * .95, H * .45, W * .8); rg.addColorStop(0, 'rgba(170,152,169,.35)'); rg.addColorStop(1, 'rgba(170,152,169,0)');
      ctx.fillStyle = rg; ctx.fillRect(0, 0, W, H);
    } else if (tpl === 'cream') {
      ctx.fillStyle = '#D9DCE5'; ctx.fillRect(0, 0, W, H);
      const rg = ctx.createRadialGradient(W * .9, H * .1, 10, W * .9, H * .1, W); rg.addColorStop(0, 'rgba(170,152,169,.35)'); rg.addColorStop(1, 'rgba(170,152,169,0)');
      ctx.fillStyle = rg; ctx.fillRect(0, 0, W, H);
    }
    const shadow = tpl === 'route';
    const txt = (s, x, y, size, weight, color, align, spacing) => {
      ctx.font = weight + ' ' + size + 'px ' + FONT; ctx.fillStyle = color; ctx.textAlign = align || 'left';
      if (spacing && 'letterSpacing' in ctx) ctx.letterSpacing = spacing + 'px'; else if ('letterSpacing' in ctx) ctx.letterSpacing = '0px';
      if (shadow) { ctx.shadowColor = 'rgba(0,0,0,.55)'; ctx.shadowBlur = 14; }
      ctx.fillText(s, x, y); ctx.shadowBlur = 0; ctx.shadowColor = 'transparent';
    };
    const M = 80, tall = H > 1500;
    // header
    txt('KESTREL', M, 130, 30, 800, tpl === 'cream' ? '#8A93A6' : '#A7B0C4', 'left', 7);
    txt(d.title, M, 215, 74, 800, tpl === 'route' ? '#fff' : ink, 'left');
    txt(d.date, M, 268, 32, 600, tpl === 'route' ? 'rgba(255,255,255,.85)' : soft, 'left');
    // route area
    const rTop = 320, rH = tall ? H * 0.40 : H * 0.34;
    if (d.track && d.track.length > 3 && tpl !== 'splits') {
      const pr = projectTrack(d.track, W - 2 * M, rH, 30);
      const path = () => { ctx.beginPath(); pr.forEach((p, i) => (i ? ctx.lineTo(M + p[0], rTop + p[1]) : ctx.moveTo(M + p[0], rTop + p[1]))); };
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      if (tpl !== 'cream') { ctx.strokeStyle = 'rgba(255,238,215,.30)'; ctx.lineWidth = 34; ctx.shadowColor = 'rgba(255,238,215,.55)'; ctx.shadowBlur = 40; path(); ctx.stroke(); ctx.shadowBlur = 0; }
      const g = ctx.createLinearGradient(M, rTop, W - M, rTop + rH);
      if (tpl === 'cream') { g.addColorStop(0, '#A7B0C4'); g.addColorStop(1, '#454B5C'); } else if (tpl === 'route') { g.addColorStop(0, '#ffffff'); g.addColorStop(1, '#D9DCE5'); } else { g.addColorStop(0, '#D9DCE5'); g.addColorStop(1, '#454B5C'); }
      ctx.strokeStyle = g; ctx.lineWidth = 14; if (tpl === 'route') { ctx.shadowColor = 'rgba(0,0,0,.5)'; ctx.shadowBlur = 18; }
      path(); ctx.stroke(); ctx.shadowBlur = 0;
      const a = pr[0], b = pr[pr.length - 1];
      [[a, '#4E9F83'], [b, '#454B5C']].forEach(([p, c]) => { ctx.beginPath(); ctx.arc(M + p[0], rTop + p[1], 17, 0, 7); ctx.fillStyle = c; ctx.fill(); ctx.lineWidth = 6; ctx.strokeStyle = '#fff'; ctx.stroke(); });
    } else if (tpl === 'splits' && d.splits.length) {
      const sp = d.splits.map((s) => s.sec / (s.dist / 1000)); const fast = Math.min.apply(null, sp), slow = Math.max.apply(null, sp);
      const n = Math.min(d.splits.length, 14), rowH = Math.min(58, (rH + 40) / n);
      d.splits.slice(0, n).forEach((s, i) => {
        const y = rTop + i * rowH + 40, w = (W - 2 * M - 230) * (slow === fast ? 1 : 0.35 + 0.65 * (1 - (sp[i] - fast) / (slow - fast)));
        txt(String(s.partial ? s.km - 1 + '+' : s.km), M, y + 28, 30, 700, soft);
        ctx.fillStyle = 'rgba(217,220,229,.14)'; rr(ctx, M + 70, y, W - 2 * M - 230, 36, 18); ctx.fill();
        ctx.fillStyle = '#454B5C'; rr(ctx, M + 70, y, Math.max(24, w), 36, 18); ctx.fill();
        txt(fmtPace(sp[i]), W - M, y + 28, 30, 800, ink, 'right');
      });
    } else {
      txt(d.type.toUpperCase(), W / 2, rTop + rH / 2, 120, 800, soft, 'center', 10);
    }
    // stats
    const sTop = rTop + rH + (tall ? 90 : 60);
    if (tpl === 'cream') { ctx.fillStyle = 'rgba(255,255,255,.55)'; rr(ctx, M - 20, sTop - 100, W - 2 * M + 40, tall ? 520 : 400, 44); ctx.fill(); }
    const tc = tpl === 'route' ? '#fff' : ink;
    txt(d.distance, M, sTop + 80, tall ? 190 : 160, 800, tc, 'left');
    ctx.font = '800 ' + (tall ? 190 : 160) + 'px ' + FONT;
    const dw = ctx.measureText(d.distance).width;
    txt('km', M + dw + 20, sTop + 80, 56, 700, tpl === 'route' ? 'rgba(255,255,255,.85)' : soft, 'left');
    const cols = [[d.time, 'TIME'], [d.pace, 'PACE ' + d.paceUnit.toUpperCase()]];
    if (d.hr) cols.push([String(d.hr), 'AVG BPM']); else if (d.elev) cols.push([String(d.elev), 'ELEV M']);
    const cw = (W - 2 * M) / cols.length, cy = sTop + (tall ? 260 : 230);
    cols.forEach((c, i) => { txt(c[0], M + i * cw, cy, 72, 800, tc, 'left'); txt(c[1], M + i * cw, cy + 52, 26, 700, tpl === 'route' ? 'rgba(255,255,255,.8)' : soft, 'left', 4); });
    txt('Recorded offline with Kestrel', W / 2, H - 70, 26, 600, tpl === 'route' ? 'rgba(255,255,255,.75)' : soft, 'center', 2);
  }

  // ---------- band → phone offer ----------
  // When a workout is started ON the band, the native side calls kestrelBandWorkoutOffer.
  // We ask the user once; Yes = phone GPS map + live band HR; No = band only.
  function showBandWorkoutOffer(info) {
    const type = (info && info.type) || 1;
    const label = (info && info.label) || 'Run';
    let sheet = $('rn-band-offer');
    if (!sheet) {
      sheet = h('div', 'rn-band-offer');
      sheet.id = 'rn-band-offer';
      document.body.appendChild(sheet);
    }
    sheet.innerHTML =
      '<div class="rn-band-offer-card">' +
      '<div class="rn-band-offer-badge">Band</div>' +
      '<h3>' + label + ' started on your band</h3>' +
      '<p>Record GPS on the phone for a map & heat trail. Heart rate still comes from the band.</p>' +
      '<div class="rn-band-offer-actions">' +
      '<button type="button" class="rn-share-cta" id="rn-band-no">Not now</button>' +
      '<button type="button" class="rn-share-cta primary" id="rn-band-yes">Track on phone</button>' +
      '</div></div>';
    sheet.classList.add('open');
    const close = () => sheet.classList.remove('open');
    sheet.querySelector('#rn-band-no').onclick = () => {
      close();
      if (A && A.declineBandWorkout) try { A.declineBandWorkout(); } catch (e) {}
      toast('OK — band keeps recording on its own');
    };
    sheet.querySelector('#rn-band-yes').onclick = () => {
      close();
      if (!A || !A.acceptBandWorkout) { toast('Open the Android app to track.'); return; }
      try {
        const msg = A.acceptBandWorkout(type);
        toast(msg || 'Tracking on phone');
        finishing = false; livePts = [];
        openLive(true);
        // Jump to Activity tab so the live screen is in context
        if (window.kestrelGoto) window.kestrelGoto('activity');
      } catch (e) { toast('Could not start tracking'); }
    };
  }
  window.kestrelBandWorkoutOffer = showBandWorkoutOffer;

  // ---------- wiring ----------
  function init() {
    if (!$('run-root')) return;
    renderRun();
    const strip = $('tab-strip');
    if (strip) strip.addEventListener('click', (e) => { const b = e.target.closest('.tab-strip-btn'); if (b && b.dataset.page === 'run') renderRun(); });
    const origB = window.kestrelBandWorkouts;
    if (origB) window.kestrelBandWorkouts = function (l) { origB(l); try { renderRun(); } catch (e) {} };
    if (A && A.getRunState) {
      try { const s = JSON.parse(A.getRunState()); if (s.active) { lastState = s; openLive(false); } } catch (e) {}
    }
  }
  window.kestrelRunDebug = { analyze: analyze, bestTime: bestTime, routeSvg: routeSvg, fmtPace: fmtPace, fmtDur: fmtDur, renderRun: renderRun, openDetail: openDetail, activities: activities, drawCard: drawCard };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else setTimeout(init, 50);
})();
