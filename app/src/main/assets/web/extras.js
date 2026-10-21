/* More tab — sleep stages, water + mood logging, backup / export / restore. Works offline. */
(function () {
  'use strict';
  const A = window.KestrelAndroidBridge || null;
  function pushLifeWidgets() {
    try {
      if (!A || typeof A.syncLifeWidgets !== 'function') return;
      const w = JSON.parse(localStorage.getItem('kestrel.water.v1') || '{"goal":2500,"days":{}}');
      const key = (function(){ const d=new Date(); return d.getFullYear()+"-"+String(d.getMonth()+1).padStart(2,"0")+"-"+String(d.getDate()).padStart(2,"0"); })();
      const list = (w.days && w.days[key]) || [];
      const ml = list.reduce(function(s,e){ return s + (e.ml||0); }, 0);
      const m = JSON.parse(localStorage.getItem('kestrel.mood.v1') || '{}');
      const todayMoods = m[key] || [];
      const last = todayMoods.length ? todayMoods[todayMoods.length-1] : null;
      const score = last ? (last.score||0) : 0;
      const label = last && last.tags && last.tags[0] ? last.tags[0] : '';
      A.syncLifeWidgets(ml, w.goal||2500, score, label);
    } catch (e) {}
  }
  const app = () => window.kestrelApp;
  const $ = (id) => document.getElementById(id);
  function h(tag, cls, html) { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
  const pad2 = (n) => String(n).padStart(2, '0');
  const keyOf = (d) => d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  const todayKey = () => keyOf(new Date());
  function toast(m) { if (A && A.showToast) { try { A.showToast(String(m)); return; } catch (e) {} } console.log(m); }
  function load(k, d) { try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch (e) { return d; } }
  function save(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
  const hm = (m) => (m > 0 ? Math.floor(m / 60) + 'h ' + pad2(Math.round(m % 60)) + 'm' : '--');
  const clock = (iso) => { const d = new Date(iso); return isFinite(d) ? pad2(d.getHours()) + ':' + pad2(d.getMinutes()) : '--:--'; };

  const COL = { deep: '#6b5b95', light: '#AA98A9', rem: '#e9a8b8', awake: 'rgba(170,152,169,.25)' };
  let tab = 'sleep';
  let sleepKey = null;

  function activeGroup() { const b = document.querySelector('.tab-strip-btn.active'); return b ? b.dataset.group : 'vitals'; }
  function into(rootId, fn) {
    const root = $(rootId); if (!root) return;
    root.innerHTML = '';
    const stack = h('div', 'fx-stack'); root.appendChild(stack);
    try { fn(stack); } catch (e) { console.warn('[extras ' + rootId + ']', e); }
  }
  function renderAll(group) {
    if (!app()) return;
    group = group || activeGroup();
    if (group === 'vitals') into('sleep-root', renderSleep);
    else if (group === 'wellness') { into('pai-root', renderPai); into('life-root', renderLife); }
    else if (group === 'settings') { into('band-root', (st) => { renderConn(st); renderBand(st); }); into('data-root', renderBackup); }
  }
  function render() { renderAll(activeGroup()); }

  // ---------------- Sleep stages ----------------
  function renderSleep(stack) {
    const st = app().state;
    if (!sleepKey) sleepKey = todayKey();
    const day = (st.localDayIndex && st.localDayIndex[sleepKey]) || null;
    const nav = h('div', 'rn-card');
    nav.innerHTML = '<div style="display:flex;align-items:center;justify-content:space-between"><button class="rn-btn" id="sl-prev">‹</button><b>Night ending ' + sleepKey + '</b><button class="rn-btn" id="sl-next">›</button></div>';
    stack.appendChild(nav);
    nav.querySelector('#sl-prev').onclick = () => { sleepKey = keyOf(new Date(Date.parse(sleepKey + 'T12:00:00') - 86400000)); render(); };
    nav.querySelector('#sl-next').onclick = () => { if (sleepKey < todayKey()) { sleepKey = keyOf(new Date(Date.parse(sleepKey + 'T12:00:00') + 86400000)); render(); } };

    const total = day ? day.sleepMinutes || 0 : 0;
    const card = h('div', 'rn-card');
    if (!total) {
      card.innerHTML = '<h4>Sleep</h4><p class="mo-note">No sleep recorded for this night yet. Open Band sync below if your band should have it.</p>';
    } else {
      const deep = day.deepSleepMinutes || 0, light = day.lightSleepMinutes || 0, rem = day.remSleepMinutes || 0, sum = deep + light + rem;
      const pct = (v) => (sum ? (v / sum) * 100 : 0);
      card.innerHTML = '<h4>Sleep</h4><div style="font-size:34px;font-weight:800">' + hm(total) + '</div>' +
        '<div class="mo-note">' + (day.sleepStart ? clock(day.sleepStart) + ' → ' + clock(day.sleepEnd) : '') + '</div>' +
        (sum ? '<div class="mo-stage"><i style="width:' + pct(deep) + '%;background:' + COL.deep + '"></i><i style="width:' + pct(light) + '%;background:' + COL.light + '"></i><i style="width:' + pct(rem) + '%;background:' + COL.rem + '"></i></div>' +
          '<div class="mo-legend"><span><b style="background:' + COL.deep + '"></b>Deep ' + hm(deep) + ' (' + Math.round(pct(deep)) + '%)</span><span><b style="background:' + COL.light + '"></b>Light ' + hm(light) + '</span><span><b style="background:' + COL.rem + '"></b>REM ' + hm(rem) + '</span></div>' : '<p class="mo-note">Stage breakdown appears after the next band sync.</p>');
    }
    stack.appendChild(card);

    // hypnogram
    const hyp = h('div', 'rn-card'); hyp.innerHTML = '<h4>Sleep timeline</h4>';
    let segs = [];
    if (A && A.getSleepNight) { try { segs = JSON.parse(A.getSleepNight(sleepKey)).segs || []; } catch (e) {} }
    if (segs.length) {
      const t0 = segs[0][0], t1 = segs[segs.length - 1][1], W = 320, H = 110, span = Math.max(1, t1 - t0);
      const rowY = { 2: 6, 0: 40, 1: 74 };
      let svg = '<svg viewBox="0 0 ' + W + ' ' + H + '" style="width:100%;height:auto" preserveAspectRatio="none">';
      [['REM', 6], ['Light', 40], ['Deep', 74]].forEach(([l, y]) => { svg += '<text x="0" y="' + (y + 20) + '" font-size="9" fill="currentColor" opacity=".5">' + l + '</text>'; });
      segs.forEach((s) => {
        const x = 34 + ((s[0] - t0) / span) * (W - 34), w = Math.max(1.5, ((s[1] - s[0]) / span) * (W - 34));
        svg += '<rect x="' + x.toFixed(1) + '" y="' + rowY[s[2]] + '" width="' + w.toFixed(1) + '" height="30" rx="4" fill="' + (s[2] === 1 ? COL.deep : s[2] === 2 ? COL.rem : COL.light) + '"/>';
      });
      svg += '</svg><div style="display:flex;justify-content:space-between;font-size:11px;color:var(--muted);font-weight:700"><span>' + clockT(t0) + '</span><span>' + clockT(t1) + '</span></div>';
      hyp.innerHTML += svg;
    } else {
      hyp.innerHTML += '<p class="mo-note">Timeline shows once the band has synced minute-by-minute sleep for this night.</p>';
    }
    stack.appendChild(hyp);

    // last 7 nights
    const trend = h('div', 'rn-card'); trend.innerHTML = '<h4>Last 7 nights</h4>';
    const rows = []; for (let i = 6; i >= 0; i--) { const k = keyOf(new Date(Date.parse(sleepKey + 'T12:00:00') - i * 86400000)); rows.push([k, (st.localDayIndex && st.localDayIndex[k]) || {}]); }
    const mx = Math.max(480, Math.max.apply(null, rows.map((r) => r[1].sleepMinutes || 0)));
    trend.innerHTML += '<div class="rn-bars" style="border:0;padding:0;background:transparent">' + rows.map((r) => {
      const m = r[1].sleepMinutes || 0, deep = r[1].deepSleepMinutes || 0, rem = r[1].remSleepMinutes || 0, sum = deep + rem + (r[1].lightSleepMinutes || 0);
      const ht = Math.max(3, (m / mx) * 80);
      const bar = sum ? '<em style="height:' + ht + 'px;background:linear-gradient(to top,' + COL.deep + ' ' + (deep / sum) * 100 + '%,' + COL.light + ' ' + (deep / sum) * 100 + '% ' + (100 - (rem / sum) * 100) + '%,' + COL.rem + ' ' + (100 - (rem / sum) * 100) + '%)"></em>' : '<em style="height:' + ht + 'px"></em>';
      return '<div><span>' + (m ? (m / 60).toFixed(1) : '') + '</span>' + bar + '<span>' + r[0].slice(8) + '</span></div>';
    }).join('') + '</div>';
    stack.appendChild(trend);

    // data health + repair
    const dh = h('div', 'rn-card'); dh.innerHTML = '<h4>Band data on this phone</h4><div id="sl-stats" class="mo-note">Loading…</div>' +
      '<div class="mo-btns" style="margin-top:10px"><button id="sl-sync">Sync now</button><button id="sl-resync" >Re-sync everything</button></div>' +
      '<p class="mo-note">If a night is missing, “Re-sync everything” clears the local copy and downloads all sleep and activity the band still holds (about the last week).</p>';
    stack.appendChild(dh);
    if (A && A.getStoreStats) {
      try {
        const s = JSON.parse(A.getStoreStats());
        const when = (t) => (t ? new Date(t * 1000).toLocaleString() : 'never');
        dh.querySelector('#sl-stats').innerHTML = s.samples + ' minutes stored · ' + s.sleepRows + ' sleep minutes<br>Latest data: ' + when(s.last) + '<br>Latest sleep sample: ' + when(s.lastSleep) + '<br>Stress readings: ' + s.stress + ' · Band workouts: ' + s.workouts;
      } catch (e) { dh.querySelector('#sl-stats').textContent = 'Unavailable'; }
      dh.querySelector('#sl-sync').onclick = () => { A.syncBandNow(); toast('Syncing…'); setTimeout(render, 6000); };
      dh.querySelector('#sl-resync').onclick = () => { if (confirm('Re-download all history from the band? This can take a minute.')) { A.resyncEverything(); toast('Re-sync started'); setTimeout(render, 9000); } };
    } else {
      dh.querySelector('#sl-stats').textContent = 'Band sync is available in the Android app.';
    }
  }
  const clockT = (t) => { const d = new Date(t * 1000); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); };

  // ---------------- Water + mood ----------------
  const WATER = 'kestrel.water.v1', MOOD = 'kestrel.mood.v1';
  const MOODS = [
    // Monochrome face SVGs (single slate color)
    '<svg class="mood-ico" viewBox="0 0 40 40" width="32" height="32" aria-hidden="true"><circle cx="20" cy="20" r="17" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="14" cy="16" r="1.8" fill="currentColor"/><circle cx="26" cy="16" r="1.8" fill="currentColor"/><path d="M13 27c2.5-3 11.5-3 14 0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
    '<svg class="mood-ico" viewBox="0 0 40 40" width="32" height="32" aria-hidden="true"><circle cx="20" cy="20" r="17" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="14" cy="16" r="1.8" fill="currentColor"/><circle cx="26" cy="16" r="1.8" fill="currentColor"/><path d="M14 27c2-1.5 10-1.5 12 0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
    '<svg class="mood-ico" viewBox="0 0 40 40" width="32" height="32" aria-hidden="true"><circle cx="20" cy="20" r="17" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="14" cy="16" r="1.8" fill="currentColor"/><circle cx="26" cy="16" r="1.8" fill="currentColor"/><path d="M14 26h12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
    '<svg class="mood-ico" viewBox="0 0 40 40" width="32" height="32" aria-hidden="true"><circle cx="20" cy="20" r="17" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="14" cy="16" r="1.8" fill="currentColor"/><circle cx="26" cy="16" r="1.8" fill="currentColor"/><path d="M14 25c2 2.5 10 2.5 12 0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
    '<svg class="mood-ico" viewBox="0 0 40 40" width="32" height="32" aria-hidden="true"><circle cx="20" cy="20" r="17" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="14" cy="16" r="1.8" fill="currentColor"/><circle cx="26" cy="16" r="1.8" fill="currentColor"/><path d="M12 24c3 5 13 5 16 0" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>'
  ];
  const TAGS = ['Calm', 'Happy', 'Focused', 'Tired', 'Stressed', 'Anxious', 'Sore', 'Motivated'];
  let moodDraft = { score: 0, tags: [], note: '' };


  function appendLocalCoach(stack) {
    const ai = h('div', 'rn-card');
    ai.innerHTML = '<h4>On-device SLM coach</h4>' +
      '<p class="mo-note st-status" id="slm-status">Small language model on this phone — no API key after download.</p>' +
      '<div class="st-actions" style="margin-top:8px">' +
      '<button type="button" id="slm-dl">Download model</button>' +
      '<button type="button" id="slm-load">Load model</button>' +
      '<button type="button" data-q="How am I today? Give a short health summary.">Today</button>' +
      '<button type="button" data-q="Advise me on water and hydration today.">Water</button>' +
      '<button type="button" data-q="Advise me on mood and stress today.">Mood</button>' +
      '<button type="button" data-q="What should I finish on my plan today?">Plan</button>' +
      '</div><p class="mo-note" id="local-coach-out" style="margin-top:10px"></p>';
    stack.appendChild(ai);
    const out = ai.querySelector('#local-coach-out');
    const stEl = ai.querySelector('#slm-status');
    const refreshSlm = () => {
      try {
        if (A && typeof A.getOnDeviceModelStatus === 'function') {
          const s = JSON.parse(A.getOnDeviceModelStatus() || '{}');
          const mb = s.bytes ? Math.round(s.bytes / 1048576) + ' MB' : '—';
          stEl.textContent = (s.loaded ? 'Model loaded' : (s.present ? 'Model on disk (' + mb + ') — tap Load' : 'Model not downloaded')) +
            (s.downloadProgress >= 0 && s.downloadProgress <= 100 ? ' · download ' + s.downloadProgress + '%' : '') +
            (s.lastError && s.lastError !== null ? ' · ' + s.lastError : '');
        }
      } catch (e) {}
    };
    refreshSlm();
    const dl = ai.querySelector('#slm-dl');
    if (dl) dl.onclick = () => {
      if (!A || typeof A.downloadOnDeviceModel !== 'function') { out.textContent = 'Rebuild app for SLM download.'; return; }
      out.textContent = A.downloadOnDeviceModel();
      refreshSlm();
    };
    const ld = ai.querySelector('#slm-load');
    if (ld) ld.onclick = () => {
      if (!A || typeof A.loadOnDeviceModel !== 'function') { out.textContent = 'Rebuild app.'; return; }
      out.textContent = A.loadOnDeviceModel();
      refreshSlm();
    };
    ai.querySelectorAll('button[data-q]').forEach((b) => {
      b.onclick = () => {
        pushLifeWidgets();
        out.textContent = 'Thinking on-device…';
        setTimeout(() => {
          if (A && typeof A.askLocalCoach === 'function') {
            out.textContent = A.askLocalCoach(b.getAttribute('data-q') || 'summary');
          } else {
            out.textContent = 'Rebuild the app binary to enable on-device coach.';
          }
          refreshSlm();
        }, 50);
      };
    });
  }

  function renderLife(stack) {
    const w = load(WATER, { goal: 2500, days: {} });
    const key = todayKey();
    const list = w.days[key] || [];
    const ml = list.reduce((s, e) => s + e.ml, 0);
    const pct = Math.min(1, ml / Math.max(1, w.goal));
    const c = 2 * Math.PI * 46;
    const card = h('div', 'rn-card');
    card.innerHTML = '<h4>Water today</h4><div class="mo-ring"><svg width="116" height="116" viewBox="0 0 116 116"><circle cx="58" cy="58" r="46" fill="none" stroke="rgba(170,152,169,.25)" stroke-width="12"/>' +
      '<circle cx="58" cy="58" r="46" fill="none" stroke="#A7B0C4" stroke-width="12" stroke-linecap="round" stroke-dasharray="' + (c * pct) + ' ' + c + '" transform="rotate(-90 58 58)"/>' +
      '<text x="58" y="56" text-anchor="middle" font-size="20" font-weight="800" fill="currentColor">' + ml + '</text><text x="58" y="74" text-anchor="middle" font-size="11" fill="currentColor" opacity=".6">of ' + w.goal + ' ml</text></svg>' +
      '<div><div class="mo-btns"><button data-ml="150">+150</button><button data-ml="250">+250</button><button data-ml="330">+330</button><button data-ml="500">+500</button></div>' +
      '<div class="mo-btns" style="margin-top:8px"><button id="wt-undo">Undo</button><button id="wt-goal">Goal</button></div></div></div>';
    stack.appendChild(card);
    card.querySelectorAll('button[data-ml]').forEach((b) => (b.onclick = () => { (w.days[key] = w.days[key] || []).push({ t: Date.now(), ml: Number(b.dataset.ml) }); save(WATER, w); pushLifeWidgets(); render(); }));
    card.querySelector('#wt-undo').onclick = () => { if (list.length) { list.pop(); save(WATER, w); pushLifeWidgets(); render(); } };
    card.querySelector('#wt-goal').onclick = () => { const v = Number(prompt('Daily water goal (ml)', w.goal)); if (v >= 500) { w.goal = Math.round(v); save(WATER, w); pushLifeWidgets(); render(); } };
    const wk = h('div', 'rn-card'); wk.innerHTML = '<h4>Last 7 days</h4>';
    const rows = []; for (let i = 6; i >= 0; i--) { const k = keyOf(new Date(Date.now() - i * 86400000)); rows.push([k, (w.days[k] || []).reduce((s, e) => s + e.ml, 0)]); }
    const mx = Math.max(w.goal, Math.max.apply(null, rows.map((r) => r[1])));
    wk.innerHTML += '<div class="rn-bars" style="border:0;padding:0;background:transparent">' + rows.map((r) => '<div><span>' + (r[1] ? (r[1] / 1000).toFixed(1) : '') + '</span><em style="height:' + Math.max(3, (r[1] / mx) * 80) + 'px;background:' + (r[1] >= w.goal ? '#A7B0C4' : 'var(--accent)') + '"></em><span>' + r[0].slice(8) + '</span></div>').join('') + '</div>';
    stack.appendChild(wk);

    // mood
    const m = load(MOOD, {});
    const todays = m[key] || [];
    const mc = h('div', 'rn-card');
    mc.innerHTML = '<h4>How are you feeling?</h4><div class="mo-mood">' + MOODS.map((e, i) => '<button data-s="' + (i + 1) + '" class="' + (moodDraft.score === i + 1 ? 'active' : '') + '">' + e + '</button>').join('') + '</div>' +
      '<div class="mo-tags">' + TAGS.map((t) => '<button data-t="' + t + '" class="' + (moodDraft.tags.indexOf(t) >= 0 ? 'active' : '') + '">' + t + '</button>').join('') + '</div>' +
      '<input class="mo-input" id="md-note" placeholder="Add a note (optional)" value="' + moodDraft.note.replace(/"/g, '&quot;') + '"><div class="mo-btns" style="margin-top:10px"><button id="md-save">Log mood</button></div>' +
      (todays.length ? '<p class="mo-note">Today: ' + todays.map((e) => MOODS[e.score - 1] + ' ' + new Date(e.t).getHours() + ':' + pad2(new Date(e.t).getMinutes())).join(' · ') + '</p>' : '');
    stack.appendChild(mc);
    mc.querySelectorAll('button[data-s]').forEach((b) => (b.onclick = () => { moodDraft.note = mc.querySelector('#md-note').value; moodDraft.score = Number(b.dataset.s); render(); }));
    mc.querySelectorAll('button[data-t]').forEach((b) => (b.onclick = () => { moodDraft.note = mc.querySelector('#md-note').value; const i = moodDraft.tags.indexOf(b.dataset.t); if (i >= 0) moodDraft.tags.splice(i, 1); else moodDraft.tags.push(b.dataset.t); render(); }));
    mc.querySelector('#md-save').onclick = () => {
      if (!moodDraft.score) { toast('Pick a mood first'); return; }
      (m[key] = m[key] || []).push({ t: Date.now(), score: moodDraft.score, tags: moodDraft.tags.slice(), note: mc.querySelector('#md-note').value.trim() });
      save(MOOD, m); pushLifeWidgets(); moodDraft = { score: 0, tags: [], note: '' }; render();
    };
    const tr = h('div', 'rn-card'); tr.innerHTML = '<h4>Mood, last 14 days</h4>';
    const avg = (k) => { const e = m[k] || []; return e.length ? e.reduce((s, x) => s + x.score, 0) / e.length : 0; };
    let dots = '<div style="display:flex;gap:6px;justify-content:space-between">';
    for (let i = 13; i >= 0; i--) { const k = keyOf(new Date(Date.now() - i * 86400000)), a = avg(k); dots += '<div style="text-align:center;font-size:10px;color:var(--muted);font-weight:700"><div style="font-size:18px;opacity:' + (a ? 1 : 0.2) + '">' + (a ? MOODS[Math.round(a) - 1] : '·') + '</div>' + k.slice(8) + '</div>'; }
    tr.innerHTML += dots + '</div>';
    // simple pattern: mood vs sleep
    const days = (app().state.localDayIndex) || {};
    const good = [], bad = [];
    Object.keys(m).forEach((k) => { const a = avg(k), d = days[k]; if (a && d && d.sleepMinutes) (d.sleepMinutes >= 420 ? good : bad).push(a); });
    const mean = (x) => x.reduce((s, v) => s + v, 0) / x.length;
    if (good.length >= 3 && bad.length >= 3) tr.innerHTML += '<p class="mo-note">On nights with 7h+ sleep your mood averaged ' + mean(good).toFixed(1) + '/5, otherwise ' + mean(bad).toFixed(1) + '/5.</p>';
    stack.appendChild(tr);
  }

  // ---------------- Band: PAI, settings, alarms, phone notifications ----------------
  const NOTIFY_APPS = [['com.whatsapp', 'WhatsApp'], ['org.telegram.messenger', 'Telegram'], ['com.instagram.android', 'Instagram'], ['com.facebook.orca', 'Messenger'],
    ['com.google.android.gm', 'Gmail'], ['com.google.android.apps.messaging', 'Messages'], ['com.google.android.dialer', 'Phone (missed calls)'], ['com.twitter.android', 'X / Twitter'], ['com.snapchat.android', 'Snapchat']];
  const DAYS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];


  function renderPai(stack) {
    // PAI
    const days = (app().state.localDayIndex) || {};
    const rows = []; for (let i = 6; i >= 0; i--) { const k = keyOf(new Date(Date.now() - i * 86400000)); rows.push([k, days[k] || {}]); }
    const latest = rows.slice().reverse().find((r) => r[1].bandPaiTotal != null);
    const pai = h('div', 'rn-card');
    const mx = Math.max(10, Math.max.apply(null, rows.map((r) => r[1].bandPaiToday || 0)));
    pai.innerHTML = '<h4>PAI</h4><div style="display:flex;align-items:baseline;gap:10px"><span style="font-size:38px;font-weight:800">' + (latest ? Math.round(latest[1].bandPaiTotal) : '--') + '</span><span class="mo-note" style="margin:0">' +
      (latest ? '7-day score · +' + Math.round(latest[1].bandPaiToday || 0) + ' today' : 'Syncs from your band. Needs a few days of heart-rate data.') + '</span></div>' +
      '<div class="rn-bars" style="border:0;padding:0;background:transparent;margin-top:8px">' + rows.map((r) => '<div><span>' + (r[1].bandPaiToday ? Math.round(r[1].bandPaiToday) : '') + '</span><em style="height:' + Math.max(3, ((r[1].bandPaiToday || 0) / mx) * 70) + 'px"></em><span>' + r[0].slice(8) + '</span></div>').join('') + '</div>';
    stack.appendChild(pai);
  }

  function renderBand(stack) {
    const A = window.KestrelAndroidBridge;
    const c = h('div', 'rn-card sync-card');
    const darkOn = document.body.classList.contains('dark');
    const dm = h('div', 'dm-row');
    dm.innerHTML = '<span>Dark mode</span><button type="button" class="dm-switch" id="dm-toggle" aria-label="Toggle dark mode"></button>';
    stack.appendChild(dm);
    dm.querySelector('#dm-toggle').onclick = () => {
      document.body.classList.toggle('dark');
      try { localStorage.setItem('kestrel.dark', document.body.classList.contains('dark') ? '1' : '0'); } catch (e) {}
    };

    c.innerHTML =
      '<h3>Band &amp; Health</h3>' +
      '<p>Sync your band or import from Health Connect / Gadgetbridge.</p>' +
      '<div class="sync-row">' +
      '<button type="button" class="primary" id="bf-sync">Sync band</button>' +
      '<button type="button" id="bf-zepp">Import HC</button>' +
      '<button type="button" id="bf-pair">Pair</button>' +
      '</div>' +
      '<p class="mo-note keep st-status" id="bf-status" style="margin-top:12px">Ready</p>' +
      '<div style="margin-top:16px">' +
      '<button type="button" id="bf-plan" style="width:100%;padding:14px;border-radius:14px;border:1px solid rgba(0,0,0,0.08);background:#fff;font-weight:600">Edit workout plan</button>' +
      '</div>';
    stack.appendChild(c);

    const status = c.querySelector('#bf-status');
    c.querySelector('#bf-sync').onclick = () => {
      if (!A || !A.syncBandNow) { toast('Open the app binary with band support'); return; }
      status.textContent = 'Syncing…';
      try {
        const r = A.syncBandNow();
        status.textContent = typeof r === 'string' ? r : 'Sync started';
        toast('Band sync started');
      } catch (e) { status.textContent = 'Sync failed'; toast(String(e)); }
    };
    c.querySelector('#bf-zepp').onclick = async () => {
      if (!A || !A.importFromZepp) {
        toast('Rebuild APK for Health Connect import');
        return;
      }
      status.textContent = 'Importing…';
      try {
        const msg = await Promise.resolve(A.importFromZepp());
        status.textContent = msg || 'Import finished';
        toast(msg || 'Import finished');
      } catch (e) {
        status.textContent = 'Import failed';
        toast('Import failed: ' + (e && e.message ? e.message : e));
      }
    };
    c.querySelector('#bf-pair').onclick = () => {
      if (A && A.openBandPairing) A.openBandPairing();
      else if (A && A.startBandScan) A.startBandScan();
      else toast('Pairing available in native band screen');
    };
    c.querySelector('#bf-plan').onclick = () => {
      if (window.openPlanOnboarding) window.openPlanOnboarding();
    };
  }


  function renderBackup(stack) {
    const c = h('div', 'rn-card');
    c.innerHTML = '<h4>Export</h4><div class="mo-btns"><button id="bk-csv">Daily CSV</button><button id="bk-json">Daily JSON</button>' + (A && A.exportSamplesCsv ? '<button id="bk-min">Band minutes CSV</button>' : '') + '</div>' +
      '<p class="mo-note st-status">Saved to Downloads/Kestrel</p>';
    stack.appendChild(c);
    const fire = (id) => { const b = $(id); if (b) b.click(); };
    c.querySelector('#bk-csv').onclick = () => fire('export-csv');
    c.querySelector('#bk-json').onclick = () => fire('export-json');
    const mn = c.querySelector('#bk-min'); if (mn) mn.onclick = () => toast(A.exportSamplesCsv());

    const b = h('div', 'rn-card');
    b.innerHTML = '<h4>Backup</h4><p class="mo-note st-status">Days, workouts, notes, water, mood, settings, runs</p>' +
      '<div class="mo-btns"><button id="bk-save">Create backup</button><button id="bk-restore">Restore backup…</button></div><input type="file" id="bk-file" accept=".json,application/json" class="hidden">';
    stack.appendChild(b);
    b.querySelector('#bk-save').onclick = () => {
      const data = {}; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); data[k] = localStorage.getItem(k); }
      const blob = JSON.stringify({ app: 'kestrel', version: 1, exportedAt: new Date().toISOString(), data: data });
      const name = 'kestrel_backup_' + todayKey() + '.json';
      if (A && A.saveFile) toast(A.saveFile(name, 'application/json', blob));
      else { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([blob], { type: 'application/json' })); a.download = name; a.click(); }
    };
    b.querySelector('#bk-restore').onclick = () => $('bk-file').click();
    b.querySelector('#bk-file').onchange = (e) => {
      const f = e.target.files && e.target.files[0]; if (!f) return;
      const r = new FileReader();
      r.onload = () => {
        try {
          const j = JSON.parse(r.result);
          if (!j || j.app !== 'kestrel' || !j.data) throw new Error('Not a Kestrel backup');
          if (!confirm('Restore this backup? It replaces the data currently in the app.')) return;
          localStorage.clear();
          Object.keys(j.data).forEach((k) => localStorage.setItem(k, j.data[k]));
          location.reload();
        } catch (err) { toast('Restore failed: ' + err.message); }
      };
      r.readAsText(f);
    };
    const n = h('div', 'rn-card'); n.innerHTML = '<h4>Offline</h4><p class="mo-note" style="margin-top:0">Kestrel stores everything on this phone. Band sync, GPS recording, charts and sharing all work without internet.</p>'; stack.appendChild(n);
  }

  // ---------------- Band connection card (Settings) ----------------
  let connTimer = 0;
  function renderConn(stack) {
    const c = h('div', 'rn-card fx-conn');
    c.innerHTML =
      '<h4>Connection</h4>' +
      '<div id="cn-body">Loading…</div>' +
      '<div class="mo-btns">' +
        '<button id="cn-connect">Connect</button>' +
        '<button id="cn-sync">Sync</button>' +
        '<button id="cn-hr">HR</button>' +
        '<button id="cn-buzz">Buzz</button>' +
      '</div>' +
      '<div class="fx-prog" id="cn-prog"><i></i></div>';
    stack.appendChild(c);
    if (!A || !A.getBandStatus) {
      c.querySelector('#cn-body').innerHTML = '<b>Not available</b>Band controls are available in the Android app.';
      return;
    }
    const body = c.querySelector('#cn-body'), prog = c.querySelector('#cn-prog');
    const paint = () => {
      if (!c.isConnected) { clearInterval(connTimer); return; }
      let s = {}; try { s = JSON.parse(A.getBandStatus()); } catch (e) {}
      const st = (s.status || 'DISCONNECTED');
      const label = { CONNECTED: 'Connected', CONNECTING: 'Connecting…', AUTHENTICATING: 'Authenticating…', SCANNING: 'Searching…', DISCONNECTED: 'Not connected' }[st] || st;
      let meta = [];
      if (s.battery > 0) meta.push(s.battery + '% battery');
      if (st === 'CONNECTED' && s.hr > 0) meta.push('♥ ' + s.hr + ' bpm');
      let extra = '';
      if (s.lastSync) extra += '<div style="margin-top:6px;font-size:12.5px;opacity:.7">Last sync ' + new Date(s.lastSync).toLocaleString() + '</div>';
      else extra += '<div style="margin-top:6px;font-size:12.5px;opacity:.7">Not synced yet</div>';
      if (s.syncing) extra += '<div style="margin-top:4px;font-size:12.5px">' + (s.message || 'Syncing…') + '</div>';
      if (s.ack) extra += '<div style="margin-top:4px;font-size:12.5px">Band replied: ' + s.ack + '</div>';
      if (s.error) extra += '<div style="margin-top:4px;font-size:12.5px;color:#c0564f">' + s.error + '</div>';
      body.innerHTML = '<b>' + label + '</b>' +
        (meta.length ? ' <span style="font-size:13px;opacity:.75">' + meta.join(' · ') + '</span>' : '') +
        extra;
      c.classList.toggle('is-on', st === 'CONNECTED');
      prog.classList.toggle('on', !!s.syncing);
      prog.firstElementChild.style.width = (s.syncing ? Math.max(6, s.percent || 0) : 0) + '%';
    };
    paint(); clearInterval(connTimer); connTimer = setInterval(paint, 1500);
    c.querySelector('#cn-connect').onclick = () => A.openBluetoothMenu && A.openBluetoothMenu();
    c.querySelector('#cn-sync').onclick = () => { A.syncBandNow(); toast('Syncing…'); setTimeout(paint, 400); };
    c.querySelector('#cn-hr').onclick = () => toast(A.measureHeartRateNow ? A.measureHeartRateNow() : 'Update the app to use this');
    c.querySelector('#cn-buzz').onclick = () => toast(A.sendTestNotification() ? 'Sent to band' : 'Connect the band first');
  }

  let dataTimer = 0;
  function init() {
    if (!$('sleep-root')) return;
    renderAll('vitals');
    document.addEventListener('kestrel:group', (e) => { sleepKey = null; renderAll(e.detail); });
    document.addEventListener('kestrel:page', (e) => {
      const d = e.detail || {};
      if (d.group === 'vitals' && d.page === 'sleep') renderAll('vitals');
      else if (d.group) renderAll(d.group);
    });
    // keep Sleep / Mood cards live when new band data arrives (never while the user is typing in a form)
    document.addEventListener('kestrel:data', () => {
      clearTimeout(dataTimer);
      dataTimer = setTimeout(() => {
        const g = activeGroup(); if (g === 'settings') return;
        const ae = document.activeElement; if (ae && /INPUT|TEXTAREA|SELECT/.test(ae.tagName)) return;
        renderAll(g);
      }, 350);
    });
  }
  window.kestrelMoreRender = render;
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else setTimeout(init, 50);


  // ---------- AI chat + todos ----------
  const CHAT_KEY = 'kestrel.ai.chat.v1';
  const TODO_KEY = 'kestrel.todos.v1';

  function loadChat() {
    const arr = load(CHAT_KEY, []);
    return Array.isArray(arr) ? arr.slice(-40) : [];
  }
  function saveChat(msgs) { save(CHAT_KEY, msgs.slice(-40)); }

  function loadTodos() {
    const o = load(TODO_KEY, { items: [] });
    if (!o.items) o.items = [];
    return o;
  }
  function saveTodos(o) { save(TODO_KEY, o); }

  function tomorrowKey() {
    return keyOf(new Date(Date.now() + 86400000));
  }

  function formatTodosForPrompt() {
    const o = loadTodos();
    const open = o.items.filter((t) => !t.done).slice(0, 12);
    if (!open.length) return 'No open todos.';
    return open.map((t, i) => (i + 1) + '. [' + (t.tag || 'general') + '] ' + t.text).join('\n');
  }

  /** Rule-based todo generation when SLM is unavailable or as structured fallback. */
  function generateTodosFromDay(text) {
    const lower = (text || '').toLowerCase();
    const items = [];
    const push = (tag, t) => items.push({ id: 't' + Date.now() + Math.random().toString(36).slice(2, 6), text: t, tag: tag, done: false, forDate: tomorrowKey(), created: Date.now() });

    // Study
    if (/exam|test|study|homework|assignment|chapter|math|physics|chem|code|coding|lecture/.test(lower)) {
      push('study', 'Review notes from today for 25 minutes');
      push('study', 'Practice 10 problems or flashcards');
      if (/exam|test/.test(lower)) push('study', 'Past paper / mock questions for 20 minutes');
    } else {
      push('study', 'One focused study block (25–40 min) on weakest subject');
    }
    // Health / recovery
    if (/tired|sleep|exhausted|late/.test(lower)) {
      push('health', 'Aim for lights-out 30 minutes earlier');
      push('health', 'Short walk + water after waking');
    } else {
      push('health', 'Hit step goal or 20-minute walk');
      push('health', 'Drink water to daily goal');
    }
    // Life / admin
    if (/busy|meeting|work|class/.test(lower)) {
      push('life', 'Block calendar for deep work / study');
    }
    push('life', 'Prep bag / materials the night before');
    if (/stress|anxious|overwhelm/.test(lower)) {
      push('health', '5-minute breath or stretch break midday');
    }
    // Dedup-ish limit
    return items.slice(0, 8);
  }

  function parseTodoLinesFromAi(reply) {
    const lines = String(reply || '').split(/\n+/);
    const out = [];
    lines.forEach((line) => {
      let s = line.replace(/^[\s\-*•\d.\)\]]+/, '').trim();
      if (s.length < 4 || s.length > 120) return;
      if (/^(here|sure|okay|ok|i |you |tomorrow|based)/i.test(s)) return;
      let tag = 'general';
      const low = s.toLowerCase();
      if (/study|read|notes|exam|homework|math|code|chapter/.test(low)) tag = 'study';
      else if (/walk|sleep|water|run|workout|health|stretch/.test(low)) tag = 'health';
      else if (/call|email|buy|clean|prep|bag/.test(low)) tag = 'life';
      out.push({ id: 't' + Date.now() + Math.random().toString(36).slice(2, 6), text: s, tag: tag, done: false, forDate: tomorrowKey(), created: Date.now() });
    });
    return out.slice(0, 10);
  }

  function askAi(userText) {
    pushLifeWidgets();
    const todosCtx = formatTodosForPrompt();
    const prompt =
      'You are Kestrel, a helpful on-device coach. Be concise.\n' +
      'Open todos:\n' + todosCtx + '\n\n' +
      'User: ' + userText + '\n\n' +
      'If they ask for a todo list or plan for tomorrow, reply with a short intro then a numbered list of concrete tasks (one per line).';
    if (A && typeof A.askLocalCoach === 'function') {
      try { return A.askLocalCoach(prompt); } catch (e) { /* fall through */ }
    }
    // Offline rule coach
    const low = userText.toLowerCase();
    if (/todo|to-do|task list|plan tomorrow|schedule|generate.*list/.test(low)) {
      const gen = generateTodosFromDay(userText);
      return 'Here is a todo list for tomorrow:\n' + gen.map((t, i) => (i + 1) + '. [' + t.tag + '] ' + t.text).join('\n');
    }
    if (A && typeof A.askLocalCoach === 'function') return A.askLocalCoach(userText);
    return 'Describe your day and ask me to make tomorrow\'s todos, or ask about steps, water, mood, sleep, or plan.';
  }

  function handleChatSend(text, ui) {
    text = (text || '').trim();
    if (!text) return;
    const msgs = loadChat();
    msgs.push({ role: 'user', text: text, t: Date.now() });
    saveChat(msgs);
    ui.renderMessages();
    ui.setStatus('Thinking…');
    setTimeout(() => {
      let reply = askAi(text);
      const low = text.toLowerCase();
      // Auto-save todos when user asked for a list
      if (/todo|to-do|task|plan tomorrow|for tomorrow|schedule/.test(low)) {
        let items = parseTodoLinesFromAi(reply);
        if (!items.length) items = generateTodosFromDay(text);
        if (items.length) {
          const o = loadTodos();
          // replace tomorrow's open generated ones optional — append
          o.items = (o.items || []).concat(items);
          saveTodos(o);
          reply += '\n\n✓ Saved ' + items.length + ' todos for tomorrow. Open the Todos tab in this chat.';
        }
      }
      // Explicit "add todo: ..."
      const addMatch = text.match(/add todo[:\s]+(.+)/i);
      if (addMatch) {
        const o = loadTodos();
        o.items.push({ id: 't' + Date.now(), text: addMatch[1].trim(), tag: 'general', done: false, forDate: tomorrowKey(), created: Date.now() });
        saveTodos(o);
        reply = 'Added todo: ' + addMatch[1].trim();
      }
      const msgs2 = loadChat();
      msgs2.push({ role: 'assistant', text: reply, t: Date.now() });
      saveChat(msgs2);
      ui.renderMessages();
      ui.renderTodos();
      ui.setStatus('');
    }, 60);
  }

  function mountAiChat() {
    /* AI chat is fully owned by index.html inline script */
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mountAiChat);
  } else {
    mountAiChat();
  }

})();
