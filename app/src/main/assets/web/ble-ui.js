(() => {
  const keyInput = document.getElementById('ble-key');
  const connectBtn = document.getElementById('ble-connect-btn');
  const statusEl = document.getElementById('ble-status');
  const dashBtn = document.getElementById('ble-dash-btn');

  function fmt(d) {
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }

  const MAX_LIVE_STEPS = 150000;

  function setStatus(text, ok) {
    if (!statusEl) return;
    statusEl.textContent = text || '';
    statusEl.classList.toggle('ok', !!ok);
  }

  function applyLive(detail) {
    const key = fmt(new Date());
    let days = {};
    try { days = JSON.parse(localStorage.getItem('bandstats.days') || '{}') || {}; } catch (e) {}
    if (!days[key]) {
      days[key] = {
        date: key, steps: 0, calories: 0, distanceMeters: 0,
        sleepMinutes: 0, heartRateAvg: null, heartRateMin: null, heartRateMax: null,
        stressAvg: null, batteryLast: null, hourlySteps: Array(24).fill(0),
        deviceIds: {}, notes: '', estimated: false,
      };
    }
    const day = days[key];
    const steps = Number(detail.steps);
    if (Number.isFinite(steps) && steps >= 0 && steps <= MAX_LIVE_STEPS) {
      day.steps = Math.max(Number(day.steps) || 0, Math.round(steps));
    }
    const hr = Number(detail.hr);
    if (Number.isFinite(hr) && hr > 0 && hr < 254) {
      day._hrSum = (Number(day._hrSum) || 0) + hr;
      day._hrCount = (Number(day._hrCount) || 0) + 1;
      day.heartRateAvg = Math.round(day._hrSum / day._hrCount);
      day.heartRateMin = day.heartRateMin == null ? hr : Math.min(day.heartRateMin, hr);
      day.heartRateMax = day.heartRateMax == null ? hr : Math.max(day.heartRateMax, hr);
    }
    if (detail.distanceMeters != null) day.distanceMeters = Number(detail.distanceMeters) || day.distanceMeters;
    if (detail.calories != null) day.calories = Number(detail.calories) || day.calories;
    if (detail.battery != null) day.batteryLast = detail.battery;
    localStorage.setItem('bandstats.days', JSON.stringify(days));
    if (typeof window.bandRefresh === 'function') window.bandRefresh();
  }

  async function startConnect() {
    if (!window.BandBLE) {
      setStatus('Bluetooth helper did not load. Refresh the page.');
      return;
    }
    if (!BandBLE.isSupported()) {
      setStatus('Web Bluetooth is missing. Use Chrome or Edge on Android or a computer.');
      return;
    }
    const key = (keyInput && keyInput.value) || BandBLE.savedKey();
    if (keyInput && !keyInput.value && key) keyInput.value = key;
    connectBtn.disabled = true;
    connectBtn.textContent = 'Connecting…';
    try {
      await BandBLE.connect(key);
      setStatus('Connected. Live steps, battery, and heart rate will update. Use Gadgetbridge export for full sleep/workout history.', true);
      if (typeof window.bandShowDashboard === 'function') window.bandShowDashboard();
    } catch (err) {
      if (err && err.name === 'NotFoundError') setStatus('No device picked.');
      else setStatus(err.message || String(err));
    } finally {
      connectBtn.disabled = false;
      connectBtn.textContent = BandBLE.isConnected() ? 'Disconnect' : 'Connect with Bluetooth';
    }
  }

  if (keyInput && window.BandBLE) keyInput.value = BandBLE.savedKey();

  async function tryReconnect(attempt) {
    if (!window.BandBLE || !BandBLE.savedKey() || !BandBLE.reconnectSaved || BandBLE.isConnected()) return;
    if (BandBLE.wantsReconnect && !BandBLE.wantsReconnect()) return;
    const n = attempt || 0;
    const backoffMs = [0, 1500, 3000, 6000, 10000];
    try {
      setStatus(n === 0 ? 'Reconnecting to saved band…' : 'Still trying to reconnect (' + (n + 1) + '/' + backoffMs.length + ')…');
      await BandBLE.reconnectSaved();
      if (BandBLE.isConnected()) {
        setStatus('Reconnected. Live steps, battery, and heart rate will update.', true);
        if (typeof window.bandShowDashboard === 'function') window.bandShowDashboard();
        return;
      }
      throw new Error('no device returned');
    } catch (err) {
      if (n + 1 < backoffMs.length) {
        setTimeout(() => tryReconnect(n + 1), backoffMs[n + 1]);
      } else {
        setStatus('Reload disconnected Bluetooth and the band wasn\'t reachable for auto-reconnect. Make sure it\'s nearby and tap Connect.');
      }
    }
  }

  tryReconnect();
  window.addEventListener('pageshow', () => tryReconnect());
  window.addEventListener('focus', () => tryReconnect());
  document.addEventListener('visibilitychange', () => { if (!document.hidden) tryReconnect(); });

  if (connectBtn) {
    connectBtn.addEventListener('click', async () => {
      if (window.BandBLE && BandBLE.isConnected()) {
        await BandBLE.disconnect();
        connectBtn.textContent = 'Connect with Bluetooth';
        return;
      }
      startConnect();
    });
  }

  if (dashBtn) {
    dashBtn.addEventListener('click', () => {
      const upload = document.getElementById('upload-view');
      if (upload) {
        upload.classList.remove('hidden');
        const box = document.querySelector('.ble-box');
        if (box) box.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    });
  }

  window.addEventListener('band-status', (e) => {
    setStatus(e.detail.text, e.detail.state === 'connected');
    if (connectBtn && window.BandBLE) {
      connectBtn.textContent = BandBLE.isConnected() ? 'Disconnect' : 'Connect with Bluetooth';
    }
  });
  window.addEventListener('band-live', (e) => applyLive(e.detail || {}));
})();
