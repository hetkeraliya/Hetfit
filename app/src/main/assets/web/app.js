(() => {
  const STORAGE_DAYS = 'bandstats.days';
  const STORAGE_META = 'bandstats.meta';
  const STORAGE_SETTINGS = 'bandstats.settings';
  const STORAGE_WORKOUTS = 'bandstats.workouts';
  const STORAGE_DEVICES = 'bandstats.devices';
  // Standard Gadgetbridge ActivityKind codes + Xiaomi / Huami extended codes
  const ACTIVITY_KIND = {
    1: 'Activity',
    2: 'Light Sleep',
    4: 'Deep Sleep',
    8: 'Not Worn',
    16: 'Running',
    17: 'Treadmill',
    32: 'Walking',
    48: 'Nordic Walking',
    64: 'Swimming',
    65: 'Pool Swimming',
    66: 'Open Water Swimming',
    80: 'Walking',
    128: 'Cycling',
    129: 'Indoor Cycling',
    256: 'Exercise',
    257: 'Freestyle',
    512: 'Workout',
    768: 'Strength Training',
    1024: 'Hiking',
    2048: 'Elliptical',
    4096: 'Rowing',
    8192: 'Jump Rope',
    16384: 'Yoga',
    32768: 'Pilates',
    65536: 'Badminton',
    131072: 'Table Tennis',
    262144: 'Basketball',
    524288: 'Soccer',
    1048576: 'Dancing'
  };

  const $ = (id) => document.getElementById(id);
  const uploadView = $('upload-view');
  const dashboardView = $('dashboard-view');
  const dropzone = $('dropzone');
  const gbFileInput = $('gb-file-input');
  const fileLabel = $('file-label');
  const gbLoadBtn = $('gb-load-btn');
  const gbStatus = $('gb-status');
  const tabRow = $('tab-row');
  const rangeRow = $('range-row');
  const chartTitle = $('chart-title');
  const chartPath = $('chart-path');
  const chartPathPrev = $('chart-path-prev');
  const chartDrop = $('chart-drop');
  const chartDot = $('chart-dot');
  const chartDotHalo = $('chart-dot-halo');
  const dayRow = $('day-row');
  const dateRangeLabel = $('date-range-label');
  const deviceSelect = $('device-select');
  const goalInput = $('goal-input');
  const noteInput = $('note-input');
  const hourlyBars = $('hourly-bars');
  const hourlyTitle = $('hourly-title');
  const insightText = $('insight-text');
  const importMeta = $('import-meta');

  const METRIC_META = {
    steps: { title: 'Steps', get: (d) => d.steps || 0 },
    heartRate: { title: 'Heart Rate', get: (d) => d.heartRateAvg || 0 },
    sleep: { title: 'Sleep (hrs)', get: (d) => +(((d.sleepMinutes || 0) / 60).toFixed(1)) },
    distance: { title: 'Distance (km)', get: (d) => +(((d.distanceMeters || 0) / 1000).toFixed(2)) },
    calories: { title: 'Calories', get: (d) => d.calories || 0 },
  };
  const DAY_LETTERS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];
  const SQLITE_MAGIC = 'SQLite format 3';

  function defaultSettings() {
    return { theme: 'light', stepGoal: 8000, range: 'week', deviceId: 'all' };
  }
  function loadSettings() {
    try { return Object.assign(defaultSettings(), JSON.parse(localStorage.getItem(STORAGE_SETTINGS) || '{}')); }
    catch { return defaultSettings(); }
  }
  function saveSettings() { localStorage.setItem(STORAGE_SETTINGS, JSON.stringify(settings)); }

  let settings = loadSettings();
  let state = { metric: 'steps', anchor: startOfWeek(new Date()), series: [], compareSeries: [], selectedIndex: 0, localDayIndex: {}, workouts: [], devices: [], meta: null, heightCm: 170, viewKey: null };

  applyTheme(settings.theme);
  goalInput.value = settings.stepGoal;

  function startOfWeek(d) { const x = new Date(d); x.setHours(0,0,0,0); x.setDate(x.getDate() - x.getDay()); return x; }
  function startOfMonth(d) { const x = new Date(d); x.setHours(0,0,0,0); x.setDate(1); return x; }
  function addDays(d, n) { const x = new Date(d); x.setDate(x.getDate() + n); return x; }
  function fmt(date) { return date.getFullYear() + '-' + String(date.getMonth()+1).padStart(2,'0') + '-' + String(date.getDate()).padStart(2,'0'); }
  if (!state.viewKey) state.viewKey = fmt(new Date());
  function parseKey(key) { return new Date(key + 'T00:00:00'); }
  function shortLabel(date) { return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }); }
  function isSameDay(a, b) { return a.getFullYear()===b.getFullYear() && a.getMonth()===b.getMonth() && a.getDate()===b.getDate(); }
  function showUpload() { uploadView.classList.remove('hidden'); dashboardView.classList.add('hidden'); }
  function showDashboard() { uploadView.classList.add('hidden'); dashboardView.classList.remove('hidden'); }
  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', 'light');
    const m = document.querySelector('meta[name="theme-color"]');
    if (m) m.setAttribute('content', '#AA98A9');
  }

  function looksLikeSqlite(bytes) {
    if (!bytes || bytes.length < 16) return false;
    let s = ''; for (let i = 0; i < 15; i++) s += String.fromCharCode(bytes[i]);
    return s === SQLITE_MAGIC;
  }
  function fileExt(name) { const n = (name||'').toLowerCase(); const i = n.lastIndexOf('.'); return i>=0 ? n.slice(i) : ''; }

  async function extractSqliteBytes(file) {
    const name = file.name || '';
    const buf = new Uint8Array(await file.arrayBuffer());
    if (looksLikeSqlite(buf)) return { bytes: buf, sourceName: name || 'Gadgetbridge' };
    const isZip = fileExt(name) === '.zip' || (buf[0] === 0x50 && buf[1] === 0x4b);
    if (!isZip) throw new Error('This file is not a Gadgetbridge zip or SQLite database.');
    if (typeof JSZip === 'undefined') throw new Error('Zip reader failed to load.');
    const zip = await JSZip.loadAsync(buf);
    const entries = [];
    zip.forEach((path, entry) => { if (!entry.dir) entries.push({ path, entry }); });
    function pathScore(path) {
      const p = path.replace(/\\/g,'/').toLowerCase();
      const base = p.split('/').pop();
      let s = 0;
      if (p.includes('/database/') || p.startsWith('database/')) s += 1000;
      if (base === 'gadgetbridge' || base === 'gadgetbridge.db') s += 200;
      if (p.includes('/files/') && p.includes('gadgetbridge_')) s -= 100;
      return s;
    }
    let best = null, bestScore = -1e9, bestSize = -1;
    for (const item of entries) {
      const score = pathScore(item.path);
      const bytes = await item.entry.async('uint8array');
      if (!looksLikeSqlite(bytes)) continue;
      if (score > bestScore || (score === bestScore && bytes.length > bestSize)) {
        best = { bytes, sourceName: item.path }; bestScore = score; bestSize = bytes.length;
      }
    }
    if (!best) throw new Error('No SQLite database found inside that zip.');
    return best;
  }

  let sqlJsPromise = null;
  function vendorUrl(file) { try { return new URL('vendor/' + file, window.location.href).toString(); } catch { return 'vendor/' + file; } }
  function loadScript(src) {
    return new Promise((resolve, reject) => {
      if (document.querySelector('script[src="'+src+'"]')) return resolve();
      const s = document.createElement('script'); s.src = src; s.onload = () => resolve(); s.onerror = () => reject(new Error('Could not load '+src));
      document.head.appendChild(s);
    });
  }
  function getSqlJs() {
    if (!sqlJsPromise) {
      sqlJsPromise = (async () => {
        if (typeof initSqlJs !== 'function') {
          try { await loadScript(vendorUrl('sql-wasm.js')); } catch { await loadScript(vendorUrl('sql-asm.js')); }
        }
        if (typeof initSqlJs !== 'function') throw new Error('SQLite engine did not start. Use npm start and open http://localhost:3000.');
        try { return await initSqlJs({ locateFile: (f) => vendorUrl(f) }); }
        catch { await loadScript(vendorUrl('sql-asm.js')); return await initSqlJs({ locateFile: (f) => vendorUrl(f) }); }
      })();
    }
    return sqlJsPromise;
  }

  function tableNames(db) {
    const t = db.exec("SELECT name FROM sqlite_master WHERE type='table'");
    return t.length ? t[0].values.map((r) => String(r[0])) : [];
  }
  function columnMap(db, table) {
    const info = db.exec('PRAGMA table_info("'+table+'")');
    const cols = {};
    if (info.length) info[0].values.forEach((r) => { cols[String(r[1]).toUpperCase()] = String(r[1]); });
    return cols;
  }
  function tableRowCount(db, name) {
    try { const c = db.exec('SELECT COUNT(*) FROM "'+name+'"'); return c.length ? Number(c[0].values[0][0])||0 : 0; } catch { return 0; }
  }
  function execRows(db, sql) { try { const r = db.exec(sql); return r.length ? r[0].values : []; } catch { return []; } }
  function findTable(db, regex) { return tableNames(db).find((n) => regex.test(n)) || null; }
  function pickCol(cols) { for (let i=1;i<arguments.length;i++) if (cols[arguments[i]]) return cols[arguments[i]]; return null; }

  function findActivitySampleTable(db) {
    const candidates = [];
    tableNames(db).forEach((name) => {
      const cols = columnMap(db, name);
      if (cols.TIMESTAMP && cols.STEPS && (cols.HEART_RATE || cols.HEARTRATE || cols.HR)) {
        const brand = /MI_BAND/i.test(name) ? 3 : /HUAMI/i.test(name) ? 2 : /XIAOMI/i.test(name) ? 1 : 0;
        candidates.push({ name, cols, rows: tableRowCount(db, name), brand, hasIntensity: !!(cols.RAW_INTENSITY||cols.INTENSITY), hasKind: !!cols.RAW_KIND });
      }
    });
    candidates.sort((a,b) => b.rows - a.rows || b.brand - a.brand);
    return candidates[0] || null;
  }
  // Raw activity kinds written by Gadgetbridge:
  //   Mi Band 5 / Amazfit Band 5 (HUAMI_EXTENDED_ACTIVITY_SAMPLE): 120 sleep, 121 deep, 122 REM, 115 not worn, 118 charging, 64 running
  //   Mi Band 2-4 / Bip / GTS (MI_BAND / HUAMI_ACTIVITY_SAMPLE):   9 light sleep, 11 deep sleep, 3 not worn, 6 charging
  // The old check used bit masks, which counted "not worn" (115) and "charging" (118) as sleep and ignored light sleep (9).
  function isSleepKind(kind) {
    const k = Number(kind);
    if (!Number.isFinite(k)) return false;
    return k === 120 || k === 121 || k === 122 || k === 9 || k === 11;
  }
  function isSleepStageValue(value) {
    if (value == null || value === '') return false;
    if (typeof value === 'string') {
      const text = value.trim().toLowerCase();
      if (!text || text === '0' || text === 'false' || text === 'awake' || text === 'not_asleep') return false;
      if (/sleep|deep|light|rem|asleep/.test(text)) return true;
    }
    const v = Number(value);
    return Number.isFinite(v) && v > 0;
  }
  function timestampToDate(timestamp) {
    const ts = Number(timestamp);
    if (!Number.isFinite(ts)) return null;
    const d = new Date(ts > 1e12 ? ts : ts * 1000);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  function emptyDay(key) {
    return { date:key, steps:0, calories:0, distanceMeters:0, sleepMinutes:0, sleepStart:null, sleepEnd:null,
      heartRateAvg:null, heartRateMin:null, heartRateMax:null, stressAvg:null, batteryLast:null,
      hourlySteps: Array(24).fill(0), hourlyHr: Array(24).fill(null), hrSeries: [],
      deviceIds:{}, notes:'', estimated:false,
      hrZoneMinutes: { low:0, moderate:0, high:0 }, bandPaiToday:null, bandPaiTotal:null };
  }
  function nightDateKey(d) { return d.getHours() >= 18 ? fmt(addDays(d,1)) : fmt(d); }
  function strideMeters(h) { return (Number(h)||170)*0.415/100; }

  // Personal HR baseline for the effort-zone classifier below. Percentile-
  // based and derived only from this person's own samples — never a fixed
  // "normal" heart rate, since that varies a lot person to person.
  function percentile(sortedArr, p) {
    if (!sortedArr.length) return null;
    const idx = Math.min(sortedArr.length - 1, Math.max(0, Math.floor(p * (sortedArr.length - 1))));
    return sortedArr[idx];
  }
  function computeHrBaseline(rows, hrColIndex) {
    const samples = [];
    for (const row of rows) {
      const hr = row[hrColIndex];
      if (hr && hr > 0 && hr < 254) samples.push(hr);
    }
    if (samples.length < 20) return null;
    samples.sort((a,b)=>a-b);
    const resting = percentile(samples, 0.05);
    const observedMax = percentile(samples, 0.95);
    if (observedMax - resting < 10) return null; // not enough range to classify meaningfully
    return { resting, observedMax };
  }
  // Effort-zone weights loosely inspired by the published PAI concept
  // (heart-rate-reserve zones, higher intensity worth more per minute) —
  // an approximation, not a reproduction of Huami's undocumented formula.
  function classifyHrZone(hr, baseline) {
    if (!baseline || hr == null) return null;
    const hrr = (hr - baseline.resting) / (baseline.observedMax - baseline.resting);
    if (hrr >= 0.7) return 'high';
    if (hrr >= 0.4) return 'moderate';
    if (hrr >= 0.15) return 'low';
    return null;
  }

  function aggregateFromSamples(rows, opts) {
    const dayMap = {};
    const ensure = (key) => { if (!dayMap[key]) dayMap[key] = emptyDay(key); return dayMap[key]; };
    const hrBaseline = computeHrBaseline(rows, 3);
    for (const row of rows) {
      const timestamp=row[0], rawIntensity=row[1], steps=Math.max(0, Number(row[2])||0), heartRate=row[3], rawKind=row[4], deviceId=row[5], extraDist=row[6], extraCal=row[7], sleep=row[8], deepSleep=row[9], remSleep=row[10];
      const d = timestampToDate(timestamp);
      if (!d) continue;
      const day = ensure(fmt(d));
      const hour = d.getHours();
      day.steps += steps;
      if (deviceId != null) day.deviceIds[deviceId] = true;
      day.hourlySteps[hour] += steps;
      if (extraDist) day.distanceMeters += opts.distanceIsCm ? extraDist/100 : extraDist;
      if (extraCal) day.calories += extraCal;
      if (heartRate && heartRate>0 && heartRate<254) {
        day._hrSum = (day._hrSum||0)+heartRate; day._hrCount=(day._hrCount||0)+1;
        day.heartRateMin = day.heartRateMin==null ? heartRate : Math.min(day.heartRateMin, heartRate);
        day.heartRateMax = day.heartRateMax==null ? heartRate : Math.max(day.heartRateMax, heartRate);
        if (!day._hrHourSum) { day._hrHourSum = Array(24).fill(0); day._hrHourCount = Array(24).fill(0); }
        day._hrHourSum[hour] += heartRate; day._hrHourCount[hour] += 1;
        if (!day._hrBuckets) day._hrBuckets = Array(72).fill(0).map(() => ({ s:0, c:0 }));
        const bucket = Math.min(71, Math.floor((hour * 60 + d.getMinutes()) / 20));
        day._hrBuckets[bucket].s += heartRate; day._hrBuckets[bucket].c += 1;
        const zone = classifyHrZone(heartRate, hrBaseline);
        if (zone) day.hrZoneMinutes[zone] += 1;
      }
      const explicitSleep = isSleepStageValue(sleep) || isSleepStageValue(deepSleep) || isSleepStageValue(remSleep);
      const kindSleep = opts.hasKind && isSleepKind(rawKind);
      const quiet = rawIntensity===0 || rawIntensity===null;
      if (explicitSleep || kindSleep || (!opts.hasKind && opts.hasIntensity && (hour>=18||hour<14) && quiet)) {
        const night = ensure(nightDateKey(d));
        night.sleepMinutes += 1;
        const iso = d.toISOString();
        if (!night.sleepStart || iso < night.sleepStart) night.sleepStart = iso;
        const endIso = new Date(d.getTime() + 60000).toISOString();
        if (!night.sleepEnd || endIso > night.sleepEnd) night.sleepEnd = endIso;
      }
    }
    const stride = strideMeters(opts.heightCm);
    Object.values(dayMap).forEach((day) => {
      day.heartRateAvg = day._hrCount ? Math.round(day._hrSum/day._hrCount) : null;
      day.hourlyHr = Array(24).fill(null);
      if (day._hrHourCount) {
        for (let h = 0; h < 24; h++) {
          if (day._hrHourCount[h]) day.hourlyHr[h] = Math.round(day._hrHourSum[h] / day._hrHourCount[h]);
        }
      }
      day.hrSeries = (day._hrBuckets || []).map((b) => b.c ? Math.round(b.s / b.c) : null);
      delete day._hrSum; delete day._hrCount; delete day._hrHourSum; delete day._hrHourCount; delete day._hrBuckets;
      if (!day.distanceMeters && day.steps) { day.distanceMeters = Math.round(day.steps*stride); day.estimated = true; }
      if (!day.calories && day.steps) { day.calories = Math.round(day.steps*0.04); day.estimated = true; }
    });
    return dayMap;
  }

  // Reads real PAI values straight from the band, when Gadgetbridge has
  // actually synced them (HUAMI_PAI_SAMPLE) — preferred over our estimate
  // whenever it's available.
  function readBandPai(db, dayMap) {
    const table = findTable(db, /^HUAMI_PAI_SAMPLE$/i);
    if (!table) return;
    const cols = columnMap(db, table);
    const ts = pickCol(cols,'TIMESTAMP'), today = pickCol(cols,'PAI_TODAY'), total = pickCol(cols,'PAI_TOTAL');
    if (!ts || !total) return;
    const rows = execRows(db, 'SELECT "'+ts+'", '+(today?'"'+today+'"':'NULL')+', "'+total+'" FROM "'+table+'" ORDER BY "'+ts+'"');
    rows.forEach(([timestamp, t, tot]) => {
      const d = timestampToDate(timestamp);
      if (!d) return;
      const key = fmt(d);
      if (!dayMap[key]) dayMap[key] = emptyDay(key);
      dayMap[key].bandPaiToday = t!=null ? Number(t) : dayMap[key].bandPaiToday;
      dayMap[key].bandPaiTotal = tot!=null ? Number(tot) : dayMap[key].bandPaiTotal;
    });
  }
  function addSeriesToDays(dayMap, rows, field) {
    for (const row of rows) {
      const ts=row[0], val=row[1];
      const d = timestampToDate(ts);
      if (!d || val==null) continue;
      const key = fmt(d);
      if (!dayMap[key]) dayMap[key] = emptyDay(key);
      if (field==='stress') { dayMap[key]._stSum=(dayMap[key]._stSum||0)+Number(val); dayMap[key]._stCount=(dayMap[key]._stCount||0)+1; }
      else dayMap[key].batteryLast = Number(val);
    }
    Object.values(dayMap).forEach((day) => {
      if (day._stCount) { day.stressAvg = Math.round(day._stSum/day._stCount); delete day._stSum; delete day._stCount; }
    });
  }

  function notifyAndroidWidget() {
    try {
      if (window.KestrelAndroidBridge && typeof window.KestrelAndroidBridge.syncHealthToWidget === 'function') {
        const todayKey = fmt(new Date());
        const today = (state.localDayIndex && state.localDayIndex[todayKey]) || {};
        const steps = Number(today.steps) || 0;
        const calories = Number(today.calories) || 0;
        const hr = Number(today.heartRateAvg) || Number(today.heartRateMax) || 0;
        const sleep = Number(today.sleepMinutes) || 0;
        const lastWorkout = (state.workouts && state.workouts.length) ? state.workouts[0] : null;
        const workoutName = lastWorkout ? (lastWorkout.name || 'Workout') : '';
        const workoutMinutes = lastWorkout ? (Number(lastWorkout.minutes) || 0) : 0;
        const streakEl = $('flame-count');
        const streak = streakEl ? (Number(streakEl.textContent) || 0) : 0;
        window.KestrelAndroidBridge.syncHealthToWidget(steps, calories, hr, sleep, workoutName, workoutMinutes, streak);
      }
    } catch(e) {
      console.warn('[Widget Sync Error]', e);
    }
  }

  function persistAll() {
    localStorage.setItem(STORAGE_DAYS, JSON.stringify(state.localDayIndex));
    localStorage.setItem(STORAGE_META, JSON.stringify(state.meta));
    localStorage.setItem(STORAGE_WORKOUTS, JSON.stringify(state.workouts));
    localStorage.setItem(STORAGE_DEVICES, JSON.stringify(state.devices));
    saveSettings();
    notifyAndroidWidget();
  }
  function loadPersisted() {
    try {
      const days = JSON.parse(localStorage.getItem(STORAGE_DAYS)||'null');
      if (!days || typeof days!=='object' || !Object.keys(days).length) return false;
      let purged = false;
      Object.keys(days).forEach((k) => {
        const d = days[k];
        if (d && d.deviceIds && d.deviceIds['mi-smart-band-5'] === true) { delete days[k]; purged = true; }
      });
      if (purged) {
        localStorage.setItem(STORAGE_DAYS, JSON.stringify(days));
        console.log('[Kestrel] Removed placeholder days created by the old demo sync.');
      }
      if (!Object.keys(days).length) return false;
      state.localDayIndex = days;
      state.meta = JSON.parse(localStorage.getItem(STORAGE_META)||'null');
      state.workouts = JSON.parse(localStorage.getItem(STORAGE_WORKOUTS)||'[]');
      state.devices = JSON.parse(localStorage.getItem(STORAGE_DEVICES)||'[]');
      return true;
    } catch { return false; }
  }
  function clearPersisted() {
    [STORAGE_DAYS,STORAGE_META,STORAGE_WORKOUTS,STORAGE_DEVICES].forEach((k)=>localStorage.removeItem(k));
    state.localDayIndex={}; state.workouts=[]; state.devices=[]; state.meta=null;
  }
  function mergeDays(existing, incoming) {
    const out = Object.assign({}, existing);
    Object.keys(incoming).forEach((key) => {
      const prev = out[key];
      out[key] = Object.assign(emptyDay(key), prev||{}, incoming[key], { notes: (prev && prev.notes) || incoming[key].notes || '' });
    });
    return out;
  }
  function mergeWorkouts(existing, incoming) {
    const map = {};
    const all = []
      .concat(Array.isArray(existing) ? existing : [])
      .concat(Array.isArray(incoming) ? incoming : [])
      .filter(Boolean);
    all.forEach((w) => {
      const key = String(w.id || w.start || Math.random());
      map[key] = Object.assign({}, map[key] || {}, w);
    });
    return Object.values(map).sort((a,b)=>(b.start||0)-(a.start||0));
  }

  function applyImported(payload, fileName) {
    if (!payload) return;
    if (payload.days) state.localDayIndex = mergeDays(state.localDayIndex, payload.days);
    if (payload.workouts) state.workouts = mergeWorkouts(state.workouts, payload.workouts);
    if (payload.devices && payload.devices.length) state.devices = payload.devices;
    if (payload.heightCm) state.heightCm = payload.heightCm;
    const allDates = Object.keys(state.localDayIndex).sort();
    state.meta = { fileName: fileName || 'Mi Smart Band 5', importedAt: new Date().toISOString(), dayCount: allDates.length, firstDate: allDates[0]||null, lastDate: allDates[allDates.length-1]||null };
    persistAll();
    if (state.meta.lastDate) {
      const last = parseKey(state.meta.lastDate);
      state.anchor = settings.range==='month' ? startOfMonth(last) : settings.range==='today' ? last : startOfWeek(last);
    }
    showDashboard();
    refresh();
    renderWorkouts();
    renderMuscleMap();
  }
  window.kestrelApplyBandPayload = applyImported;
  window.kestrelImportBandData = function(payload) {
    if (!payload) return;
    try {
      const data = typeof payload === 'string' ? JSON.parse(payload) : payload;
      applyImported(data, data.fileName || 'Mi Smart Band 5');
    } catch (e) {
      console.warn('[kestrelImportBandData error]', e);
    }
  };
  // ---- Real Bluetooth history from the native app (minute rows straight from the band) ----
  // Rows: [timestamp, rawIntensity, steps, heartRate, rawKind]. Aggregated by the exact same code
  // that handles Gadgetbridge exports, so charts / sleep / HR series are computed identically.
  let bandRowBuffer = [];
  window.kestrelBandSamplesBegin = function() { bandRowBuffer = []; };
  window.kestrelBandSamplesChunk = function(rows) {
    try {
      const list = typeof rows === 'string' ? JSON.parse(rows) : rows;
      if (Array.isArray(list)) for (let i = 0; i < list.length; i++) bandRowBuffer.push(list[i]);
    } catch (e) { console.warn('[kestrelBandSamplesChunk]', e); }
  };
  window.kestrelBandSamplesEnd = function(meta) {
    try {
      const info = (typeof meta === 'string' ? JSON.parse(meta) : meta) || {};
      const rows = bandRowBuffer; bandRowBuffer = [];
      if (!rows.length) {
        if (info.devices && info.devices.length) { state.devices = info.devices; persistAll(); refresh(); }
        return;
      }
      const incoming = aggregateFromSamples(rows, { hasIntensity: true, hasKind: true, heightCm: state.heightCm || 172, distanceIsCm: false });
      const merged = {};
      Object.keys(incoming).forEach((key) => {
        const inc = incoming[key];
        const prev = state.localDayIndex[key];
        const day = Object.assign(emptyDay(key), prev || {});
        // Steps: never replace a fuller day (e.g. from a Gadgetbridge zip) with a partial one.
        if (!prev || (inc.steps || 0) >= (prev.steps || 0)) {
          day.steps = inc.steps; day.hourlySteps = inc.hourlySteps;
          day.calories = inc.calories; day.distanceMeters = inc.distanceMeters; day.estimated = inc.estimated;
        }
        if (inc.heartRateAvg != null) {
          day.heartRateAvg = inc.heartRateAvg; day.heartRateMin = inc.heartRateMin; day.heartRateMax = inc.heartRateMax;
          day.hourlyHr = inc.hourlyHr; day.hrSeries = inc.hrSeries; day.hrZoneMinutes = inc.hrZoneMinutes;
        }
        if (inc.sleepMinutes > 0 && (!prev || inc.sleepMinutes >= (prev.sleepMinutes || 0))) {
          day.sleepMinutes = inc.sleepMinutes; day.sleepStart = inc.sleepStart; day.sleepEnd = inc.sleepEnd;
        }
        if (info.battery != null && key === fmt(new Date())) day.batteryLast = info.battery;
        day.deviceIds = Object.assign({}, day.deviceIds || {}, { 'mi-smart-band-5': 1 });
        merged[key] = day;
      });
      applyImported({ days: merged, devices: info.devices }, 'Mi Smart Band 5');
    } catch (e) {
      console.warn('[kestrelBandSamplesEnd error]', e);
    }
  };
  // Live values while connected. Steps/battery go into today's record; heart rate only updates the readout
  // (history comes from the band's own samples, so live pings never skew averages).
  window.kestrelLiveMetric = function(type, val) {
    try {
      const v = Number(val);
      if (!Number.isFinite(v)) return;
      const key = fmt(new Date());
      if (type === 'heart_rate') {
        if (v > 30 && v < 220) {
          const el = $('home-hr'); if (el) el.textContent = String(Math.round(v));
          const pill = $('home-hr-status'); if (pill) pill.textContent = v < 60 ? 'Resting' : v < 100 ? 'Normal' : v < 140 ? 'Active' : 'High';
        }
        return;
      }
      const day = state.localDayIndex[key] || (state.localDayIndex[key] = emptyDay(key));
      let changed = false;
      if (type === 'steps' && v >= 0 && v <= 150000 && v > (day.steps || 0)) {
        day.steps = Math.round(v);
        day.distanceMeters = Math.round(v * strideMeters(state.heightCm));
        day.calories = Math.round(v * 0.04);
        day.estimated = true;
        changed = true;
      } else if (type === 'battery' && v >= 0 && v <= 100 && day.batteryLast !== v) {
        day.batteryLast = v; changed = true;
      }
      if (changed) { persistAll(); refresh(); }
    } catch (e) { console.warn('[kestrelLiveMetric]', e); }
  };
  // Per-day stress and sleep-stage values computed natively from the band's samples.
  window.kestrelBandExtras = function(map) {
    try {
      const m = typeof map === 'string' ? JSON.parse(map) : map;
      Object.keys(m || {}).forEach((key) => {
        const e = m[key];
        const day = state.localDayIndex[key] || (state.localDayIndex[key] = emptyDay(key));
        if (e.stressAvg != null) { day.stressAvg = e.stressAvg; day.stressMax = e.stressMax; }
        if (e.paiToday != null) { day.bandPaiToday = e.paiToday; day.bandPaiTotal = e.paiTotal; }
        if (e.sleepMinutes > 0 && e.sleepStart && e.sleepEnd) {
          day.sleepMinutes = e.sleepMinutes; day.sleepStart = e.sleepStart; day.sleepEnd = e.sleepEnd;
        }
        if (e.deepSleepMinutes != null) {
          day.deepSleepMinutes = e.deepSleepMinutes; day.lightSleepMinutes = e.lightSleepMinutes; day.remSleepMinutes = e.remSleepMinutes;
        }
      });
      persistAll(); refresh();
    } catch (e) { console.warn('[kestrelBandExtras]', e); }
  };
  // Workouts recorded on the band itself (not synced as "single" ones; merged by id so re-sync never duplicates).
  window.kestrelBandWorkouts = function(list) {
    try {
      const arr = typeof list === 'string' ? JSON.parse(list) : list;
      if (!Array.isArray(arr) || !arr.length) return;
      state.workouts = mergeWorkouts(state.workouts, arr);
      persistAll();
      renderWorkouts();
      renderMuscleMap();
      if (typeof refresh === 'function') refresh();
    } catch (e) { console.warn('[kestrelBandWorkouts]', e); }
  };
  window.kestrelApp = {
    get state() { return state; },
    get settings() { return settings; },
    persistAll: () => persistAll(),
    refresh: () => refresh(),
    saveSettings: () => saveSettings(),
    fmt: (d) => fmt(d),
    parseKey: (k) => parseKey(k),
    addDays: (d, n) => addDays(d, n),
    mergeWorkouts: (a, b) => mergeWorkouts(a, b),
    renderWorkouts: () => { try { renderWorkouts(); renderMuscleMap(); } catch (e) {} }
  };
  window.kestrelAddSingleWorkout = function(w) {
    if (!w) return;
    state.workouts = mergeWorkouts(state.workouts, [w]);
    persistAll();
    renderWorkouts();
    renderMuscleMap();
    if (typeof refresh === 'function') refresh();
  };
  window.kestrelState = state;

  async function parseExportFile(file) {
    const extracted = await extractSqliteBytes(file);
    const SQL = await getSqlJs();
    const db = new SQL.Database(extracted.bytes);
    try {
      const table = findActivitySampleTable(db);
      if (!table) throw new Error('No activity table with TIMESTAMP / STEPS / HEART_RATE.');
      const ts = pickCol(table.cols,'TIMESTAMP');
      const steps = pickCol(table.cols,'STEPS');
      const hr = pickCol(table.cols,'HEART_RATE','HEARTRATE','HR');
      const intensity = pickCol(table.cols,'RAW_INTENSITY','INTENSITY');
      const kind = pickCol(table.cols,'RAW_KIND');
      const sleepCol = pickCol(table.cols,'SLEEP');
      const deepSleepCol = pickCol(table.cols,'DEEP_SLEEP');
      const remSleepCol = pickCol(table.cols,'REM_SLEEP');
      const deviceCol = pickCol(table.cols,'DEVICE_ID');
      const distCol = pickCol(table.cols,'DISTANCE_METERS','DISTANCE','DISTANCE_CM');
      const calCol = pickCol(table.cols,'CALORIES','ACTIVE_CALORIES');
      const result = db.exec('SELECT "'+ts+'", '+(intensity?'"'+intensity+'"':'NULL')+', "'+steps+'", "'+hr+'", '+(kind?'"'+kind+'"':'NULL')+', '+(deviceCol?'"'+deviceCol+'"':'NULL')+', '+(distCol?'"'+distCol+'"':'NULL')+', '+(calCol?'"'+calCol+'"':'NULL')+', '+(sleepCol?'"'+sleepCol+'"':'NULL')+', '+(deepSleepCol?'"'+deepSleepCol+'"':'NULL')+', '+(remSleepCol?'"'+remSleepCol+'"':'NULL')+' FROM "'+table.name+'" ORDER BY "'+ts+'"');
      if (!result.length || !result[0].values.length) throw new Error('No rows in '+table.name+' (inside '+extracted.sourceName+').');
      let heightCm = 170;
      const userAttr = findTable(db, /^USER_ATTRIBUTES$/i);
      if (userAttr) {
        const hcol = pickCol(columnMap(db,userAttr),'HEIGHT_CM');
        if (hcol) { const h = execRows(db,'SELECT "'+hcol+'" FROM "'+userAttr+'" LIMIT 1'); if (h.length && h[0][0]) heightCm = Number(h[0][0])||170; }
      }
      const days = aggregateFromSamples(result[0].values, { hasIntensity:!!intensity, hasKind:!!kind, heightCm, distanceIsCm: !!(distCol && /CM/i.test(distCol)) });
      readBandPai(db, days);
      const stressTable = findTable(db,/STRESS_SAMPLE/i);
      if (stressTable) {
        const sc=columnMap(db,stressTable); const sts=pickCol(sc,'TIMESTAMP'); const sval=pickCol(sc,'STRESS');
        if (sts && sval) addSeriesToDays(days, execRows(db,'SELECT "'+sts+'", "'+sval+'" FROM "'+stressTable+'"'), 'stress');
      }
      const batTable = findTable(db,/^BATTERY_LEVEL$/i);
      if (batTable) {
        const bc=columnMap(db,batTable); const bts=pickCol(bc,'TIMESTAMP'); const bval=pickCol(bc,'LEVEL');
        if (bts && bval) addSeriesToDays(days, execRows(db,'SELECT "'+bts+'", "'+bval+'" FROM "'+batTable+'" ORDER BY "'+bts+'"'), 'battery');
      }
      const devices = [];
      const devTable = findTable(db,/^DEVICE$/i);
      if (devTable) {
        const dc=columnMap(db,devTable);
        execRows(db,'SELECT '+ (pickCol(dc,'_ID','ID')||'_id') +', '+(pickCol(dc,'NAME','ALIAS')||'NAME')+', '+(pickCol(dc,'TYPE_NAME','MODEL')||'NULL')+' FROM "'+devTable+'"').forEach((r)=>{
          devices.push({ id:r[0], name:r[1]||'Band', type:r[2]||'' });
        });
      }
      const workouts = [];
      const sumTable = findTable(db,/^(BASE_)?ACTIVITY_SUMMARY$/i);
      if (sumTable) {
        const sc=columnMap(db,sumTable);
        const startc=pickCol(sc,'START_TIME'); const endc=pickCol(sc,'END_TIME');
        const calc=pickCol(sc,'CALORIES','ACTIVE_CALORIES','BASE_CALORIES');
        const distc=pickCol(sc,'DISTANCE_METERS','DISTANCE');
        const stepsc=pickCol(sc,'STEPS');
        const hrc=pickCol(sc,'HEART_RATE_AVG','AVG_HR','HR_AVG','HEART_RATE');
        execRows(db,'SELECT '+(pickCol(sc,'_ID')||'NULL')+', '+(pickCol(sc,'NAME')||'NULL')+', '+startc+', '+endc+', '+(pickCol(sc,'ACTIVITY_KIND')||'NULL')+', '+(pickCol(sc,'DEVICE_ID')||'NULL')+(calc?', '+calc:' ,NULL')+(distc?', '+distc:' ,NULL')+(stepsc?', '+stepsc:' ,NULL')+(hrc?', '+hrc:' ,NULL')+' FROM "'+sumTable+'"').forEach((r)=>{
          const start=Number(r[2]), end=Number(r[3]);
          if (!start) return;
          workouts.push({
            id: r[0] || ('gb-'+start),
            name: r[1] || ACTIVITY_KIND[r[4]] || 'Workout',
            start,
            end,
            kind: r[4],
            deviceId: r[5],
            minutes: end ? Math.max(1,Math.round((end-start)/60000)) : null,
            calories: r[6] ? Number(r[6]) : null,
            distanceMeters: r[7] ? Number(r[7]) : null,
            steps: r[8] ? Number(r[8]) : null,
            heartRateAvg: r[9] ? Number(r[9]) : null
          });
        });
      }
      return { days, workouts, devices, heightCm };
    } finally { db.close(); }
  }

  function visibleDays() {
    if (settings.deviceId==='all') return state.localDayIndex;
    const filtered = {};
    Object.keys(state.localDayIndex).forEach((k) => {
      const d = state.localDayIndex[k];
      if (d.deviceIds && d.deviceIds[settings.deviceId]) filtered[k]=d;
      else if (!d.deviceIds || !Object.keys(d.deviceIds).length) filtered[k]=d;
    });
    return filtered;
  }
  function rangeDates() {
    const anchor = new Date(state.anchor); anchor.setHours(0,0,0,0);
    if (settings.range==='today') return [new Date(anchor)];
    if (settings.range==='month') {
      const start=startOfMonth(anchor), end=startOfMonth(addDays(start,32)), days=[];
      for (let d=new Date(start); d<end; d=addDays(d,1)) days.push(new Date(d));
      return days;
    }
    const start=startOfWeek(anchor);
    return Array.from({length:7},(_,i)=>addDays(start,i));
  }
  function compareDates() { return []; }
  function dayOrEmpty(index, key) { return index[key] || emptyDay(key); }

  function computeStreak(index, goal) {
    const dates = Object.keys(index).sort();
    if (!dates.length) return 0;
    const today = fmt(new Date());
    let cursor = dates[dates.length-1] > today ? today : dates[dates.length-1];
    if (cursor===today && (index[today]?.steps||0) < goal) cursor = fmt(addDays(parseKey(today),-1));
    let streak=0;
    while (index[cursor] && (index[cursor].steps||0)>=goal) { streak++; cursor = fmt(addDays(parseKey(cursor),-1)); }
    return streak;
  }
  function records(index) {
    const days = Object.values(index).filter((d)=>d.steps||d.sleepMinutes||d.heartRateAvg);
    if (!days.length) return [];
    const bestSteps = days.reduce((a,d)=>((d.steps||0)>(a.steps||0)?d:a));
    const sleepDays = days.filter((d)=>d.sleepMinutes>0);
    const bestSleep = sleepDays.length ? sleepDays.reduce((a,d)=>d.sleepMinutes>a.sleepMinutes?d:a) : null;
    const hrDays = days.filter((d)=>d.heartRateAvg);
    const lowestHr = hrDays.length ? hrDays.reduce((a,d)=>d.heartRateAvg<a.heartRateAvg?d:a) : null;
    const out = [{ label:'Most steps', value:(bestSteps.steps||0).toLocaleString()+' · '+bestSteps.date }];
    if (bestSleep) out.push({ label:'Longest sleep', value:(bestSleep.sleepMinutes/60).toFixed(1)+'h · '+bestSleep.date });
    if (lowestHr) out.push({ label:'Lowest avg HR', value:lowestHr.heartRateAvg+' bpm · '+lowestHr.date });
    return out;
  }
  function insightFor(days) {
    const withSteps = days.filter((d)=>d.steps>0);
    if (!withSteps.length) return 'No step data in this range. Try another span, or add a newer Gadgetbridge export.';
    const avgSteps = Math.round(withSteps.reduce((a,d)=>a+d.steps,0)/withSteps.length);
    const best = withSteps.reduce((a,d)=>d.steps>a.steps?d:a);
    const goalHits = withSteps.filter((d)=>d.steps>=settings.stepGoal).length;
    const sleepDays = days.filter((d)=>d.sleepMinutes>0);
    const avgSleepHrs = sleepDays.length ? sleepDays.reduce((a,d)=>a+d.sleepMinutes,0)/sleepDays.length/60 : null;
    const weekday = withSteps.filter((d)=>[1,2,3,4,5].includes(parseKey(d.date).getDay()));
    const weekend = withSteps.filter((d)=>[0,6].includes(parseKey(d.date).getDay()));
    const wdAvg = weekday.length ? Math.round(weekday.reduce((a,d)=>a+d.steps,0)/weekday.length) : null;
    const weAvg = weekend.length ? Math.round(weekend.reduce((a,d)=>a+d.steps,0)/weekend.length) : null;
    const evening = days.reduce((s,d)=>s+(d.hourlySteps||[]).slice(18).reduce((a,b)=>a+b,0),0);
    const totalSteps = days.reduce((s,d)=>s+(d.steps||0),0);
    const eveningShare = totalSteps ? Math.round(evening/totalSteps*100) : 0;
    const hrDays = days.filter((d)=>d.heartRateAvg);
    const avgHr = hrDays.length ? Math.round(hrDays.reduce((a,d)=>a+d.heartRateAvg,0)/hrDays.length) : null;
    const hrHigh = hrDays.length ? hrDays.reduce((a,d)=>d.heartRateAvg>a.heartRateAvg?d:a) : null;
    const parts = [
      'Averaging '+avgSteps.toLocaleString()+' steps/day.',
      'Best day '+best.date+' with '+best.steps.toLocaleString()+' steps.',
      'Hit your '+settings.stepGoal.toLocaleString()+' goal on '+goalHits+'/'+withSteps.length+' active days.'
    ];
    if (avgSleepHrs!==null) parts.push('Nights average '+avgSleepHrs.toFixed(1)+'h (counted toward the morning you woke).');
    if (wdAvg && weAvg) parts.push(weAvg>wdAvg ? 'Weekends are busier ('+weAvg.toLocaleString()+' vs '+wdAvg.toLocaleString()+' on weekdays).' : 'Weekdays are busier ('+wdAvg.toLocaleString()+' vs '+weAvg.toLocaleString()+' on weekends).');
    if (eveningShare>=40) parts.push(eveningShare+'% of steps happen after 6pm.');
    if (avgHr!==null) {
      parts.push('Average heart rate '+avgHr+' bpm across days with a reading.');
      if (hrHigh && hrDays.length>1 && hrHigh.heartRateAvg > avgHr*1.1) {
        parts.push(hrHigh.date+' ran higher than usual ('+hrHigh.heartRateAvg+' bpm avg) — worth a note if that lines up with something specific.');
      }
    }
    return parts.join(' ');
  }

  // Composite 0-100 "day score": how the selected day compares to the
  // person's own recent history (not a fixed medical threshold — heart
  // rate baselines vary a lot person to person, so we only ever compare
  // someone against their own trailing average).
  function computeDailyScore(day, allDaysIndex) {
    const parts = [];
    let haveAny = false;

    // Steps vs personal goal
    const goal = settings.stepGoal || 8000;
    if (day.steps) {
      haveAny = true;
      const pct = Math.max(0, Math.min(1, day.steps / goal));
      parts.push({ name: 'Steps', pct, weight: 0.4 });
    }

    // Sleep vs a 7-9h window (general public-health guidance range, not a
    // personal diagnosis)
    const sleepHrs = (day.sleepMinutes || 0) / 60;
    if (day.sleepMinutes) {
      haveAny = true;
      const distanceFromWindow = sleepHrs < 7 ? (7 - sleepHrs) : sleepHrs > 9 ? (sleepHrs - 9) : 0;
      const pct = Math.max(0, 1 - distanceFromWindow / 3);
      parts.push({ name: 'Sleep', pct, weight: 0.3 });
    }

    // Heart rate vs this person's own trailing average (last 14 days,
    // excluding the selected day) — never compared to a fixed number.
    if (day.heartRateAvg) {
      const baselineDays = Object.keys(allDaysIndex)
        .filter((k) => k !== day.date && allDaysIndex[k].heartRateAvg)
        .sort()
        .slice(-14)
        .map((k) => allDaysIndex[k].heartRateAvg);
      if (baselineDays.length >= 2) {
        haveAny = true;
        const baseline = baselineDays.reduce((a,b)=>a+b,0) / baselineDays.length;
        const ratio = baseline / day.heartRateAvg; // >=1 means at/below your own average
        const pct = Math.max(0, Math.min(1, ratio > 1 ? 1 : 1 - (1 - ratio) * 2));
        parts.push({ name: 'Heart rate', pct, weight: 0.3 });
      }
    }

    if (!haveAny || !parts.length) return null;

    const totalWeight = parts.reduce((a,p)=>a+p.weight, 0);
    const score = Math.round(parts.reduce((a,p)=>a + p.pct * p.weight, 0) / totalWeight * 100);
    let label;
    if (score >= 80) label = 'Strong day overall.';
    else if (score >= 60) label = 'Solid day, room to push a bit.';
    else if (score >= 40) label = 'Below your usual — one or two areas lagging.';
    else label = 'Quiet day across the board.';
    return { score, label, parts };
  }

  // Rolling 7-day PAI-style total ending on `throughKey`. Prefers real
  // band-synced PAI_TOTAL when every day in the window has one; otherwise
  // sums our own zone-weighted estimate. Weights (low=0.5, moderate=1.5,
  // high=3 effort-minutes; ~150 effort-minutes/week ≈ 100 points) are a
  // deliberately simple approximation, not Huami's real formula.
  function rollingPaiScore(index, throughKey) {
    const through = parseKey(throughKey);
    const weekDates = []; for (let i=6;i>=0;i--) weekDates.push(fmt(addDays(through,-i)));
    const weekDays = weekDates.map((k)=>index[k]).filter(Boolean);
    if (!weekDays.length) return null;

    const bandDay = index[throughKey];
    if (bandDay && bandDay.bandPaiTotal != null) {
      return { score: Math.round(bandDay.bandPaiTotal), source: 'band', zoneMinutes: null };
    }

    let low=0, moderate=0, high=0, any=false;
    weekDays.forEach((d) => {
      const z = d.hrZoneMinutes;
      if (!z) return;
      any = true;
      low += z.low||0; moderate += z.moderate||0; high += z.high||0;
    });
    if (!any || (low+moderate+high)===0) return null;
    const weighted = low*0.5 + moderate*1.5 + high*3;
    const score = Math.round(weighted / 1.5);
    return { score, source: 'estimate', zoneMinutes: { low, moderate, high } };
  }

  function renderPai() {
    const day = selectedDay();
    const result = rollingPaiScore(state.localDayIndex, day.date);
    const valueEl = $('pai-value'), labelEl = $('pai-label'), barsEl = $('pai-bars');
    if (!result) {
      valueEl.textContent = '–';
      labelEl.textContent = 'Not enough heart-rate history yet for a 7-day PAI-style total.';
      barsEl.innerHTML = '';
      return;
    }
    valueEl.textContent = result.score;
    if (result.source === 'band') {
      labelEl.textContent = 'Synced from your band · 7 days through ' + day.date;
      barsEl.innerHTML = '';
      return;
    }
    labelEl.textContent = (result.score >= 100 ? 'At or above the 100/week target (estimate).' : 'Estimate · target is 100/week.') + ' 7 days through ' + day.date;
    const z = result.zoneMinutes;
    const total = Math.max(1, z.low + z.moderate + z.high);
    barsEl.innerHTML = '';
    [['Low', z.low], ['Moderate', z.moderate], ['High', z.high]].forEach(([name, mins]) => {
      const pct = Math.round(mins/total*100);
      const row = document.createElement('div');
      row.className = 'score-bar-row';
      row.innerHTML = '<span class="score-bar-name">'+name+'</span>'+
        '<span class="score-bar-track"><span class="score-bar-fill" style="width:'+pct+'%"></span></span>'+
        '<span class="score-bar-pct">'+mins+'m</span>';
      barsEl.appendChild(row);
    });
  }

  function renderScore() {
    const day = selectedDay();
    const result = computeDailyScore(day, state.localDayIndex);
    const barsEl = $('score-bars');
    if (!result) {
      $('score-value').textContent = '–';
      $('score-label').textContent = 'Not enough data yet for ' + day.date + ' — import more history or connect live.';
      barsEl.innerHTML = '';
      return;
    }
    $('score-value').textContent = result.score;
    $('score-label').textContent = result.label + ' (' + day.date + ')';
    barsEl.innerHTML = '';
    result.parts.forEach((p) => {
      const row = document.createElement('div');
      row.className = 'score-bar-row';
      const pct = Math.round(p.pct * 100);
      row.innerHTML = '<span class="score-bar-name">'+p.name+'</span>'+
        '<span class="score-bar-track"><span class="score-bar-fill" style="width:'+pct+'%"></span></span>'+
        '<span class="score-bar-pct">'+pct+'%</span>';
      barsEl.appendChild(row);
    });
  }

  function selectedDay() {
    if (state.viewKey) {
      const hit = state.series.find((d) => d.date === state.viewKey);
      if (hit) return hit;
      return dayOrEmpty(visibleDays(), state.viewKey);
    }
    return state.series[state.selectedIndex] || emptyDay(fmt(new Date()));
  }
  function viewedDay(index) {
    return dayOrEmpty(index || visibleDays(), state.viewKey || fmt(new Date()));
  }
  function storedDateBounds() {
    const keys = Object.keys(state.localDayIndex || {}).sort();
    return { min: keys[0] || '2020-01-01', max: fmt(new Date()) };
  }
  function syncHomeDateInput(key) {
    const input = $('home-date');
    const label = $('home-date-label');
    if (!input) return;
    const bounds = storedDateBounds();
    input.min = bounds.min;
    input.max = bounds.max;
    input.value = key;
    const todayKey = fmt(new Date());
    if (label) label.textContent = key === todayKey ? 'Today' : shortLabel(parseKey(key));
  }
  function animateMetricCardsOnDateChange() {
    const cards = document.querySelectorAll('.metric-card, .sheet-stack, .hero-metrics');
    if (!cards.length) return;
    cards.forEach((card) => {
      card.classList.remove('card-fade-update');
      // Trigger reflow to restart CSS animation cleanly
      void card.offsetWidth;
      card.classList.add('card-fade-update');
    });
  }

  function setViewKey(key) {
    const bounds = storedDateBounds();
    if (key < bounds.min) key = bounds.min;
    if (key > bounds.max) key = bounds.max;
    state.viewKey = key;
    const idx = state.series.findIndex((d) => d.date === key);
    if (idx >= 0) state.selectedIndex = idx;
    renderToday(visibleDays());
    renderDayRow();
    renderChart();
    renderHourly();
    renderPills();
    renderNote();
    renderScore();
    renderAuraUI();
    renderStrain();
    animateMetricCardsOnDateChange();
  }

  function refresh() {
    const index = visibleDays();
    const dates = rangeDates();
    state.series = dates.map((d)=>dayOrEmpty(index, fmt(d)));
    state.compareSeries = compareDates(dates).map((d)=>dayOrEmpty(index, fmt(d)));
    const todayKey = fmt(new Date());
    if (!state.viewKey) state.viewKey = todayKey;
    let di = state.series.findIndex((d)=>d.date===state.viewKey);
    if (di<0) di = state.series.findIndex((d)=>d.date===todayKey);
    if (di<0) di = state.series.length-1;
    state.selectedIndex = Math.max(0, di);
    const start=dates[0], end=dates[dates.length-1];
    dateRangeLabel.textContent = settings.range==='today' ? shortLabel(start) : shortLabel(start)+' – '+shortLabel(end);
    if (state.meta) {
      const when = state.meta.importedAt ? new Date(state.meta.importedAt).toLocaleString() : '';
      importMeta.textContent = state.meta.dayCount+' days stored'+(state.meta.fileName?' · last file '+state.meta.fileName:'')+(when?' · '+when:'');
    }
    const last = state.meta && state.meta.lastDate;
    $('stale-banner').classList.toggle('hidden', !(last && (new Date()-parseKey(last))/86400000 > 2));
    renderToday(index); renderDevices(); renderDayRow(); renderChart(); renderHourly(); renderPills(); renderRecords(index); renderWorkouts(); renderNote(); renderScore(); renderAuraUI();
    insightText.textContent = insightFor(state.series);
    try { renderStrain(); renderMuscleMap(); renderJournal(); renderCorrelations(); renderBadges(); } catch (err) { console.error('phase 1/2 render failed', err); }
    try { document.dispatchEvent(new CustomEvent('kestrel:data')); } catch (e) {}
    try { countUpAll(); } catch (e) {}
  }

  function renderToday(index) {
    const today = viewedDay(index);
    const goal = settings.stepGoal||8000;
    const pct = Math.min(100, Math.round((today.steps||0)/goal*100));
    $('today-steps').textContent = (today.steps||0).toLocaleString();
    if ($('today-sub')) $('today-sub').textContent = 'steps';
    $('goal-pct').textContent = pct+'%';
    const circ = 2*Math.PI*30;
    $('goal-ring').style.strokeDasharray = String(circ);
    $('goal-ring').style.strokeDashoffset = String(circ*(1-pct/100));
    const bits = [];
    if (today.heartRateAvg) bits.push(today.heartRateAvg+' bpm');
    if (today.sleepMinutes) bits.push((today.sleepMinutes/60).toFixed(1)+'h sleep');
    if (today.batteryLast!=null) bits.push('battery '+today.batteryLast+'%');
    $('today-meta').textContent = bits.join(' · ') || 'No samples for today yet';
    const streak = computeStreak(index, goal);
    $('streak-chip').textContent = streak ? streak+'-day goal streak' : 'No goal streak yet';
  }
  function renderDevices() {
    const current = settings.deviceId;
    deviceSelect.innerHTML = '';
    const all = document.createElement('option'); all.value='all'; all.textContent='All bands'; deviceSelect.appendChild(all);
    state.devices.forEach((d) => { const o=document.createElement('option'); o.value=String(d.id); o.textContent=d.name+(d.type?' ('+d.type+')':''); deviceSelect.appendChild(o); });
    deviceSelect.value = current;
    if (deviceSelect.value!==current) { settings.deviceId='all'; deviceSelect.value='all'; }
    deviceSelect.parentElement.classList.toggle('hidden', state.devices.length<=1);
  }
  function renderDayRow() {
    dayRow.innerHTML=''; dayRow.classList.toggle('month', settings.range==='month');
    const today=new Date();
    state.series.forEach((d,i) => {
      const btn=document.createElement('button'); btn.className='day-pill';
      const date=parseKey(d.date);
      btn.textContent = (settings.range==='month'||settings.range==='today') ? String(date.getDate()) : DAY_LETTERS[date.getDay()];
      if (isSameDay(date,today)) btn.classList.add('today');
      if (i===state.selectedIndex) btn.classList.add('selected');
      btn.addEventListener('click', () => { state.selectedIndex=i; state.viewKey=d.date; renderDayRow(); renderChart(); renderHourly(); renderPills(); renderNote(); renderToday(visibleDays()); renderAuraUI(); renderStrain(); });
      dayRow.appendChild(btn);
    });
  }
  function smoothPath(points) {
    if (!points.length) return '';
    if (points.length<2) return 'M '+points[0].x+' '+points[0].y;
    let d='M '+points[0].x+' '+points[0].y;
    for (let i=0;i<points.length-1;i++) {
      const p0=points[i===0?i:i-1], p1=points[i], p2=points[i+1], p3=points[i+2<points.length?i+2:i+1];
      d += ' C '+(p1.x+(p2.x-p0.x)/6)+' '+(p1.y+(p2.y-p0.y)/6)+', '+(p2.x-(p3.x-p1.x)/6)+' '+(p2.y-(p3.y-p1.y)/6)+', '+p2.x+' '+p2.y;
    }
    return d;
  }
  function pointsFrom(values) {
    const max=Math.max.apply(null, values.concat([1]));
    if (values.length===1) return [{x:160,y:110-(values[0]/max)*96}];
    return values.map((v,i)=>({x:(i/(values.length-1))*320,y:110-(v/max)*96}));
  }
  function renderChart() {
    const meta = METRIC_META[state.metric];
    chartTitle.textContent = meta.title;
    const points = pointsFrom(state.series.map((d)=>meta.get(d)||0));
    chartPath.setAttribute('d', smoothPath(points));
    chartPathPrev.setAttribute('d', '');
    const sel = points[state.selectedIndex] || points[points.length-1];
    if (sel) {
      chartDrop.setAttribute('d','M '+sel.x+' '+sel.y+' L '+sel.x+' 110');
      chartDot.setAttribute('cx',sel.x); chartDot.setAttribute('cy',sel.y);
      chartDotHalo.setAttribute('cx',sel.x); chartDotHalo.setAttribute('cy',sel.y);
    }
  }
  function renderHourly() {
    const day = selectedDay();
    hourlyTitle.textContent = 'Hourly steps · '+day.date;
    const hours = day.hourlySteps || Array(24).fill(0);
    const max = Math.max.apply(null, hours.concat([1]));
    hourlyBars.innerHTML='';
    hours.forEach((v,i) => {
      const h=(v/max)*88;
      const rect=document.createElementNS('http://www.w3.org/2000/svg','rect');
      rect.setAttribute('x', String(6+i*(308/24)));
      rect.setAttribute('y', String(96-h));
      rect.setAttribute('width','8');
      rect.setAttribute('height', String(Math.max(h,1)));
      rect.setAttribute('class','hourly-bar');
      hourlyBars.appendChild(rect);
    });
  }
  function pctChip(curr, prev) {
    if (!prev) return '—';
    const pct=Math.round((curr-prev)/prev*100);
    if (pct===0) return '0%';
    return pct>0 ? '↑ '+pct+'%' : '↓ '+Math.abs(pct)+'%';
  }
  function renderPills() {
    const day=selectedDay(); const prev=state.series[state.selectedIndex-1];
    $('val-steps').textContent=(day.steps||0).toLocaleString();
    $('chip-steps').textContent=pctChip(day.steps||0, prev?prev.steps:null);
    $('val-hr').textContent=day.heartRateAvg||'–';
    $('chip-hr').textContent=day.heartRateMin&&day.heartRateMax ? (day.heartRateMin+'–'+day.heartRateMax) : pctChip(day.heartRateAvg||0, prev?prev.heartRateAvg:null);
    const sleepHrs=+(((day.sleepMinutes||0)/60).toFixed(1));
    $('val-sleep').textContent=sleepHrs||'–';
    let sleepChip=pctChip(sleepHrs, prev?+(((prev.sleepMinutes||0)/60).toFixed(1)):null);
    if (day.sleepStart && day.sleepEnd) {
      const a=new Date(day.sleepStart), b=new Date(day.sleepEnd);
      sleepChip=a.toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'})+'–'+b.toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});
    }
    $('chip-sleep').textContent=sleepChip;
    $('val-stress').textContent=day.stressAvg??'–';
    $('chip-stress').textContent=day.stressAvg!=null?'avg this day':'no sample';
    $('val-battery').textContent=day.batteryLast??'–';
    $('chip-battery').textContent=day.batteryLast!=null?'last reading':'no sample';
    $('val-dist').textContent=day.distanceMeters?((day.distanceMeters||0)/1000).toFixed(2):'–';
    $('chip-cal').textContent=(day.calories||0)+' kcal'+(day.estimated?' est.':'');
  }

  function cumulative(arr) {
    let s = 0;
    return arr.map((v) => { s += v || 0; return s; });
  }
  function drawBubbles(svg, seed, color, intensity) {
    if (!svg) return;
    svg.innerHTML = '';
    const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    line.setAttribute('x1', '4'); line.setAttribute('x2', '216');
    line.setAttribute('y1', '14'); line.setAttribute('y2', '14');
    line.setAttribute('class', 'bubble-line');
    svg.appendChild(line);
    const n = 14;
    for (let i = 0; i < n; i++) {
      const t = i / (n - 1);
      const wave = Math.abs(Math.sin((seed + i) * 0.85));
      const r = 1.6 + wave * (2.2 + intensity * 5.5);
      const c = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      c.setAttribute('cx', String(10 + t * 200));
      c.setAttribute('cy', '14');
      c.setAttribute('r', String(r));
      c.setAttribute('fill', color);
      c.setAttribute('opacity', String(0.35 + wave * 0.55));
      svg.appendChild(c);
    }
  }
  function setDonut(el, startFrac, spanFrac) {
    if (!el) return;
    const C = 2 * Math.PI * 44;
    const span = Math.max(0.02, spanFrac) * C;
    const gap = C - span;
    el.style.strokeDasharray = span + ' ' + gap;
    el.style.strokeDashoffset = String(-(startFrac * C));
  }
  function renderAuraUI() {
    const index = visibleDays();
    const today = viewedDay(index);
    syncHomeDateInput(today.date);
    const goal = settings.stepGoal || 8000;
    const pct = Math.min(100, Math.round((today.steps || 0) / goal * 100));
    const streak = computeStreak(index, goal);
    if ($('flame-count')) $('flame-count').textContent = String(streak || 0);
    if ($('on-track-label')) $('on-track-label').textContent = pct >= 55 ? 'On Track' : (today.steps ? 'Keep Going' : 'Start Moving');

    const hours = today.hourlySteps || Array(24).fill(0);
    const cum = cumulative(hours);
    const max = Math.max.apply(null, cum.concat([1]));
    const pts = cum.map((v, i) => {
      const x = (i / 23) * 320;
      const y = 72 - (v / max) * 58;
      return x.toFixed(1) + ' ' + y.toFixed(1);
    });
    const path = $('home-steps-path');
    const dot = $('home-steps-dot');
    if (path) path.setAttribute('d', 'M ' + pts.join(' L '));
    if (dot && pts.length) {
      const last = pts[pts.length - 1].split(' ');
      dot.setAttribute('cx', last[0]);
      dot.setAttribute('cy', last[1]);
    }

    const hr = today.heartRateAvg;
    if ($('home-hr')) $('home-hr').textContent = hr || '–';
    if ($('home-hr-status')) {
      $('home-hr-status').textContent = !hr ? 'no sample' : (hr < 100 ? 'Healthy' : 'Active');
    }
    const spark = $('hr-spark-bars');
    if (spark) {
      spark.innerHTML = '';
      let series = (today.hrSeries && today.hrSeries.some((v) => v != null))
        ? today.hrSeries
        : (today.hourlyHr || []);
      if (!series.some((v) => v != null) && (today.heartRateMin || today.heartRateAvg || today.heartRateMax)) {
        series = [today.heartRateMin || today.heartRateAvg, today.heartRateAvg, today.heartRateMax || today.heartRateAvg];
      }
      const known = [];
      series.forEach((v, i) => { if (v != null) known.push({ i, v }); });
      const barsN = 36;
      const display = [];
      if (known.length) {
        const lastIdx = Math.max(1, series.length - 1);
        for (let k = 0; k < barsN; k++) {
          const t = k / (barsN - 1) * lastIdx;
          let a = known[0], b = known[known.length - 1];
          for (let p = 0; p < known.length - 1; p++) {
            if (known[p].i <= t && known[p + 1].i >= t) { a = known[p]; b = known[p + 1]; break; }
          }
          const spanI = Math.max(0.001, b.i - a.i);
          display.push(a.v + (b.v - a.v) * ((t - a.i) / spanI));
        }
      }
      if (!display.length) {
        const note = document.createElementNS('http://www.w3.org/2000/svg', 'text');
        note.setAttribute('x', '160'); note.setAttribute('y', '32');
        note.setAttribute('text-anchor', 'middle');
        note.setAttribute('fill', '#9a8b99');
        note.setAttribute('font-size', '11');
        note.textContent = 'No heart-rate samples for this day';
        spark.appendChild(note);
      } else {
        const min = Math.min.apply(null, display);
        const maxHr = Math.max.apply(null, display);
        const span = Math.max(8, maxHr - min);
        const mid = min + span * 0.62;
        const slot = 320 / barsN;
        display.forEach((v, i) => {
          const h = 10 + ((v - min) / span) * 40;
          const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
          rect.setAttribute('x', String(i * slot + slot * 0.32));
          rect.setAttribute('y', String(56 - h));
          rect.setAttribute('width', '3.2');
          rect.setAttribute('height', String(h));
          rect.setAttribute('rx', '1.4');
          let cls = 'hr-bar';
          if (i > barsN * 0.78) cls = 'hr-bar soft';
          else if (v >= mid || v >= 110) cls = 'hr-bar warn';
          rect.setAttribute('class', cls);
          spark.appendChild(rect);
        });
      }
    }

    if ($('home-stress')) $('home-stress').textContent = today.stressAvg != null ? today.stressAvg : '–';
    if ($('home-stress-status')) {
      $('home-stress-status').textContent = today.stressAvg == null ? 'no sample' : (today.stressAvg > 70 ? 'high' : today.stressAvg > 40 ? 'moderate' : 'low');
    }
    const calEst = Math.round((today.steps || 0) * 0.04);
    const calShow = Math.max(today.calories || 0, today.estimated ? calEst : 0);
    if ($('home-cal')) $('home-cal').textContent = calShow ? calShow.toLocaleString() : '–';
    if ($('home-cal-status')) $('home-cal-status').textContent = today.estimated || !today.calories ? 'estimated' : 'from band';
    const strain = computeStrainScore(today);
    if ($('home-strain')) $('home-strain').textContent = strain == null ? '–' : strain.toFixed(1);
    if ($('home-strain-status')) {
      $('home-strain-status').textContent = strain == null ? 'need HR zones' : strain >= 14 ? 'high day' : strain >= 8 ? 'moderate' : 'light';
    }
    const sleepHrs = +(((today.sleepMinutes || 0) / 60).toFixed(1));
    if ($('home-sleep')) $('home-sleep').textContent = sleepHrs || '–';
    if ($('home-sleep-status')) $('home-sleep-status').textContent = sleepHrs ? 'last night' : 'no sample';

    const zones = today.hrZoneMinutes || { low: 0, moderate: 0, high: 0 };
    const zoneTotal = (zones.low || 0) + (zones.moderate || 0) + (zones.high || 0);
    const heartPct = hr ? Math.min(99, Math.max(18, Math.round((hr / 140) * 100))) : (pct ? Math.min(99, pct) : 0);
    if ($('heart-activity-pct')) $('heart-activity-pct').textContent = heartPct ? heartPct + '%' : '–%';
    if ($('heart-activity-state')) $('heart-activity-state').textContent = !hr && !today.steps ? 'idle' : (zoneTotal > 20 || pct >= 40 ? 'active' : 'resting');

    const restInt = hr ? Math.max(0.15, 1 - (hr - 55) / 80) : 0.25;
    const workInt = zoneTotal ? Math.min(1, (zones.high + zones.moderate) / 80) : Math.min(1, pct / 100);
    drawBubbles($('bubble-rest'), 2.1, '#AA98A9', restInt);
    drawBubbles($('bubble-work'), 5.4, '#7A6879', workInt);
    drawBubbles($('bubble-avg'), 8.2, '#d8c8d6', (restInt + workInt) / 2);

    const cardio = zoneTotal ? zones.high / zoneTotal : Math.min(0.55, pct / 180);
    const strength = zoneTotal ? zones.moderate / zoneTotal : Math.min(0.28, pct / 280);
    const flex = zoneTotal ? zones.low / zoneTotal : Math.max(0.12, 1 - cardio - strength);
    const bodyPct = Math.min(100, Math.round((cardio * 55 + strength * 30 + flex * 15 + pct * 0.25)));
    if ($('body-activity-pct')) $('body-activity-pct').textContent = (today.steps || zoneTotal) ? bodyPct + '%' : '–%';
    setDonut($('donut-flex'), 0, Math.max(0.08, flex));
    setDonut($('donut-str'), flex, Math.max(0.08, strength));
    setDonut($('donut-cardio'), flex + strength, Math.max(0.1, cardio));

    if ($('check-stress-score')) $('check-stress-score').textContent = today.stressAvg != null ? today.stressAvg : 'None';
    if ($('check-stress-state')) {
      $('check-stress-state').textContent = today.stressAvg == null ? 'Pending' : (today.stressAvg > 60 ? 'High' : 'Ok');
    }
    if ($('check-sleep-score')) $('check-sleep-score').textContent = sleepHrs ? sleepHrs + 'h' : 'None';
    if ($('check-sleep-state')) {
      $('check-sleep-state').textContent = !sleepHrs ? 'Pending' : (sleepHrs >= 7 ? 'Ok' : 'Low');
    }
  }
  function renderRecords(index) {
    const list=$('record-list'); list.innerHTML='';
    const recs=records(index);
    if (!recs.length) { list.innerHTML='<li><span>No records yet</span></li>'; return; }
    recs.forEach((r)=>{ const li=document.createElement('li'); li.innerHTML='<span>'+r.label+'</span><span class="muted">'+r.value+'</span>'; list.appendChild(li); });
  }
  function renderWorkouts() {
    const list=$('workout-list'); if (!list) return;
    list.innerHTML='';
    const groups = [];
    const groupMap = {};
    function addCard(card) {
      const key = card.source + '|' + card.title + '|' + card.dateKey;
      if (card.source === 'manual' && groupMap[key] != null) {
        groups[groupMap[key]].count += 1;
        if (Array.isArray(card.indexes)) {
          groups[groupMap[key]].indexes.push(...card.indexes);
        } else if (card.index != null) {
          groups[groupMap[key]].indexes.push(card.index);
        }
        if (card.detail) groups[groupMap[key]].detail = card.detail;
        return;
      }
      groupMap[key] = groups.length;
      groups.push(card);
    }
    state.workouts.forEach((w) => {
      const start=new Date(w.start>1e12?w.start:w.start*1000);
      let detail = (workoutTags[w.id]||[]).map((ex)=>ex.name+(ex.sets?' '+ex.sets+'×'+(ex.reps||'?') : '')).join(' · ');
      if (!detail && (w.calories || w.distanceMeters || w.heartRateAvg)) {
        const parts = [];
        if (w.calories) parts.push(w.calories + ' kcal');
        if (w.distanceMeters) parts.push(w.distanceMeters >= 1000 ? (w.distanceMeters/1000).toFixed(2) + ' km' : w.distanceMeters + ' m');
        if (w.heartRateAvg) parts.push('Avg HR ' + w.heartRateAvg + ' bpm');
        detail = parts.join(' · ');
      }
      addCard({
        sort: start.getTime(),
        title: w.name || 'Workout',
        dateKey: fmt(start),
        meta: start.toLocaleString([],{weekday:'short',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}) + (w.minutes?' · '+w.minutes+' min':''),
        detail: detail,
        needsTag: !musclesForWorkout(w) && !workoutTags[w.id],
        id: w.id,
        source: 'band',
        count: 1,
        indexes: []
      });
    });
    manualWorkouts.forEach((w, i) => {
      const detail = (w.exercises||[]).map((ex)=>ex.name+(ex.sets?' '+ex.sets+'×'+(ex.reps||'?') : '')).join(' · ');
      addCard({
        sort: parseKey(w.date).getTime(),
        title: w.label || 'Logged workout',
        dateKey: w.date,
        meta: shortLabel(parseKey(w.date)),
        detail,
        needsTag: false,
        id: 'manual-'+i,
        source: 'manual',
        count: 1,
        indexes: [i]
      });
    });
    groups.sort((a,b)=>b.sort-a.sort);
    if (!groups.length) {
      list.innerHTML='<div class="workout-empty">No workouts yet. Log one above or import a Gadgetbridge export.</div>';
      return;
    }
    groups.slice(0, 20).forEach((w) => {
      const card = document.createElement('article');
      card.className = 'workout-card';
      const times = w.count > 1 ? ' · '+w.count+' sessions' : '';
      card.innerHTML = '<div class="workout-card-top"><strong>'+w.title+'</strong><span class="workout-source">'+w.source+'</span></div>'+
        '<div class="workout-meta">'+w.meta+times+'</div>'+
        (w.detail ? '<div class="workout-detail">'+w.detail+'</div>' : '')+
        '<div class="workout-actions">'+
        (w.needsTag ? '<button class="tag-btn" type="button" data-tag-workout="'+w.id+'">Add exercises</button>' : '')+
        (w.source==='manual' ? '<button class="text-btn workout-del" type="button" data-del-manual="'+w.indexes.join(',')+'">Remove</button>' : '')+
        '</div>';
      list.appendChild(card);
    });
  }
  // ---------------- Muscle map + workout logging ----------------
  const STORAGE_MANUAL_WORKOUTS = 'bandstats.manualWorkouts';
  const STORAGE_WORKOUT_TAGS = 'bandstats.workoutTags';

  // Muscle mapping by actual exercise science: running/walking/cycling/
  // hiking are leg-and-core dominant with glute/hamstring involvement,
  // not an arm exercise, however it might feel.
  const AUTO_MUSCLE_MAP = {
    Running:    { primary: ['quads','calves','core'], secondary: ['glutes','hamstrings'] },
    Walking:    { primary: ['quads','calves'],        secondary: ['glutes'] },
    Cycling:    { primary: ['quads','calves'],        secondary: ['glutes','hamstrings'] },
    Elliptical: { primary: ['quads','calves','core'], secondary: ['glutes'] },
    Swimming:   { primary: ['chest','arms','shoulders','core'], secondary: ['back'] },
    Hiking:     { primary: ['quads','calves','core'], secondary: ['glutes','hamstrings'] },
    Rowing:     { primary: ['arms','core','quads'],   secondary: ['back'] },
    JumpRope:   { primary: ['calves','core'],         secondary: [] },
    Yoga:       { primary: ['core'],                  secondary: [] },
  };

  // Matched against the workout's own name text (from BASE_ACTIVITY_SUMMARY),
  // which covers every activity type your band can log — Outdoor/Treadmill
  // running, Indoor/Outdoor cycling, Elliptical, Rowing, Skipping rope, Yoga,
  // etc. — without depending on undocumented numeric activity codes that
  // vary by firmware. "Freestyle" intentionally matches nothing here, so it
  // always falls through to the exercise picker.
  const NAME_MUSCLE_RULES = [
    { test: /treadmill|running|\brun\b|jog/i, key: 'Running' },
    { test: /walk/i, key: 'Walking' },
    { test: /elliptical/i, key: 'Elliptical' },
    { test: /cycl|\bbik(e|ing)\b/i, key: 'Cycling' },
    { test: /swim/i, key: 'Swimming' },
    { test: /hik/i, key: 'Hiking' },
    { test: /rowing/i, key: 'Rowing' },
    { test: /skip|jump.?rope/i, key: 'JumpRope' },
    { test: /yoga/i, key: 'Yoga' },
  ];

  function musclesForKind(kindName) { return AUTO_MUSCLE_MAP[kindName] || null; }
  function musclesForWorkout(w) {
    const name = String((w && w.name) || '');
    for (const rule of NAME_MUSCLE_RULES) {
      if (rule.test.test(name)) return AUTO_MUSCLE_MAP[rule.key];
    }
    return musclesForKind(ACTIVITY_KIND[w && w.kind]); // fallback for sparse/blank names
  }

  const EXERCISE_LIBRARY = [
    { name:'Push-ups',       primary:['chest','arms','core'], secondary:['shoulders'] },
    { name:'Pull-ups',       primary:['arms','shoulders'],    secondary:['back'] },
    { name:'Bicep Curls',    primary:['arms'],                secondary:[] },
    { name:'Squats',         primary:['quads','core'],        secondary:['glutes'] },
    { name:'Lunges',         primary:['quads','calves'],      secondary:['glutes'] },
    { name:'Deadlifts',      primary:['core'],                secondary:['hamstrings','glutes','back'] },
    { name:'Bench Press',    primary:['chest','arms'],        secondary:['shoulders'] },
    { name:'Shoulder Press', primary:['shoulders','arms'],    secondary:[] },
    { name:'Plank',          primary:['core'],                secondary:[] },
    { name:'Jump Rope',      primary:['calves','core'],       secondary:[] },
    { name:'Rowing Machine', primary:['arms','core','quads'], secondary:['back'] },
    { name:'Yoga / Stretch', primary:['core'],                secondary:[] },
  ];

  const MUSCLE_REGIONS = ['shoulders','chest','arms','core','quads','calves'];

  let manualWorkouts = [];
  let workoutTags = {};
  function loadMuscleData() {
    try { manualWorkouts = JSON.parse(localStorage.getItem(STORAGE_MANUAL_WORKOUTS) || '[]'); } catch { manualWorkouts = []; }
    try { workoutTags = JSON.parse(localStorage.getItem(STORAGE_WORKOUT_TAGS) || '{}'); } catch { workoutTags = {}; }
  }
  function saveManualWorkouts() { localStorage.setItem(STORAGE_MANUAL_WORKOUTS, JSON.stringify(manualWorkouts)); }
  function saveWorkoutTags() { localStorage.setItem(STORAGE_WORKOUT_TAGS, JSON.stringify(workoutTags)); }
  loadMuscleData();

  function weekRangeForMuscleMap() {
    // Always "last 7 days ending today", independent of whatever period
    // is currently shown in the chart above.
    const end = new Date();
    const start = addDays(end, -6);
    return { startKey: fmt(start), endKey: fmt(end) };
  }

  function collectMuscleCounts() {
    const { startKey, endKey } = weekRangeForMuscleMap();
    const counts = {}; MUSCLE_REGIONS.forEach((m)=>{ counts[m]=0; });
    const secondaryHit = new Set();

    function tally(map) {
      if (!map) return;
      (map.primary||[]).forEach((m)=>{ if (counts[m]!=null) counts[m]++; });
      (map.secondary||[]).forEach((m)=>secondaryHit.add(m));
    }

    state.workouts.forEach((w) => {
      const d = new Date(w.start>1e12?w.start:w.start*1000);
      const key = fmt(d);
      if (key < startKey || key > endKey) return;
      const auto = musclesForWorkout(w);
      if (auto) tally(auto);
      const tagged = workoutTags[w.id];
      if (tagged) tagged.forEach((ex)=>tally(ex));
    });

    manualWorkouts.forEach((w) => {
      if (w.date < startKey || w.date > endKey) return;
      if (w.auto) tally(w.auto);
      (w.exercises||[]).forEach((ex)=>tally(ex));
    });

    return { counts, secondaryHit };
  }

  function renderMuscleMap() {
    const svgRoot = $('muscle-svg');
    if (!svgRoot) return;
    const { counts, secondaryHit } = collectMuscleCounts();
    const max = Math.max(1, ...Object.values(counts));
    MUSCLE_REGIONS.forEach((m) => {
      svgRoot.querySelectorAll('[data-muscle="'+m+'"]').forEach((el) => {
        const pct = counts[m] / max;
        el.style.fill = pct === 0 ? '' : ('rgba(170,152,169,'+(0.28+pct*0.62).toFixed(2)+')');
      });
    });
    const secList = $('muscle-secondary');
    if (secList) {
      secList.innerHTML = '';
      if (!secondaryHit.size) {
        secList.innerHTML = '<span class="muted">Nothing logged this week yet</span>';
      } else {
        [...secondaryHit].forEach((m) => {
          const chip = document.createElement('span');
          chip.className = 'muscle-chip';
          chip.textContent = m;
          secList.appendChild(chip);
        });
      }
    }
    renderMuscleCoachNote(counts);
  }

  function renderMuscleCoachNote(counts) {
    const el = $('muscle-coach-note');
    if (!el) return;
    const upper = (counts.chest||0)+(counts.arms||0)+(counts.shoulders||0);
    const lower = (counts.quads||0)+(counts.calves||0);
    const core = counts.core||0;
    if (upper+lower+core === 0) {
      el.textContent = 'No workouts logged this week yet — log one below to get a suggestion for tomorrow.';
      return;
    }
    let suggestion;
    if (lower > upper*2) suggestion = "Legs have carried this week — tomorrow could be an upper-body or core day.";
    else if (upper > lower*2) suggestion = "Upper body has been the focus — tomorrow could lean into legs or a run.";
    else if (core === 0) suggestion = "Core hasn't been hit directly this week — worth adding planks or a core set tomorrow.";
    else suggestion = "Fairly balanced across muscle groups this week — for tomorrow, let your Strain and sleep guide intensity.";
    el.textContent = suggestion;
  }

  function openFreestylePicker(onSave) {
    const modal = $('exercise-modal');
    const list = $('exercise-list');
    if (!modal || !list) return;
    list.innerHTML = '';
    EXERCISE_LIBRARY.forEach((ex, i) => {
      const row = document.createElement('div');
      row.className = 'exercise-row';
      row.innerHTML =
        '<label class="exercise-check"><input type="checkbox" data-idx="'+i+'"><span>'+ex.name+'</span></label>'+
        '<div class="exercise-nums">'+
        '<input type="number" min="0" placeholder="sets" class="exercise-sets" data-idx="'+i+'" disabled>'+
        '<input type="number" min="0" placeholder="reps" class="exercise-reps" data-idx="'+i+'" disabled>'+
        '</div>';
      list.appendChild(row);
    });
    list.querySelectorAll('input[type=checkbox]').forEach((cb) => {
      cb.addEventListener('change', () => {
        const idx = cb.dataset.idx;
        const s = list.querySelector('.exercise-sets[data-idx="'+idx+'"]');
        const r = list.querySelector('.exercise-reps[data-idx="'+idx+'"]');
        if (s) s.disabled = !cb.checked;
        if (r) r.disabled = !cb.checked;
      });
    });
    modal.classList.remove('hidden');
    document.body.classList.add('modal-open');
    const saveBtn = $('exercise-save');
    const cancelBtn = $('exercise-cancel');
    if (saveBtn) saveBtn.onclick = () => {
      const chosen = [];
      list.querySelectorAll('input[type=checkbox]:checked').forEach((cb) => {
        const idx = Number(cb.dataset.idx);
        const ex = EXERCISE_LIBRARY[idx];
        const setsEl = list.querySelector('.exercise-sets[data-idx="'+idx+'"]');
        const repsEl = list.querySelector('.exercise-reps[data-idx="'+idx+'"]');
        chosen.push({ name: ex.name, primary: ex.primary, secondary: ex.secondary, sets: setsEl&&Number(setsEl.value)||null, reps: repsEl&&Number(repsEl.value)||null });
      });
      modal.classList.add('hidden');
      document.body.classList.remove('modal-open');
      onSave(chosen);
    };
    if (cancelBtn) cancelBtn.onclick = () => { modal.classList.add('hidden'); document.body.classList.remove('modal-open'); };
  }

  // Real, verified demonstration videos, one per movement pattern (checked
  // via search before including — actual channels, actual matching
  // exercise, not guessed IDs). Mostly Cali Move, a well-established
  // calisthenics channel matching this plan's style. If one of these ever
  // goes down, the "More videos on YouTube" search link below is the
  // fallback — that part can never break the way a fixed ID can.
  const EXERCISE_VIDEO_IDS = {
    pushup: 'IODxDxX7oi4',   // Cali Move — The Perfect Push Up
    pullup: 'eGo4IYlbE5g',   // Cali Move — The Perfect Pull Up
    squat:  'X0qC1k0Zi6k',   // Cali Move — The Perfect Squat
    lunge:  'BenhAbJiTsw',   // Walking Lunge — Proper Form Tutorial
    plank:  'kL_NJAkCQBg',   // Cali Move — Mastering the Plank
    dip:    '2z8JmcrW-As',   // Cali Move — The Perfect Dip
    raise:  'Mz2pZdK_U5g',   // Glute Bridge + Calf Raise — Tutorial
    jump:   'Uzby_714akU',   // How To Do A Burpee With Perfect Form
    curl:   'crj5Zqwbz1A',   // Towel Bicep Curl
    core:   'wkD8rjkodUI',   // How to Do a Russian Twist | Ab Workout
    hold:   'z6PJMT2y8GQ',   // Exercise Tutorial — Superman
  };

  // \"Watch how to do it\" — tap an exercise's icon to open a modal with a
  // real embedded demonstration video for that movement pattern, plus a
  // live YouTube search link as a fallback in case the embed is ever
  // pulled or you want to see other takes on the same exercise.
  const demoModal = $('exercise-demo-modal');
  if (demoModal) {
    document.addEventListener('click', (e) => {
      const btn = e.target.closest('.ex-icon-btn');
      if (!btn) return;
      const name = btn.getAttribute('data-ex-name') || '';
      const sub = btn.getAttribute('data-ex-sub') || '';
      $('demo-name').textContent = name;
      $('demo-sub').textContent = sub;
      $('demo-icon-holder').innerHTML = exerciseIconHtml(name, 90);

      const move = exerciseMove(name);
      const videoId = EXERCISE_VIDEO_IDS[move];
      const videoHolder = $('demo-video-holder');
      videoHolder.innerHTML = videoId
        ? '<iframe src="https://www.youtube-nocookie.com/embed/' + videoId + '?rel=0" ' +
            'title="Exercise demonstration" frameborder="0" ' +
            'allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture" ' +
            'allowfullscreen loading="lazy"></iframe>'
        : '';

      $('demo-youtube-link').href = 'https://www.youtube.com/results?search_query=' +
        encodeURIComponent(name.replace(/\([^)]*\)/g, '').trim() + ' correct form tutorial');
      demoModal.classList.remove('hidden');
      document.body.classList.add('modal-open');
    });
    const closeBtn = $('demo-close');
    if (closeBtn) closeBtn.onclick = () => {
      demoModal.classList.add('hidden');
      document.body.classList.remove('modal-open');
      $('demo-video-holder').innerHTML = ''; // stop playback, don't keep it loaded in the background
    };
  }

  const logWorkoutBtn = $('log-workout-btn');
  if (logWorkoutBtn) {
    logWorkoutBtn.addEventListener('click', () => {
      const typeEl = $('log-workout-type');
      const choice = typeEl ? typeEl.value : 'running';
      const when = state.viewKey || fmt(new Date());
      const preset = {
        running: { label:'Running', auto: AUTO_MUSCLE_MAP.Running },
        walking: { label:'Walking', auto: AUTO_MUSCLE_MAP.Walking },
        cycling: { label:'Cycling', auto: AUTO_MUSCLE_MAP.Cycling },
        yoga: { label:'Yoga / stretch', auto: AUTO_MUSCLE_MAP.Yoga },
        strength: { label:'Strength', auto: { primary:['chest','arms','shoulders'], secondary:['core'] } }
      };
      if (preset[choice]) {
        const exists = manualWorkouts.some((w) => w.date === when && w.label === preset[choice].label);
        if (!exists) {
          manualWorkouts.push({ date: when, label: preset[choice].label, auto: preset[choice].auto, exercises:[] });
          saveManualWorkouts();
        }
        renderMuscleMap();
        renderWorkouts();
      } else {
        openFreestylePicker((chosen) => {
          if (!chosen.length) return;
          manualWorkouts.push({ date: when, label:'Freestyle', exercises: chosen });
          saveManualWorkouts();
          renderMuscleMap();
          renderWorkouts();
        });
      }
    });
  }

  document.addEventListener('click', (e) => {
    const del = e.target.closest('[data-del-manual]');
    if (del) {
      const indexes = del.getAttribute('data-del-manual').split(',').map(Number).sort((a,b)=>b-a);
      indexes.forEach((i) => { if (i >= 0) manualWorkouts.splice(i, 1); });
      saveManualWorkouts();
      renderWorkouts();
      renderMuscleMap();
      return;
    }
    const btn = e.target.closest('[data-tag-workout]');
    if (!btn) return;
    const id = btn.getAttribute('data-tag-workout');
    openFreestylePicker((chosen) => {
      if (!chosen.length) return;
      workoutTags[id] = chosen;
      saveWorkoutTags();
      renderWorkouts();
      renderMuscleMap();
    });
  });

  // ---------------- Strain (today's cumulative exertion, 0-21ish) ----------------
  // Reuses the same personal HR-zone engine as the score card. Approximate,
  // loosely modeled after WHOOP's public description of Strain, not a
  // reproduction of their formula.
  function computeStrainScore(day) {
    if (!day) return null;
    const z = day.hrZoneMinutes || { low:0, moderate:0, high:0 };
    const low = z.low || 0, mod = z.moderate || 0, high = z.high || 0;
    const zoneMin = low + mod + high;
    let zoneLoad = low * 0.035 + mod * 0.09 + high * 0.22;
    if (zoneMin > 180) zoneLoad = Math.min(zoneLoad, 6 + Math.log10(zoneMin) * 1.2);
    let load = zoneLoad;
    load += Math.min(4.5, (day.steps || 0) / 2800);
    load += Math.min(3, (day.calories || 0) / 220);
    if (day.heartRateAvg && day.heartRateMin) {
      load += Math.min(2.2, Math.max(0, day.heartRateAvg - day.heartRateMin) / 18);
    }
    if (load < 0.35) return (zoneMin || day.steps || day.heartRateAvg) ? 0.8 : null;
    const score = 21 * (1 - Math.exp(-load / 14));
    return Math.round(Math.min(21, score) * 10) / 10;
  }
  function renderStrain() {
    const valueEl = $('strain-value'), labelEl = $('strain-label');
    if (!valueEl) return;
    const day = selectedDay();
    const score = computeStrainScore(day);
    valueEl.textContent = score==null ? '–' : score.toFixed(1);
    if (labelEl) {
      labelEl.textContent = score==null ? 'Not enough heart-rate data for '+day.date
        : score>=14 ? 'High exertion day' : score>=8 ? 'Moderate exertion' : 'Light day';
    }
  }

  function renderNote() { const day=selectedDay(); $('note-date').textContent=day.date; noteInput.value=day.notes||''; }

  // ---------------- Workout / Study / Nutrition plan ----------------
  const WEEKDAY_NAMES = ['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];

  const WARMUP = ['Jumping jacks × 30', 'Arm circles × 15 each direction', 'Bodyweight squats × 15', 'Hip circles × 10 each side'];

  // ---------------- Small illustrations for the Plan tab ----------------
  // Exercises: no external video links — video IDs go stale/get taken down
  // and there's no way to verify one actually shows the right movement
  // ahead of time. Instead, a tiny looping animated SVG stick-figure per
  // movement pattern (several similar exercises share one icon, matched
  // by keyword — order matters, most specific pattern first). Verified
  // against every real exercise name in WORKOUT_PLAN before shipping.
  const EXERCISE_MOVE_RULES = [
    [/plank-to-push/i, 'pushup'],
    [/push-?up/i, 'pushup'],
    [/pull-?up|chin-?up|inverted row/i, 'pullup'],
    [/dip/i, 'dip'],
    [/squat jump/i, 'jump'],
    [/burpee/i, 'jump'],
    [/jumping jack/i, 'jump'],
    [/wall sit|superman/i, 'hold'],
    [/squat/i, 'squat'],
    [/lunge/i, 'lunge'],
    [/bridge|calf raise/i, 'raise'],
    [/curl|arm circle/i, 'curl'],
    [/leg raise|crunch|twist|hip circle/i, 'core'],
    [/plank/i, 'plank'],
  ];
  function exerciseMove(name) {
    for (const [re, move] of EXERCISE_MOVE_RULES) if (re.test(name)) return move;
    return 'pushup';
  }
  function exerciseIconHtml(name, size) {
    const move = exerciseMove(name);
    const px = size || 30;
    return '<svg class="ex-icon" style="width:' + px + 'px;height:' + px + 'px" viewBox="0 0 40 40" aria-hidden="true">' +
      '<g class="move-' + move + '">' +
        '<circle class="sf-head" cx="20" cy="9" r="4"/>' +
        '<line class="sf-body" x1="20" y1="13" x2="20" y2="25"/>' +
        '<line class="sf-arm-l" x1="20" y1="16" x2="12" y2="21"/>' +
        '<line class="sf-arm-r" x1="20" y1="16" x2="28" y2="21"/>' +
        '<line class="sf-leg-l" x1="20" y1="25" x2="13" y2="35"/>' +
        '<line class="sf-leg-r" x1="20" y1="25" x2="27" y2="35"/>' +
      '</g>' +
    '</svg>';
  }

  // Food: hand-drawn line-art SVG illustrations, not emoji — emoji render
  // inconsistently across platforms and clash with a custom-designed UI.
  // Built from simple, well-supported SVG primitives (circle/ellipse/rect/
  // line/basic arcs) rather than complex bezier paths, since these can't
  // be visually previewed before shipping — simple shapes are far less
  // likely to render wrong.
  const FOOD_SVG = {
    banana: '<path d="M6 18C4 12 6 6 12 4" fill="none"/><path d="M12 4c2 0 3 1 3 2" fill="none"/>',
    dates: '<ellipse cx="12" cy="13" rx="4.5" ry="6"/><line x1="12" y1="7" x2="12" y2="4"/>',
    nuts: '<ellipse cx="9" cy="12" rx="3.6" ry="5.6" transform="rotate(-16 9 12)"/><ellipse cx="15" cy="12" rx="3.6" ry="5.6" transform="rotate(16 15 12)"/>',
    milk: '<path d="M8 4L16 4L15 20L9 20Z" fill="none"/><line x1="8.6" y1="9" x2="15.4" y2="9"/>',
    curd: '<path d="M4 10a8 6 0 0 0 16 0" fill="none"/><line x1="4" y1="10" x2="20" y2="10"/>',
    paneer: '<rect x="6" y="10" width="6" height="6" rx="1"/><rect x="13" y="7" width="6" height="6" rx="1"/>',
    flatbread: '<circle cx="12" cy="12" r="7.5" fill="none"/><line x1="7" y1="12" x2="17" y2="12" stroke-opacity="0.5"/><line x1="12" y1="7" x2="12" y2="17" stroke-opacity="0.5"/>',
    dal: '<path d="M4 10a8 6 0 0 0 16 0" fill="none"/><line x1="4" y1="10" x2="20" y2="10"/><circle cx="9" cy="8" r="0.9" fill="currentColor" stroke="none"/><circle cx="12" cy="7" r="0.9" fill="currentColor" stroke="none"/><circle cx="15" cy="8" r="0.9" fill="currentColor" stroke="none"/>',
    water: '<path d="M8 4L16 4L15 20L9 20Z" fill="none"/><path d="M9 12q1.5 1.5 3 0t3 0" fill="none"/>',
    ghee: '<rect x="8" y="8" width="8" height="9" rx="2"/><rect x="9" y="5" width="6" height="3" rx="1"/>',
    leafy: '<ellipse cx="12" cy="12" rx="7" ry="4" transform="rotate(-30 12 12)"/><line x1="7" y1="16" x2="17" y2="8"/>',
    rice: '<path d="M4 10a8 6 0 0 0 16 0" fill="none"/><line x1="4" y1="10" x2="20" y2="10"/><ellipse cx="9" cy="8" rx="1.1" ry="0.6"/><ellipse cx="12" cy="7" rx="1.1" ry="0.6"/><ellipse cx="15" cy="8" rx="1.1" ry="0.6"/>',
    sprout: '<line x1="12" y1="20" x2="12" y2="10"/><ellipse cx="9" cy="10" rx="2.5" ry="1.5" transform="rotate(-30 9 10)"/><ellipse cx="15" cy="10" rx="2.5" ry="1.5" transform="rotate(30 15 10)"/>',
    dried: '<ellipse cx="12" cy="13" rx="5" ry="5.5"/><line x1="12" y1="7.5" x2="12" y2="5"/>',
    plate: '<circle cx="12" cy="12" r="8" fill="none"/><line x1="9" y1="7" x2="9" y2="17"/><line x1="15" y1="7" x2="15" y2="17"/>',
  };
  const FOOD_SVG_RULES = [
    [/banana/i, 'banana'], [/date/i, 'dates'],
    [/milk|buttermilk|chaas/i, 'milk'], [/almond|walnut|peanut|nut/i, 'nuts'],
    [/curd|yog/i, 'curd'], [/paneer|tofu|cheese/i, 'paneer'],
    [/chilla|paratha|roti|poha|upma/i, 'flatbread'], [/dal|rajma|chole|chana|sabzi|curry/i, 'dal'],
    [/water/i, 'water'], [/ghee|butter/i, 'ghee'],
    [/salad|veg|leafy|palak|methi/i, 'leafy'], [/rice/i, 'rice'],
    [/soya|sprout/i, 'sprout'], [/chikki|dried fruit|raisin/i, 'dried'],
  ];
  function foodIconHtml(name) {
    let key = 'plate';
    for (const [re, k] of FOOD_SVG_RULES) { if (re.test(name)) { key = k; break; } }
    return '<svg class="food-icon" viewBox="0 0 24 24" aria-hidden="true">' + FOOD_SVG[key] + '</svg>';
  }

  // Study blocks: one emoji per activity type, same keyword-match approach.
  const STUDY_ICON_RULES = [
    [/wake/i, '\u23F0'], [/exercise/i, '\u{1F3C3}'], [/shower|change/i, '\u{1F6BF}'],
    [/focus block|mock test|mock analysis/i, '\u{1F4D6}'], [/snack/i, '\u{1F34E}'], [/break/i, '\u2615'],
    [/breakfast/i, '\u{1F373}'], [/school/i, '\u{1F3EB}'],
    [/tuition/i, '\u{1F4DA}'], [/dinner/i, '\u{1F37D}\uFE0F'], [/revision/i, '\u{1F4DD}'],
    [/sleep/i, '\u{1F634}'],
  ];
  function studyIconHtml(name) {
    for (const [re, emoji] of STUDY_ICON_RULES) {
      if (re.test(name)) return '<span class="study-icon">' + emoji + '</span>';
    }
    return '<span class="study-icon">\u{1F553}</span>';
  }

  const WORKOUT_PLAN = {
    Monday:    { title: 'Push — Chest, Shoulders, Triceps', tag: 'PUSH', color: '#b3542f', exercises: [
      ['Push-ups','4 × 12–15'], ['Diamond push-ups','3 × 8–10'], ['Pike push-ups','3 × 8–10'],
      ['Decline push-ups (feet on chair)','3 × 10–12'], ['Chair / bench dips','3 × 12–15'], ['Plank-to-push-up','3 × 10']
    ]},
    Tuesday:   { title: 'Pull — Back, Biceps', tag: 'PULL', color: '#3d5a4c', exercises: [
      ['Pull-ups (or door-frame rows)','4 × 6–10'], ['Chin-ups (underhand grip)','3 × 6–8'],
      ['Inverted rows (table or low bar)','3 × 10–12'], ['Superman holds','3 × 15 (2s hold)'], ['Towel / backpack curls','3 × 12–15']
    ]},
    Wednesday: { title: 'Legs', tag: 'LEGS', color: '#8a6b1f', exercises: [
      ['Bodyweight squats','4 × 20'], ['Bulgarian split squats (rear foot on chair)','3 × 10 / leg'],
      ['Walking lunges','3 × 12 / leg'], ['Glute bridges','3 × 15'], ['Calf raises','4 × 20'], ['Wall sit','3 × 30–45s']
    ]},
    Thursday:  { title: 'Active Rest', tag: 'REST', color: '#8c8577', note: "Light stretching, a walk, or mobility work — 10–15 min. No lifting." },
    Friday:    { title: 'Core & Shoulders', tag: 'CORE', color: '#5a4a7a', exercises: [
      ['Pike push-ups','3 × 10'], ['Plank','3 × 45–60s'], ['Hanging / lying leg raises','3 × 12'],
      ['Bicycle crunches','3 × 20 (10/side)'], ['Russian twists','3 × 20'], ['Side plank','3 × 30s / side']
    ]},
    Saturday:  { title: 'Full Body & Skill Work', tag: 'FULL', color: '#2f5f6b', exercises: [
      ['Burpees','3 × 10'], ['Pull-ups','3 × max'], ['Push-ups','3 × max'], ['Squat jumps','3 × 12'], ['Plank','3 × 60s']
    ]},
    Sunday:    { title: 'Rest', tag: 'REST', color: '#8c8577' }
  };

  const STUDY_DAILY_BLOCKS = [
    ['3:30–3:40 AM','Wake up, freshen up'], ['3:40–4:10 AM','Exercise (light cardio/stretching)'],
    ['4:10–4:20 AM','Shower/change'], ['4:20–5:50 AM','Deep Focus Block 1 — hardest topic, new concepts'],
    ['5:50–6:00 AM','Short break'], ['6:00–6:45 AM','Focus Block 2 — practice problems/revision'],
    ['6:45–7:00 AM','Get ready, light breakfast'], ['7:00 AM–4:00 PM','School'],
    ['4:00–4:15 PM','Snack + break'], ['4:15–8:00 PM','Tuition'], ['8:00–8:30 PM','Dinner'],
    ['8:30–9:00 PM','Quick revision of the day'], ['9:00 PM','Sleep']
  ];
  const STUDY_SUNDAY_BLOCKS = [
    ['~4:30 AM','Wake up (1 hr later than usual) — recovery sleep'],
    ['Morning','Full-length Mock Test (3 hrs, JEE Main/Advanced pattern)'],
    ['Afternoon','Mock analysis — mistakes, weak areas, redo wrong questions'],
    ['Evening','Light revision or off — mental recovery matters too'],
    ['9:30 PM','Sleep (slightly relaxed)']
  ];
  const SUBJECT_ROTATION = {
    Monday: ['Physics — new concept','Math — practice'], Tuesday: ['Math — new concept','Chemistry — practice'],
    Wednesday: ['Chemistry — new concept','Physics — practice'], Thursday: ['Physics — new concept','Math — practice'],
    Friday: ['Math — new concept','Chemistry — practice'], Saturday: ['Chemistry — new concept','Physics — practice'],
    Sunday: ['Full Mock Test (3 hrs)','Mock Analysis (1 hr)']
  };
  const STUDY_RULES = [
    'Protect the 4:20–5:50 AM slot — this is your sharpest window, use it only for the toughest topic of the week.',
    'Same-day revision (8:30–9:00 PM) is non-negotiable — this is what makes the early morning study actually stick.',
    '9:00 PM sleep, no exceptions on weekdays — this schedule only works if sleep is consistent.',
    'If you ever feel consistently groggy through Focus Block 1, shift wake-up to 4:30 AM instead — quality of focused hours matters more than quantity.',
    'Keep a 15–20 min buffer daily somewhere (mornings tend to run long) — don\'t over-pack.'
  ];

  const NUTRITION_MEALS = [
    { time:'3:35 AM', title:'Before Exercise', tag:'PRE-WORKOUT', color:'#b3542f', items:[
      ['1 banana or 4–5 soaked dates','1 serving'], ['Water','1–2 glasses']
    ]},
    { time:'4:15 AM', title:'Post-Workout, Before Focus Block 1', tag:'PROTEIN', color:'#3d5a4c', items:[
      ['Soaked almonds (8) + walnuts (3)','1 handful'], ['Full-fat milk + 1 tsp peanut butter/almond powder','1 glass (250ml)']
    ]},
    { time:'6:45–7:00 AM', title:'Breakfast', tag:'PROTEIN + CARB', color:'#3d5a4c', items:[
      ['Moong dal chilla (3) with ghee, mint chutney','3 medium'],
      ['OR Paneer paratha (2, ghee-roasted) + curd','2 paratha + 100g curd'],
      ['OR Poha/upma with peanuts + sprouts + ghee','1 large bowl']
    ]},
    { time:'~12–1 PM (School)', title:'Lunch Tiffin', tag:'MAIN MEAL', color:'#8a6b1f', items:[
      ['3 roti + rajma/chole/dal (rotate daily)','1 large katori'], ['Paneer or soya chunk sabzi (daily)','1 medium katori'],
      ['Curd','1 katori'], ['Seasonal veg sabzi + salad','1 katori']
    ]},
    { time:'4:00–4:15 PM', title:'Snack (before tuition)', tag:'SNACK', color:'#8c8577', items:[
      ['Roasted chana / peanut chikki','1 bowl / 2 pieces'], ['Banana or dried fruit','1 piece'], ['Buttermilk or a fruit','1 glass / 1 piece']
    ]},
    { time:'8:00–8:30 PM', title:'Dinner', tag:'MAIN MEAL', color:'#3d5a4c', items:[
      ['2–3 roti + small rice if training day','2–3 roti'], ['Paneer/tofu/soya curry or second dal','1 medium katori'],
      ['Green leafy sabzi (palak/methi) 3–4x/week','1 katori'], ['Curd','1 katori']
    ]}
  ];
  const PROTEIN_TALLY_TEXT = "Post-workout milk (8g) + breakfast chilla (18g) + lunch dal+paneer (35g) + snack chana (7g) + dinner dal+paneer (30g) + curd×2 (14g) ≈ ~100g/day. The bigger job is calories, not just protein — don't skip the \"extra\" ghee/fruit/nuts items.";
  const VITAMIN_NOTES = [
    'B12 — plants don\'t make it; on a pure-veg diet it\'s genuinely hard to hit enough. Worth a blood test and asking a doctor about a supplement.',
    'Vitamin D — an indoors-heavy day means food alone won\'t fix this; 10–15 min morning sun on weekends, or ask a doctor about a supplement.',
    'Iron — dal/rajma/chana/palak have it, but plant iron absorbs poorly. Pair with something vitamin-C rich to roughly double absorption.',
    'Omega-3 — walnuts and flaxseed/chia are the main vegetarian sources.',
    'Calcium — well covered here via milk + curd twice daily + paneer.'
  ];

  function planCheckKey(section, date, itemId) { return 'bandstats.plan.' + section + '.' + date + '.' + itemId; }
  function isPlanChecked(section, date, itemId) { return localStorage.getItem(planCheckKey(section, date, itemId)) === '1'; }
  function setPlanChecked(section, date, itemId, checked) {
    const key = planCheckKey(section, date, itemId);
    if (checked) localStorage.setItem(key, '1'); else localStorage.removeItem(key);
  }

  function buildCheckList(container, section, date, items, iconFn) {
    container.innerHTML = '';
    const watchable = iconFn === exerciseIconHtml;
    items.forEach(([main, sub], i) => {
      const id = 'i' + i;
      const li = document.createElement('li');
      const checked = isPlanChecked(section, date, id);
      if (checked) li.classList.add('checked');
      const rawIcon = iconFn ? iconFn(main) : '';
      const icon = watchable
        ? '<button type="button" class="ex-icon-btn" data-ex-name="' + main.replace(/"/g, '&quot;') + '" data-ex-sub="' + (sub || '').replace(/"/g, '&quot;') + '" aria-label="Watch how to do ' + main.replace(/"/g, '&quot;') + '">' + rawIcon + '</button>'
        : rawIcon;
      li.innerHTML = '<input type="checkbox" ' + (checked ? 'checked' : '') + ' />' +
        icon +
        '<span class="item-main">' + main + (sub ? '<span class="item-reps">' + sub + '</span>' : '') + '</span>';
      li.querySelector('input').addEventListener('change', (e) => {
        setPlanChecked(section, date, id, e.target.checked);
        li.classList.toggle('checked', e.target.checked);
      });
      container.appendChild(li);
    });
  }

  function renderPlan() {
    const todayKey = fmt(new Date());
    const weekday = WEEKDAY_NAMES[new Date().getDay()];

    // Workout
    const w = WORKOUT_PLAN[weekday];
    const wTitleEl = $('plan-workout-title'), wDotEl = $('plan-workout-tag-dot'), wNoteEl = $('plan-workout-note'), wListEl = $('plan-workout-list');
    if (w && wTitleEl) {
      wTitleEl.textContent = weekday + ' — ' + w.title;
      if (wDotEl) wDotEl.style.color = w.color;
      if (w.exercises) { wListEl.classList.remove('hidden'); buildCheckList(wListEl, 'workout', todayKey, w.exercises, exerciseIconHtml); }
      else if (wListEl) wListEl.innerHTML = '';
      if (wNoteEl) wNoteEl.textContent = w.note || '';
    }
    const warmupEl = $('plan-warmup-list');
    if (warmupEl) buildCheckList(warmupEl, 'warmup', todayKey, WARMUP.map((x) => [x, '']), exerciseIconHtml);

    // Study
    const blocks = weekday === 'Sunday' ? STUDY_SUNDAY_BLOCKS : STUDY_DAILY_BLOCKS;
    const studyTitleEl = $('plan-study-title'), studyListEl = $('plan-study-list'), subjEl = $('plan-study-subjects'), rulesEl = $('plan-study-rules');
    if (studyTitleEl) studyTitleEl.textContent = weekday + "'s Schedule";
    if (studyListEl) buildCheckList(studyListEl, 'study', todayKey, blocks, studyIconHtml);
    if (subjEl) {
      const rot = SUBJECT_ROTATION[weekday];
      subjEl.textContent = rot ? ('Block 1: ' + rot[0] + '  ·  Block 2: ' + rot[1]) : '';
    }
    if (rulesEl) rulesEl.innerHTML = STUDY_RULES.map((r) => '<li>' + r + '</li>').join('');

    // Nutrition
    const mealsEl = $('plan-nutrition-meals');
    if (mealsEl) {
      mealsEl.innerHTML = '';
      NUTRITION_MEALS.forEach((meal, mi) => {
        const card = document.createElement('div');
        card.className = 'glass-card list-card';
        card.innerHTML =
          '<div class="insight-head"><span class="meal-time">' + meal.time + '</span></div>' +
          '<span class="plan-tag" style="background:' + meal.color + '">' + meal.tag + '</span>' +
          '<div class="insight-head" style="margin-top:2px;"><span>' + meal.title + '</span></div>' +
          '<ul class="check-list" id="plan-meal-' + mi + '"></ul>';
        mealsEl.appendChild(card);
        buildCheckList(card.querySelector('.check-list'), 'nutrition', todayKey, meal.items, foodIconHtml);
      });
    }
    const proteinEl = $('plan-protein-tally'); if (proteinEl) proteinEl.textContent = PROTEIN_TALLY_TEXT;
    const vitEl = $('plan-vitamins'); if (vitEl) vitEl.innerHTML = VITAMIN_NOTES.map((v) => '<li>' + v + '</li>').join('');
  }

  const planSubTabs = $('plan-sub-tabs');
  if (planSubTabs) {
    planSubTabs.addEventListener('click', (e) => {
      const btn = e.target.closest('.sub-tab-btn');
      if (!btn) return;
      planSubTabs.querySelectorAll('.sub-tab-btn').forEach((b) => b.classList.toggle('active', b === btn));
      document.querySelectorAll('.plan-panel').forEach((p) => p.classList.toggle('active', p.dataset.planPanel === btn.dataset.plan));
    });
  }


  // ---------------- Journal + pattern correlations (Phase 2) ----------------
  const JOURNAL_TAGS = ['Alcohol','Late caffeine','Late meal','High stress','Screen before bed','Supplements'];

  function renderJournal() {
    const container = $('journal-tags');
    if (!container) return;
    const day = selectedDay();
    const dayData = state.localDayIndex[day.date];
    const current = (dayData && dayData.journalTags) || [];
    container.innerHTML = '';
    JOURNAL_TAGS.forEach((tag) => {
      const label = document.createElement('label');
      label.className = 'journal-tag';
      const checked = current.includes(tag) ? 'checked' : '';
      label.innerHTML = '<input type="checkbox" value="'+tag+'" '+checked+'> '+tag;
      container.appendChild(label);
    });
    container.querySelectorAll('input[type=checkbox]').forEach((cb) => {
      cb.addEventListener('change', () => {
        const d = selectedDay();
        if (!state.localDayIndex[d.date]) state.localDayIndex[d.date] = emptyDay(d.date);
        const tags = new Set(state.localDayIndex[d.date].journalTags || []);
        if (cb.checked) tags.add(cb.value); else tags.delete(cb.value);
        state.localDayIndex[d.date].journalTags = [...tags];
        persistAll();
        renderCorrelations();
      });
    });
  }

  // Purely descriptive pattern-matching against this person's own logged
  // days — not a diagnosis, not a claim about causation, just "here's what
  // your own data shows so far." Requires at least 3 days on each side of
  // a comparison before showing anything, so a single logged night can't
  // masquerade as a pattern.
  function renderCorrelations() {
    const el = $('correlation-list');
    if (!el) return;
    const allDays = Object.values(state.localDayIndex);
    const lines = [];
    JOURNAL_TAGS.forEach((tag) => {
      const withTag = allDays.filter((d)=>(d.journalTags||[]).includes(tag) && d.sleepMinutes);
      const withoutTag = allDays.filter((d)=>!(d.journalTags||[]).includes(tag) && d.sleepMinutes);
      if (withTag.length < 3 || withoutTag.length < 3) return;
      const avgSleep = (arr)=>arr.reduce((a,d)=>a+d.sleepMinutes,0)/arr.length/60;
      const diff = avgSleep(withTag) - avgSleep(withoutTag);
      if (Math.abs(diff) < 0.2) return;
      lines.push({ tag, diff, n: withTag.length });
    });
    el.innerHTML = '';
    if (!lines.length) {
      el.innerHTML = '<span class="muted">Log tags on a few more days to see patterns in your own data here.</span>';
      return;
    }
    lines.forEach(({ tag, diff, n }) => {
      const row = document.createElement('div');
      row.className = 'correlation-row';
      const dir = diff < 0 ? 'less' : 'more';
      row.textContent = 'On the '+n+' days you logged "'+tag+'," you slept '+Math.abs(diff).toFixed(1)+'h '+dir+' on average than on days without it.';
      el.appendChild(row);
    });
  }

  // ---------------- Achievements ----------------
  const BADGES = [
    { icon:'🔥', name:'3-Day Streak', check: () => computeStreak(state.localDayIndex, settings.stepGoal) >= 3 },
    { icon:'💯', name:'10K Day', check: () => Object.values(state.localDayIndex).some((d)=>(d.steps||0)>=10000) },
    { icon:'🌙', name:'Well Rested', check: () => Object.values(state.localDayIndex).some((d)=>(d.sleepMinutes||0)>=480) },
    { icon:'⚡', name:'High Strain', check: () => Object.values(state.localDayIndex).some((d)=>{ const s=computeStrainScore(d); return s!=null && s>=14; }) },
    { icon:'⚖️', name:'Balanced Week', check: () => {
        let counts; try { counts = collectMuscleCounts().counts; } catch { return false; }
        const upper=(counts.chest||0)+(counts.arms||0)+(counts.shoulders||0);
        const lower=(counts.quads||0)+(counts.calves||0);
        return upper>0 && lower>0;
      } },
    { icon:'📓', name:'Journaler', check: () => Object.values(state.localDayIndex).filter((d)=>(d.journalTags||[]).length>0).length >= 5 },
  ];

  function renderBadges() {
    const grid = $('badge-grid');
    if (!grid) return;
    grid.innerHTML = '';
    BADGES.forEach((b) => {
      let earned = false;
      try { earned = !!b.check(); } catch { earned = false; }
      const el = document.createElement('div');
      el.className = 'badge' + (earned ? ' earned' : '');
      el.innerHTML = '<span class="badge-icon">'+b.icon+'</span><span class="badge-name">'+b.name+'</span>';
      grid.appendChild(el);
    });
  }


  // Talks to /api/coach (a stateless server-side proxy — see server/index.js)
  // rather than any AI API directly, since a secret key can never live in
  // browser JS. All of your actual data still stays in this browser's
  // localStorage; only the single question + a data snapshot are sent per ask.
  function buildCoachContext() {
    const day = selectedDay();
    const strain = computeStrainScore(day);
    const recentDays = Object.values(state.localDayIndex)
      .filter((d) => d.steps || d.sleepMinutes || d.heartRateAvg)
      .sort((a, b) => (a.date < b.date ? -1 : 1))
      .slice(-7)
      .map((d) => ({
        date: d.date,
        steps: d.steps || 0,
        sleepHours: +(((d.sleepMinutes || 0) / 60).toFixed(1)),
        heartRateAvg: d.heartRateAvg || null,
        journalTags: d.journalTags || [],
      }));
    let muscleThisWeek = {};
    try { muscleThisWeek = collectMuscleCounts().counts; } catch { muscleThisWeek = {}; }
    return {
      today: { date: day.date, steps: day.steps || 0, sleepHours: +(((day.sleepMinutes || 0) / 60).toFixed(1)), heartRateAvg: day.heartRateAvg || null, strain },
      last7Days: recentDays,
      muscleGroupsHitThisWeek: muscleThisWeek,
      dailyStepGoal: settings.stepGoal,
    };
  }

  function appendCoachMessage(text, who) {
    const area = $('coach-messages');
    if (!area) return null;
    const div = document.createElement('div');
    div.className = 'coach-msg ' + (who === 'user' ? 'coach-msg-user' : 'coach-msg-ai');
    div.textContent = text;
    area.appendChild(div);
    area.scrollTop = area.scrollHeight;
    return div;
  }

  async function askCoach(question) {
    if (!question) return;
    appendCoachMessage(question, 'user');
    const pending = appendCoachMessage('Thinking…', 'ai');
    try {
      const res = await fetch('/api/coach', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, context: buildCoachContext() }),
      });
      const data = await res.json();
      if (pending) pending.textContent = res.ok ? data.reply : ('⚠️ ' + (data.error || 'Something went wrong.'));
    } catch (err) {
      if (pending) pending.textContent = '⚠️ Could not reach the coach: ' + err.message;
    }
    const area = $('coach-messages');
    if (area) area.scrollTop = area.scrollHeight;
  }

  const coachSendBtn = $('coach-send');
  const coachInput = $('coach-input');
  if (coachSendBtn && coachInput) {
    const send = () => {
      const q = coachInput.value.trim();
      if (!q) return;
      coachInput.value = '';
      askCoach(q);
    };
    coachSendBtn.addEventListener('click', send);
    coachInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });
  }
  const coachPlanBtn = $('coach-plan-btn');
  if (coachPlanBtn) {
    coachPlanBtn.addEventListener('click', () => {
      askCoach("Based on my data this week (steps, sleep, heart rate, Strain, and which muscle groups I've trained), what should tomorrow's plan look like?");
    });
  }

  function download(name, text, mime) {
    if (window.KestrelAndroidBridge && typeof window.KestrelAndroidBridge.saveFile === 'function') {
      try {
        const msg = window.KestrelAndroidBridge.saveFile(name, mime || 'text/plain', text);
        if (window.KestrelAndroidBridge.showToast) window.KestrelAndroidBridge.showToast(String(msg));
        return;
      } catch (e) { console.warn('[download via bridge failed]', e); }
    }
    const a=document.createElement('a'); a.href=URL.createObjectURL(new Blob([text],{type:mime})); a.download=name; a.click();
    setTimeout(()=>URL.revokeObjectURL(a.href),500);
  }

  // ---------------- Tab navigation: 5 grouped pages ----------------
  // Vitals (steps / heart / calories / sleep) · Wellness (scores, strain, PAI, mood, journal)
  // Activity (run + workouts) · Plan · Settings (band, alarms, notifications, backup)
  const GROUP_TITLES = { vitals: 'Vitals', wellness: 'Wellness', activity: 'Activity', plan: 'Plan', settings: 'Settings' };
  const GROUP_ORDER = ['vitals', 'wellness', 'activity', 'plan', 'settings'];
  let currentGroup = 'vitals';
  const tabStrip = $('tab-strip');

  function moveTabIndicator() {
    const ind = $('tab-indicator'), act = tabStrip && tabStrip.querySelector('.tab-strip-btn.active');
    if (!ind || !act) return;
    ind.style.width = act.offsetWidth + 'px';
    ind.style.transform = 'translateX(' + act.offsetLeft + 'px)';
    ind.classList.add('ready');
  }
  window.addEventListener('resize', moveTabIndicator);

  function buildChips(group) {
    const bar = $('group-chips'); if (!bar) return;
    const secs = Array.from(document.querySelectorAll('.tab-page[data-group="' + group + '"]'));
    bar.innerHTML = '';
    bar.classList.toggle('hidden', secs.length < 2);
    secs.forEach((sec, i) => {
      const chip = document.createElement('button');
      chip.type = 'button'; chip.className = 'chip' + (i === 0 ? ' active' : '');
      chip.innerHTML = sec.dataset.label || sec.dataset.page; chip.dataset.page = sec.dataset.page;
      chip.addEventListener('click', () => {
        const y = sec.getBoundingClientRect().top + window.scrollY - (bar.offsetHeight + 8);
        window.scrollTo({ top: Math.max(0, y), behavior: 'smooth' });
      });
      bar.appendChild(chip);
    });
  }
  function syncChips() {
    const bar = $('group-chips'); if (!bar || bar.classList.contains('hidden')) return;
    const secs = Array.from(document.querySelectorAll('.tab-page.active'));
    let cur = secs[0];
    secs.forEach((sec) => { if (sec.getBoundingClientRect().top <= bar.offsetHeight + 40) cur = sec; });
    bar.querySelectorAll('.chip').forEach((c) => {
      const on = cur && c.dataset.page === cur.dataset.page;
      if (on && !c.classList.contains('active')) c.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' });
      c.classList.toggle('active', !!on);
    });
  }
  let chipRaf = 0;
  window.addEventListener('scroll', () => { if (chipRaf) return; chipRaf = requestAnimationFrame(() => { chipRaf = 0; syncChips(); }); }, { passive: true });

  function renderGroup(group) {
    try {
      if (group === 'vitals') { renderDayRow(); renderChart(); renderToday(visibleDays()); renderHourly(); }
      else if (group === 'wellness') { renderStrain(); renderBadges(); renderCorrelations(); renderNote(); }
      else if (group === 'activity') { renderWorkouts(); renderMuscleMap(); renderRecords(visibleDays()); }
    } catch (err) { console.warn('[renderGroup]', group, err); }
    try { document.dispatchEvent(new CustomEvent('kestrel:group', { detail: group })); } catch (e) {}
  }

  function goToGroup(group, silent) {
    if (!GROUP_TITLES[group]) group = 'vitals';
    const dir = GROUP_ORDER.indexOf(group) >= GROUP_ORDER.indexOf(currentGroup) ? 'fwd' : 'back';
    const pagesEl = document.querySelector('.tab-pages');
    if (pagesEl) { pagesEl.classList.remove('dir-fwd', 'dir-back'); void pagesEl.offsetWidth; pagesEl.classList.add('dir-' + dir); }
    currentGroup = group;
    document.querySelectorAll('.tab-strip-btn').forEach((b) => b.classList.toggle('active', b.dataset.group === group));
    let n = 0;
    document.querySelectorAll('.tab-page').forEach((sec) => {
      const on = sec.dataset.group === group;
      sec.classList.toggle('active', on);
      if (on) { sec.style.setProperty('--sec-i', n++); sec.querySelectorAll(':scope > .sheet-stack > *, :scope > * > .glass-card, :scope > .hero-metrics, :scope > .sheet-stack > .rn-card').forEach((c, i) => c.style.setProperty('--i', Math.min(i, 9))); }
    });
    if ($('screen-title')) $('screen-title').textContent = GROUP_TITLES[group];
    document.getElementById('dashboard-view')?.classList.toggle('on-home', group === 'vitals');
    document.getElementById('flame-badge')?.classList.toggle('hidden', group !== 'vitals');
    const bleBtn = document.getElementById('ble-dash-btn');
    if (bleBtn) { bleBtn.classList.remove('hidden'); bleBtn.style.display = 'inline-flex'; }
    buildChips(group);
    moveTabIndicator();
    if (!silent) window.scrollTo({ top: 0, behavior: 'auto' });
    renderGroup(group);
    countUpAll();
  }
  window.kestrelGoto = goToGroup;

  // Count-up animation for the big numbers whenever they change.
  function countUpAll() {
    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    document.querySelectorAll('.tab-page.active .metric-big, .tab-page.active .today-steps, .tab-page.active .score-value').forEach((el) => {
      const txt = (el.textContent || '').trim();
      if (!/^\d[\d,]*(\.\d+)?$/.test(txt)) return;
      const end = parseFloat(txt.replace(/,/g, '')), dec = (txt.split('.')[1] || '').length, comma = txt.indexOf(',') >= 0;
      if (!isFinite(end) || end === 0 || el._cu === txt) return;
      el._cu = txt;
      const t0 = performance.now(), dur = 650;
      const step = (t) => {
        const p = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - p, 3), v = end * e;
        el.textContent = comma ? Math.round(v).toLocaleString('en-US') : v.toFixed(dec);
        if (p < 1) requestAnimationFrame(step); else el.textContent = txt;
      };
      requestAnimationFrame(step);
    });
  }

  if (tabStrip) {
    tabStrip.addEventListener('click', (e) => {
      const btn = e.target.closest('.tab-strip-btn');
      if (!btn) return;
      goToGroup(btn.dataset.group);
    });
    setTimeout(() => { buildChips('vitals'); moveTabIndicator(); }, 60);
  }

  gbFileInput.addEventListener('change', () => { const f=gbFileInput.files[0]; fileLabel.textContent=f?f.name:'Choose zip or database'; gbStatus.textContent=''; });
  ['dragenter','dragover'].forEach((evt)=>dropzone.addEventListener(evt,(e)=>{e.preventDefault();dropzone.classList.add('dragover');}));
  ['dragleave','drop'].forEach((evt)=>dropzone.addEventListener(evt,(e)=>{e.preventDefault();dropzone.classList.remove('dragover');}));
  dropzone.addEventListener('drop', (e) => {
    const file=e.dataTransfer.files&&e.dataTransfer.files[0]; if(!file) return;
    const dt=new DataTransfer(); dt.items.add(file); gbFileInput.files=dt.files; fileLabel.textContent=file.name;
  });
  gbLoadBtn.addEventListener('click', async () => {
    gbStatus.classList.remove('ok'); gbStatus.textContent='';
    const file=gbFileInput.files[0];
    if(!file){ gbStatus.textContent='Choose your Gadgetbridge zip first.'; return; }
    gbLoadBtn.disabled=true; gbLoadBtn.textContent='Reading…';
    try {
      const payload=await parseExportFile(file);
      if(!Object.keys(payload.days).length) throw new Error('Parsed the file but found no dated activity.');
      applyImported(payload, file.name);
    } catch(err) { gbStatus.textContent=err.message; }
    finally { gbLoadBtn.disabled=false; gbLoadBtn.textContent='Load export'; }
  });
  $('replace-btn').addEventListener('click', () => {
    gbStatus.textContent=state.meta ? state.meta.dayCount+' days stay on this phone. A new file merges in.' : '';
    gbStatus.classList.add('ok'); showUpload();
  });
  const uploadBackBtn = $('upload-back-btn');
  if (uploadBackBtn) {
    uploadBackBtn.addEventListener('click', () => {
      showDashboard();
    });
  }
  $('clear-btn').addEventListener('click', () => {
    if(!confirm('Remove the saved Kestrel data from this phone?')) return;
    clearPersisted(); fileLabel.textContent='Choose zip or database'; gbFileInput.value=''; showUpload();
  });
  const themeBtn = $('theme-btn');
  if (themeBtn) themeBtn.addEventListener('click', () => { settings.theme='light'; applyTheme('light'); saveSettings(); });
  const homeDate = $('home-date');
  if (homeDate) homeDate.addEventListener('change', () => { if (homeDate.value) setViewKey(homeDate.value); });
  const prevDayBtn = $('home-prev-day');
  const nextDayBtn = $('home-next-day');
  if (prevDayBtn) prevDayBtn.addEventListener('click', () => setViewKey(fmt(addDays(parseKey(state.viewKey || fmt(new Date())), -1))));
  if (nextDayBtn) nextDayBtn.addEventListener('click', () => setViewKey(fmt(addDays(parseKey(state.viewKey || fmt(new Date())), 1))));
  tabRow.addEventListener('click', (e) => {
    const btn=e.target.closest('.tab'); if(!btn) return;
    [...tabRow.children].forEach((t)=>t.classList.remove('active')); btn.classList.add('active');
    state.metric=btn.dataset.metric; renderChart(); renderHourly();
  });
  rangeRow.addEventListener('click', (e) => {
    const btn=e.target.closest('.range-btn'); if(!btn) return;
    [...rangeRow.children].forEach((t)=>t.classList.remove('active')); btn.classList.add('active');
    settings.range=btn.dataset.range;
    if(settings.range==='month') state.anchor=startOfMonth(state.anchor);
    if(settings.range==='week') state.anchor=startOfWeek(state.anchor);
    if(settings.range==='today') state.anchor=new Date();
    saveSettings(); refresh();
  });
  $('prev-week').addEventListener('click', () => {
    if(settings.range==='today') state.anchor=addDays(state.anchor,-1);
    else if(settings.range==='month') state.anchor=startOfMonth(addDays(startOfMonth(state.anchor),-1));
    else state.anchor=addDays(state.anchor,-7);
    refresh();
  });
  $('next-week').addEventListener('click', () => {
    if(settings.range==='today') state.anchor=addDays(state.anchor,1);
    else if(settings.range==='month') state.anchor=startOfMonth(addDays(startOfMonth(state.anchor),32));
    else state.anchor=addDays(state.anchor,7);
    refresh();
  });
  deviceSelect.addEventListener('change', () => { settings.deviceId=deviceSelect.value; saveSettings(); refresh(); });
  goalInput.addEventListener('change', () => {
    const n=Number(goalInput.value);
    settings.stepGoal=Number.isFinite(n)&&n>=1000?Math.round(n):8000;
    goalInput.value=settings.stepGoal; saveSettings(); refresh();
  });
  noteInput.addEventListener('input', () => {
    const day=selectedDay();
    if(!state.localDayIndex[day.date]) state.localDayIndex[day.date]=emptyDay(day.date);
    state.localDayIndex[day.date].notes=noteInput.value; persistAll();
  });
  $('export-csv').addEventListener('click', () => {
    const rows=[['date','steps','heart_rate_avg','sleep_hours','distance_km','calories','stress_avg','battery','notes']];
    Object.keys(state.localDayIndex).sort().forEach((k)=>{
      const d=state.localDayIndex[k];
      rows.push([k,d.steps||0,d.heartRateAvg||'',((d.sleepMinutes||0)/60).toFixed(2),((d.distanceMeters||0)/1000).toFixed(3),d.calories||0,d.stressAvg||'',d.batteryLast||'',JSON.stringify(d.notes||'')]);
    });
    download('band-stats.csv', rows.map((r)=>r.join(',')).join('\n'), 'text/csv');
  });
  $('export-json').addEventListener('click', () => {
    download('band-stats.json', JSON.stringify({ meta:state.meta, settings, days:state.localDayIndex, workouts:state.workouts, devices:state.devices }, null, 2), 'application/json');
  });

  [...rangeRow.children].forEach((b)=>b.classList.toggle('active', b.dataset.range===settings.range));
  if (loadPersisted()) {
    if (state.meta && state.meta.lastDate) {
      const last=parseKey(state.meta.lastDate);
      state.anchor = settings.range==='month' ? startOfMonth(last) : settings.range==='today' ? last : startOfWeek(last);
    }
    showDashboard(); refresh();
  } else {
    const today = fmt(new Date());
    state.localDayIndex = { [today]: emptyDay(today) };
    state.meta = { lastDate: today, deviceCount: 1 };
    localStorage.setItem(STORAGE_DAYS, JSON.stringify(state.localDayIndex));
    localStorage.setItem(STORAGE_META, JSON.stringify(state.meta));
    showDashboard(); refresh();
  }
  window.bandRefresh = refresh;
  window.bandShowDashboard = showDashboard;
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(()=>{});

  try { renderPlan(); } catch (err) { console.error('plan render failed', err); }

  // Auto-import handoff: if an automation (e.g. MacroDroid reacting to
  // Gadgetbridge's own Auto Export) has dropped a file on the server, pull
  // it down, run it through the exact same parser as a manual upload, then
  // tell the server to forget it. The server never stores this long-term --
  // see server/index.js -- so this check has to happen on every load, not
  // just once, for the automation to actually be useful.
  (async () => {
    try {
      const statusRes = await fetch('/api/import/status');
      const status = await statusRes.json();
      if (!status.pending) return;
      const fileRes = await fetch('/api/import');
      if (!fileRes.ok) return;
      const bytes = await fileRes.arrayBuffer();
      const file = new File([bytes], 'auto-import-' + (status.uploadedAt || Date.now()) + '.zip');
      const payload = await parseExportFile(file);
      if (Object.keys(payload.days).length) {
        applyImported(payload, file.name);
        if (gbStatus) { gbStatus.textContent = 'Auto-imported from your automation.'; gbStatus.classList.add('ok'); }
      }
      await fetch('/api/import', { method: 'DELETE' });
    } catch (err) {
      console.warn('Auto-import check failed (non-fatal):', err.message);
    }
  })();
})();
