/* More tab — sleep stages, water + mood logging, backup / export / restore. Works offline. */
(function () {
  'use strict';
  const A = window.KestrelAndroidBridge || null;
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

  function render() {
    const root = $('more-root'); if (!root || !app()) return;
    root.innerHTML = '';
    const stack = h('div', 'sheet-stack'); root.appendChild(stack);
    const tabs = h('div', 'sub-tab-row');
    [['sleep', 'Sleep'], ['life', 'Water & mood'], ['band', 'Band'], ['backup', 'Backup']].forEach(([k, l]) => {
      const b = h('button', 'sub-tab-btn' + (tab === k ? ' active' : ''), l); b.type = 'button';
      b.onclick = () => { tab = k; render(); }; tabs.appendChild(b);
    });
    stack.appendChild(tabs);
    if (tab === 'sleep') renderSleep(stack); else if (tab === 'life') renderLife(stack); else if (tab === 'band') renderBand(stack); else renderBackup(stack);
  }

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
      '<div class="mo-btns" style="margin-top:10px"><button id="sl-sync">Sync now</button><button id="sl-resync" class="primary">Re-sync everything</button></div>' +
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
  const MOODS = ['😞', '🙁', '😐', '🙂', '😄'];
  const TAGS = ['Calm', 'Happy', 'Focused', 'Tired', 'Stressed', 'Anxious', 'Sore', 'Motivated'];
  let moodDraft = { score: 0, tags: [], note: '' };

  function renderLife(stack) {
    const w = load(WATER, { goal: 2500, days: {} });
    const key = todayKey();
    const list = w.days[key] || [];
    const ml = list.reduce((s, e) => s + e.ml, 0);
    const pct = Math.min(1, ml / Math.max(1, w.goal));
    const c = 2 * Math.PI * 46;
    const card = h('div', 'rn-card');
    card.innerHTML = '<h4>Water today</h4><div class="mo-ring"><svg width="116" height="116" viewBox="0 0 116 116"><circle cx="58" cy="58" r="46" fill="none" stroke="rgba(170,152,169,.25)" stroke-width="12"/>' +
      '<circle cx="58" cy="58" r="46" fill="none" stroke="#7fb8d6" stroke-width="12" stroke-linecap="round" stroke-dasharray="' + (c * pct) + ' ' + c + '" transform="rotate(-90 58 58)"/>' +
      '<text x="58" y="56" text-anchor="middle" font-size="20" font-weight="800" fill="currentColor">' + ml + '</text><text x="58" y="74" text-anchor="middle" font-size="11" fill="currentColor" opacity=".6">of ' + w.goal + ' ml</text></svg>' +
      '<div><div class="mo-btns"><button data-ml="150">+150</button><button data-ml="250">+250</button><button data-ml="330">+330</button><button data-ml="500">+500</button></div>' +
      '<div class="mo-btns" style="margin-top:8px"><button id="wt-undo">Undo</button><button id="wt-goal">Goal</button></div></div></div>';
    stack.appendChild(card);
    card.querySelectorAll('button[data-ml]').forEach((b) => (b.onclick = () => { (w.days[key] = w.days[key] || []).push({ t: Date.now(), ml: Number(b.dataset.ml) }); save(WATER, w); render(); }));
    card.querySelector('#wt-undo').onclick = () => { if (list.length) { list.pop(); save(WATER, w); render(); } };
    card.querySelector('#wt-goal').onclick = () => { const v = Number(prompt('Daily water goal (ml)', w.goal)); if (v >= 500) { w.goal = Math.round(v); save(WATER, w); render(); } };
    const wk = h('div', 'rn-card'); wk.innerHTML = '<h4>Last 7 days</h4>';
    const rows = []; for (let i = 6; i >= 0; i--) { const k = keyOf(new Date(Date.now() - i * 86400000)); rows.push([k, (w.days[k] || []).reduce((s, e) => s + e.ml, 0)]); }
    const mx = Math.max(w.goal, Math.max.apply(null, rows.map((r) => r[1])));
    wk.innerHTML += '<div class="rn-bars" style="border:0;padding:0;background:transparent">' + rows.map((r) => '<div><span>' + (r[1] ? (r[1] / 1000).toFixed(1) : '') + '</span><em style="height:' + Math.max(3, (r[1] / mx) * 80) + 'px;background:' + (r[1] >= w.goal ? '#7fb8d6' : 'var(--accent)') + '"></em><span>' + r[0].slice(8) + '</span></div>').join('') + '</div>';
    stack.appendChild(wk);

    // mood
    const m = load(MOOD, {});
    const todays = m[key] || [];
    const mc = h('div', 'rn-card');
    mc.innerHTML = '<h4>How are you feeling?</h4><div class="mo-mood">' + MOODS.map((e, i) => '<button data-s="' + (i + 1) + '" class="' + (moodDraft.score === i + 1 ? 'active' : '') + '">' + e + '</button>').join('') + '</div>' +
      '<div class="mo-tags">' + TAGS.map((t) => '<button data-t="' + t + '" class="' + (moodDraft.tags.indexOf(t) >= 0 ? 'active' : '') + '">' + t + '</button>').join('') + '</div>' +
      '<input class="mo-input" id="md-note" placeholder="Add a note (optional)" value="' + moodDraft.note.replace(/"/g, '&quot;') + '"><div class="mo-btns" style="margin-top:10px"><button class="primary" id="md-save">Log mood</button></div>' +
      (todays.length ? '<p class="mo-note">Today: ' + todays.map((e) => MOODS[e.score - 1] + ' ' + new Date(e.t).getHours() + ':' + pad2(new Date(e.t).getMinutes())).join(' · ') + '</p>' : '');
    stack.appendChild(mc);
    mc.querySelectorAll('button[data-s]').forEach((b) => (b.onclick = () => { moodDraft.note = mc.querySelector('#md-note').value; moodDraft.score = Number(b.dataset.s); render(); }));
    mc.querySelectorAll('button[data-t]').forEach((b) => (b.onclick = () => { moodDraft.note = mc.querySelector('#md-note').value; const i = moodDraft.tags.indexOf(b.dataset.t); if (i >= 0) moodDraft.tags.splice(i, 1); else moodDraft.tags.push(b.dataset.t); render(); }));
    mc.querySelector('#md-save').onclick = () => {
      if (!moodDraft.score) { toast('Pick a mood first'); return; }
      (m[key] = m[key] || []).push({ t: Date.now(), score: moodDraft.score, tags: moodDraft.tags.slice(), note: mc.querySelector('#md-note').value.trim() });
      save(MOOD, m); moodDraft = { score: 0, tags: [], note: '' }; render();
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

  function renderBand(stack) {
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

    if (!A || !A.getBandSettings) {
      const c = h('div', 'rn-card'); c.innerHTML = '<h4>Band controls</h4><p class="mo-note" style="margin-top:0">Settings, alarms and phone notifications are available in the Android app.</p>'; stack.appendChild(c); return;
    }
    let st = {}; try { st = JSON.parse(A.getBandSettings() || '{}'); } catch (e) {}
    st.alarms = Array.isArray(st.alarms) ? st.alarms : [];
    for (let i = 0; i < 5; i++) if (!st.alarms[i]) st.alarms[i] = { slot: i, enabled: false, hour: 7, minute: 0, days: 0 };
    st.inactivity = st.inactivity || { enabled: false, threshold: 60, startHour: 8, endHour: 21 };
    const chk = (id, label, on) => '<label style="display:flex;justify-content:space-between;align-items:center;padding:8px 0;font-weight:600"><span>' + label + '</span><input type="checkbox" id="' + id + '" ' + (on ? 'checked' : '') + ' style="width:22px;height:22px;accent-color:var(--accent)"></label>';
    const sel = (id, opts, cur) => '<select id="' + id + '" class="mo-input" style="width:auto">' + opts.map((o) => '<option value="' + o[0] + '"' + (String(o[0]) === String(cur) ? ' selected' : '') + '>' + o[1] + '</option>').join('') + '</select>';

    const set = h('div', 'rn-card');
    set.innerHTML = '<h4>Band settings</h4>' +
      '<div style="display:flex;justify-content:space-between;align-items:center;font-weight:600;padding:6px 0"><span>Heart-rate check every</span>' + sel('bs-hr', [[0, 'Off'], [1, '1 min'], [5, '5 min'], [10, '10 min'], [30, '30 min']], st.hrInterval == null ? 5 : st.hrInterval) + '</div>' +
      chk('bs-sleephr', 'Heart rate during sleep', st.sleepHr !== false) + chk('bs-lift', 'Raise wrist to wake screen', st.lift !== false) + chk('bs-goal', 'Notify when step goal is reached', st.goalNotify !== false) +
      chk('bs-inact', 'Sedentary reminder', st.inactivity.enabled) +
      '<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;font-size:13px;font-weight:600"><span>after</span>' + sel('bs-ithr', [[30, '30 min'], [45, '45 min'], [60, '60 min'], [90, '90 min']], st.inactivity.threshold) +
      '<span>between</span>' + sel('bs-ish', Array.from({ length: 24 }, (_, i) => [i, pad2(i) + ':00']), st.inactivity.startHour) + '<span>and</span>' + sel('bs-ieh', Array.from({ length: 24 }, (_, i) => [i, pad2(i) + ':00']), st.inactivity.endHour) + '</div>';
    stack.appendChild(set);

    const al = h('div', 'rn-card');
    al.innerHTML = '<h4>Band alarms</h4>' + st.alarms.slice(0, 5).map((a, i) =>
      '<div style="padding:10px 0;border-bottom:1px solid var(--line)"><div style="display:flex;align-items:center;justify-content:space-between;gap:10px">' +
      '<input type="time" id="al-t' + i + '" class="mo-input" style="width:auto;font-size:20px;font-weight:800" value="' + pad2(a.hour) + ':' + pad2(a.minute) + '">' +
      '<input type="checkbox" id="al-e' + i + '" ' + (a.enabled ? 'checked' : '') + ' style="width:24px;height:24px;accent-color:var(--accent)"></div>' +
      '<div class="mo-tags" style="margin:8px 0 0" id="al-d' + i + '">' + DAYS.map((d, j) => '<button type="button" data-b="' + (1 << j) + '" class="' + ((a.days & (1 << j)) ? 'active' : '') + '">' + d + '</button>').join('') + '</div></div>').join('') +
      '<p class="mo-note">No day selected = rings once. Alarms run on the band itself, so they work without the phone nearby. (This band model has no “smart wake” window in the protocol.)</p>';
    stack.appendChild(al);
    al.querySelectorAll('.mo-tags button').forEach((b) => (b.onclick = () => b.classList.toggle('active')));

    const save = h('div', 'rn-card');
    save.innerHTML = '<div class="mo-btns"><button class="primary" id="bs-save">Save to band</button></div><p class="mo-note">Settings are stored here and re-sent every time the band connects.</p>';
    stack.appendChild(save);
    save.querySelector('#bs-save').onclick = () => {
      const g = (id) => stack.querySelector('#' + id);
      const out = {
        hrInterval: Number(g('bs-hr').value), sleepHr: g('bs-sleephr').checked, lift: g('bs-lift').checked, goalNotify: g('bs-goal').checked,
        inactivity: { enabled: g('bs-inact').checked, threshold: Number(g('bs-ithr').value), startHour: Number(g('bs-ish').value), endHour: Number(g('bs-ieh').value) },
        alarms: st.alarms.slice(0, 5).map((a, i) => {
          const tv = (g('al-t' + i).value || '07:00').split(':'); let days = 0;
          g('al-d' + i).querySelectorAll('button.active').forEach((b) => (days |= Number(b.dataset.b)));
          return { slot: i, enabled: g('al-e' + i).checked, hour: Number(tv[0]), minute: Number(tv[1]), days: days };
        })
      };
      toast(A.saveBandSettings(JSON.stringify(out)));
    };

    // phone notifications
    let nc = { enabled: false, all: false, packages: [], access: false };
    try { nc = JSON.parse(A.getNotifyConfig()); } catch (e) {}
    const nf = h('div', 'rn-card');
    nf.innerHTML = '<h4>Phone notifications on band</h4>' + chk('nf-on', 'Show phone notifications on the band', nc.enabled) + chk('nf-all', 'All apps', nc.all) +
      '<div id="nf-apps" style="' + (nc.all ? 'opacity:.4;pointer-events:none' : '') + '">' + NOTIFY_APPS.map((a) => chk('nf-' + a[0].replace(/\./g, '_'), a[1], nc.packages.indexOf(a[0]) >= 0)).join('') + '</div>' +
      '<p class="mo-note">' + (nc.access ? 'Notification access is granted.' : 'Android needs you to allow “Notification access” for Kestrel once.') + '</p>' +
      '<div class="mo-btns"><button class="primary" id="nf-save">Save</button>' + (nc.access ? '' : '<button id="nf-grant">Allow access</button>') + '<button id="nf-test">Send test</button></div>';
    stack.appendChild(nf);
    nf.querySelector('#nf-all').onchange = (e) => { nf.querySelector('#nf-apps').style.cssText = e.target.checked ? 'opacity:.4;pointer-events:none' : ''; };
    nf.querySelector('#nf-save').onclick = () => {
      const pk = NOTIFY_APPS.filter((a) => nf.querySelector('#nf-' + a[0].replace(/\./g, '_')).checked).map((a) => a[0]);
      // keep related default packages (Samsung / AOSP messaging) together with Messages
      if (pk.indexOf('com.google.android.apps.messaging') >= 0) pk.push('com.samsung.android.messaging', 'com.android.mms');
      if (pk.indexOf('com.google.android.dialer') >= 0) pk.push('com.android.server.telecom');
      if (pk.indexOf('com.whatsapp') >= 0) pk.push('com.whatsapp.w4b');
      A.setNotifyConfig(JSON.stringify({ enabled: nf.querySelector('#nf-on').checked, all: nf.querySelector('#nf-all').checked, packages: pk })); toast('Saved');
    };
    const gr = nf.querySelector('#nf-grant'); if (gr) gr.onclick = () => A.openNotificationAccess();
    nf.querySelector('#nf-test').onclick = () => toast(A.sendTestNotification() ? 'Sent to band' : 'Connect the band first');
  }

  // ---------------- Backup ----------------
  function renderBackup(stack) {
    const c = h('div', 'rn-card');
    c.innerHTML = '<h4>Export</h4><div class="mo-btns"><button id="bk-csv">Daily CSV</button><button id="bk-json">Daily JSON</button>' + (A && A.exportSamplesCsv ? '<button id="bk-min">Band minutes CSV</button>' : '') + '</div>' +
      '<p class="mo-note">Files are saved to Downloads/Kestrel.</p>';
    stack.appendChild(c);
    const fire = (id) => { const b = $(id); if (b) b.click(); };
    c.querySelector('#bk-csv').onclick = () => fire('export-csv');
    c.querySelector('#bk-json').onclick = () => fire('export-json');
    const mn = c.querySelector('#bk-min'); if (mn) mn.onclick = () => toast(A.exportSamplesCsv());

    const b = h('div', 'rn-card');
    b.innerHTML = '<h4>Full backup</h4><p class="mo-note" style="margin-top:0">Everything in the app: days, workouts, notes, water, mood, settings and run records.</p>' +
      '<div class="mo-btns"><button class="primary" id="bk-save">Create backup</button><button id="bk-restore">Restore backup…</button></div><input type="file" id="bk-file" accept=".json,application/json" class="hidden">';
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

  function init() {
    if (!$('more-root')) return;
    render();
    const strip = $('tab-strip');
    if (strip) strip.addEventListener('click', (e) => { const b = e.target.closest('.tab-strip-btn'); if (b && b.dataset.page === 'more') { sleepKey = null; render(); } });
  }
  window.kestrelMoreRender = render;
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else setTimeout(init, 50);
})();
