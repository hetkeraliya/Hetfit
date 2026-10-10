(() => {
  const UUIDS = {
    miband1: '0000fee0-0000-1000-8000-00805f9b34fb',
    miband2: '0000fee1-0000-1000-8000-00805f9b34fb',
    heartrate: '0000180d-0000-1000-8000-00805f9b34fb',
  };
  const CHARS = {
    auth: '00000009-0000-3512-2118-0009af100700',
    steps: '00000007-0000-3512-2118-0009af100700',
    battery: '00000006-0000-3512-2118-0009af100700',
    activity: '00000005-0000-3512-2118-0009af100700',
    hrMeasure: '00002a37-0000-1000-8000-00805f9b34fb',
    hrControl: '00002a39-0000-1000-8000-00805f9b34fb',
  };

  const KEY_STORE = 'bandstats.authKey';
  const AUTO_RECONNECT_STORE = 'bandstats.bleAutoReconnect';
  const DEVICE_STORE = 'bandstats.bleDeviceId';

  function emit(name, detail) {
    window.dispatchEvent(new CustomEvent(name, { detail }));
  }

  function parseKey(raw) {
    const hex = String(raw || '').trim().replace(/^0x/i, '').replace(/\s+/g, '').toLowerCase();
    if (!/^[0-9a-f]{32}$/.test(hex)) {
      throw new Error('Auth key must be 32 hex characters (optionally starting with 0x).');
    }
    return hex;
  }

  function hexToBytes(hex) {
    const out = new Uint8Array(hex.length / 2);
    for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
    return out;
  }

  // AES-128 ECB, one 16-byte block — no external aesjs library.
  const AES_SBOX = [99,124,119,123,242,107,111,197,48,1,103,43,254,215,171,118,202,130,201,125,250,89,71,240,173,212,162,175,156,164,114,192,183,253,147,38,54,63,247,204,52,165,229,241,113,216,49,21,4,199,35,195,24,150,5,154,7,18,128,226,235,39,178,117,9,131,44,26,27,110,90,160,82,59,214,179,41,227,47,132,83,209,0,237,32,252,177,91,106,203,190,57,74,76,88,207,208,239,170,251,67,77,51,133,69,249,2,127,80,60,159,168,81,163,64,143,146,157,56,245,188,182,218,33,16,255,243,210,205,12,19,236,95,151,68,23,196,167,126,61,100,93,25,115,96,129,79,220,34,42,144,136,70,238,184,20,222,94,11,219,224,50,58,10,73,6,36,92,194,211,172,98,145,149,228,121,231,200,55,109,141,213,78,169,108,86,244,234,101,122,174,8,186,120,37,46,28,166,180,198,232,221,116,31,75,189,139,138,112,62,181,102,72,3,246,14,97,53,87,185,134,193,29,158,225,248,152,17,105,217,142,148,155,30,135,233,206,85,40,223,140,161,137,13,191,230,66,104,65,153,45,15,176,84,187,22];
  const AES_RCON = [0,1,2,4,8,16,32,64,128,27,54];

  function aes128EcbEncrypt(keyBytes, block) {
    const w = new Uint32Array(44);
    for (let i = 0; i < 4; i++) {
      w[i] = (keyBytes[i * 4] << 24) | (keyBytes[i * 4 + 1] << 16) | (keyBytes[i * 4 + 2] << 8) | keyBytes[i * 4 + 3];
    }
    for (let i = 4; i < 44; i++) {
      let t = w[i - 1];
      if (i % 4 === 0) {
        t = ((t << 8) | (t >>> 24)) >>> 0;
        t = ((AES_SBOX[t >>> 24] << 24) | (AES_SBOX[(t >>> 16) & 255] << 16) | (AES_SBOX[(t >>> 8) & 255] << 8) | AES_SBOX[t & 255]) >>> 0;
        t = (t ^ (AES_RCON[i / 4] << 24)) >>> 0;
      }
      w[i] = (w[i - 4] ^ t) >>> 0;
    }
    const s = new Uint8Array(16);
    s.set(block);
    const addKey = (round) => {
      for (let i = 0; i < 4; i++) {
        const k = w[round * 4 + i];
        s[i * 4] ^= k >>> 24;
        s[i * 4 + 1] ^= (k >>> 16) & 255;
        s[i * 4 + 2] ^= (k >>> 8) & 255;
        s[i * 4 + 3] ^= k & 255;
      }
    };
    addKey(0);
    for (let round = 1; round <= 10; round++) {
      for (let i = 0; i < 16; i++) s[i] = AES_SBOX[s[i]];
      const t1 = s[1]; s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = t1;
      const t2a = s[2], t2b = s[6]; s[2] = s[10]; s[6] = s[14]; s[10] = t2a; s[14] = t2b;
      const t3 = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t3;
      if (round !== 10) {
        for (let c = 0; c < 4; c++) {
          const a0 = s[c * 4], a1 = s[c * 4 + 1], a2 = s[c * 4 + 2], a3 = s[c * 4 + 3];
          const xt = (x) => ((x << 1) ^ ((x & 0x80) ? 0x1b : 0)) & 255;
          s[c * 4]     = xt(a0) ^ xt(a1) ^ a1 ^ a2 ^ a3;
          s[c * 4 + 1] = a0 ^ xt(a1) ^ xt(a2) ^ a2 ^ a3;
          s[c * 4 + 2] = a0 ^ a1 ^ xt(a2) ^ xt(a3) ^ a3;
          s[c * 4 + 3] = xt(a0) ^ a0 ^ a1 ^ a2 ^ xt(a3);
        }
      }
      addKey(round);
    }
    return s;
  }

  function aesEcbEncrypt(keyHex, data) {
    const key = hexToBytes(keyHex);
    const src = data instanceof Uint8Array ? data : new Uint8Array(data);
    if (src.length !== 16) throw new Error('Band auth challenge was not 16 bytes.');
    return aes128EcbEncrypt(key, src);
  }

  function concatBytes(a, b) {
    const out = new Uint8Array(a.length + b.length);
    out.set(a, 0);
    out.set(b, a.length);
    return out;
  }

  function waitForAuthPacket(char, match, timeoutMs) {
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => {
        char.removeEventListener('characteristicvaluechanged', onVal);
        reject(new Error('Band did not answer auth in time. Check the key and that the band is close.'));
      }, timeoutMs);
      function onVal(ev) {
        const v = new Uint8Array(ev.target.value.buffer);
        if (match(v)) {
          clearTimeout(t);
          char.removeEventListener('characteristicvaluechanged', onVal);
          resolve(v);
        }
      }
      char.addEventListener('characteristicvaluechanged', onVal);
    });
  }

  let session = {
    device: null,
    server: null,
    connected: false,
    liveHr: null,
  };

  async function authenticate(server, keyHex) {
    const svc = await server.getPrimaryService(UUIDS.miband2);
    const auth = await svc.getCharacteristic(CHARS.auth);
    await auth.startNotifications();

    const keyBytes = hexToBytes(keyHex);

    try {
      await auth.writeValueWithoutResponse(concatBytes(new Uint8Array([0x01, 0x00]), new Uint8Array(keyBytes)));
    } catch {
      await auth.writeValue(concatBytes(new Uint8Array([0x01, 0x00]), new Uint8Array(keyBytes)));
    }

    const write = async (bytes) => {
      try { await auth.writeValueWithoutResponse(bytes); }
      catch { await auth.writeValue(bytes); }
    };

    await new Promise((r) => setTimeout(r, 200));
    await write(new Uint8Array([0x02, 0x00]));

    const challengePkt = await waitForAuthPacket(auth, (v) => v.length >= 19 && v[0] === 0x10 && v[1] === 0x02, 8000);
    if (challengePkt[2] !== 0x01) throw new Error('Band rejected the random-number request. Key may be wrong.');
    const challenge = challengePkt.slice(3, 19);
    const encrypted = aesEcbEncrypt(keyHex, challenge);
    await write(concatBytes(new Uint8Array([0x03, 0x00]), encrypted));

    const done = await waitForAuthPacket(auth, (v) => v.length >= 3 && v[0] === 0x10 && v[1] === 0x03, 8000);
    if (done[2] !== 0x01) throw new Error('Auth failed. Re-check the key, and do not unpair the band from Gadgetbridge/Zepp.');
    return auth;
  }

  function readUIntLE(buf, offset, bytes) {
    let out = 0;
    for (let i = 0; i < bytes; i++) out += (buf[offset + i] || 0) * (256 ** i);
    return out;
  }

  function parseSteps(buf) {
    if (!buf || buf.length < 2) return { steps: null, distanceMeters: null, calories: null, valid: false };
    const steps = buf.length >= 13 ? readUIntLE(buf, 1, 2) : readUIntLE(buf, 0, 2);
    const distanceMeters = buf.length >= 13 ? readUIntLE(buf, 5, 4) : null;
    const calories = buf.length >= 13 ? readUIntLE(buf, 9, 4) : null;
    const valid = Number.isFinite(steps) && steps >= 0 && steps <= 150000;
    return {
      steps: valid ? steps : null,
      distanceMeters: Number.isFinite(distanceMeters) && distanceMeters >= 0 ? distanceMeters : null,
      calories: Number.isFinite(calories) && calories >= 0 ? calories : null,
      valid,
    };
  }

  function parseHeartRatePacket(buf) {
    if (!buf || buf.length < 2) return null;
    const flags = buf[0];
    const is16Bit = !!(flags & 0x01);
    const hr = is16Bit && buf.length >= 3 ? readUIntLE(buf, 1, 2) : buf[1];
    return Number.isFinite(hr) && hr > 0 && hr < 254 ? hr : null;
  }

  async function readLive(server) {
    const out = { steps: null, distanceMeters: null, calories: null, battery: null, hr: session.liveHr, sleepMinutes: null, workouts: [] };
    try {
      const s1 = await server.getPrimaryService(UUIDS.miband1);
      try {
        const stepsChar = await s1.getCharacteristic(CHARS.steps);
        const v = new Uint8Array((await stepsChar.readValue()).buffer);
        const parsed = parseSteps(v);
        out.steps = parsed.steps;
        out.distanceMeters = parsed.distanceMeters;
        out.calories = parsed.calories;
        out.stepsValid = parsed.valid;
      } catch { /* optional */ }
      try {
        const batChar = await s1.getCharacteristic(CHARS.battery);
        const v = new Uint8Array((await batChar.readValue()).buffer);
        if (v.length) out.battery = v.length >= 2 ? v[1] : v[0];
      } catch { /* optional */ }
    } catch { /* service missing */ }
    return out;
  }

  async function startHeartRate(server) {
    const svc = await server.getPrimaryService(UUIDS.heartrate);
    const measure = await svc.getCharacteristic(CHARS.hrMeasure);
    const control = await svc.getCharacteristic(CHARS.hrControl);
    await measure.startNotifications();
    measure.addEventListener('characteristicvaluechanged', (ev) => {
      const v = new Uint8Array(ev.target.value.buffer);
      const hr = parseHeartRatePacket(v);
      if (hr != null) {
        session.liveHr = hr;
        emit('band-live', { hr: session.liveHr });
      }
    });
    try { await control.writeValue(new Uint8Array([0x15, 0x01, 0x00])); } catch { /* stop first */ }
    await new Promise((r) => setTimeout(r, 80));
    await control.writeValue(new Uint8Array([0x15, 0x01, 0x01]));
  }

  async function connectToDevice(device, keyHex) {
    emit('band-status', { state: 'connecting', text: 'Connecting to ' + (device.name || 'band') + '…' });
    const server = await device.gatt.connect();
    session.device = device;
    session.server = server;

    device.addEventListener('gattserverdisconnected', () => {
      session.connected = false;
      emit('band-status', { state: 'disconnected', text: 'Band disconnected.' });
      if (localStorage.getItem(AUTO_RECONNECT_STORE) === '1') scheduleReconnect();
    });

    emit('band-status', { state: 'auth', text: 'Authenticating with your key… tap the band if it vibrates.' });
    await authenticate(server, keyHex);

    session.connected = true;
    localStorage.setItem(AUTO_RECONNECT_STORE, '1');
    if (device.id) localStorage.setItem(DEVICE_STORE, device.id);
    emit('band-status', { state: 'connected', text: 'Connected to ' + (device.name || 'Mi Band') });

    try { await startHeartRate(server); } catch (err) {
      emit('band-status', { state: 'connected', text: 'Connected, but heart rate did not start: ' + err.message });
    }

    const live = await readLive(server);
    emit('band-live', live);

    if (session._poll) clearInterval(session._poll);
    session._poll = setInterval(async () => {
      if (!session.connected || !session.server) return;
      try { emit('band-live', await readLive(session.server)); } catch { /* ignore */ }
    }, 10000);

    return live;
  }

  async function connect(rawKey) {
    if (!navigator.bluetooth) {
      throw new Error('This browser has no Web Bluetooth. Use Chrome or Edge on Android or a computer — not Safari on iPhone.');
    }
    const keyHex = parseKey(rawKey);
    localStorage.setItem(KEY_STORE, keyHex);

    emit('band-status', { state: 'picking', text: 'Pick your Mi Band in the browser popup…' });

    const device = await navigator.bluetooth.requestDevice({
      filters: [
        { namePrefix: 'Mi Smart Band' },
        { namePrefix: 'Mi Band' },
        { namePrefix: 'Amazfit' },
        { services: [UUIDS.miband1] },
      ],
      optionalServices: [UUIDS.miband1, UUIDS.miband2, UUIDS.heartrate],
    });

    return connectToDevice(device, keyHex);
  }

  async function reconnectSaved() {
    if (!navigator.bluetooth || typeof navigator.bluetooth.getDevices !== 'function') return null;
    const keyHex = parseKey(localStorage.getItem(KEY_STORE) || '');
    const devices = await navigator.bluetooth.getDevices();
    const savedId = localStorage.getItem(DEVICE_STORE);
    const device = devices.find((d) => d && d.gatt && (!savedId || d.id === savedId)) || devices.find((d) => d && d.gatt);
    return device ? connectToDevice(device, keyHex) : null;
  }

  function scheduleReconnect(delayMs) {
    if (session._reconnectTimer || session.connected) return;
    session._reconnectTimer = setTimeout(async () => {
      session._reconnectTimer = null;
      if (session.connected || localStorage.getItem(AUTO_RECONNECT_STORE) !== '1') return;
      try {
        emit('band-status', { state: 'connecting', text: 'Reconnecting to saved band…' });
        await reconnectSaved();
      } catch {
        scheduleReconnect(5000);
      }
    }, delayMs == null ? 1500 : delayMs);
  }

  async function disconnect() {
    localStorage.setItem(AUTO_RECONNECT_STORE, '0');
    if (session._reconnectTimer) { clearTimeout(session._reconnectTimer); session._reconnectTimer = null; }
    if (session._poll) { clearInterval(session._poll); session._poll = null; }
    try { if (session.device && session.device.gatt.connected) session.device.gatt.disconnect(); } catch { /* */ }
    session.connected = false;
    session.device = null;
    session.server = null;
    emit('band-status', { state: 'disconnected', text: 'Disconnected.' });
  }

  window.BandBLE = {
    connect,
    reconnectSaved,
    disconnect,
    isSupported: () => !!(navigator.bluetooth),
    savedKey: () => localStorage.getItem(KEY_STORE) || '',
    isConnected: () => session.connected,
    wantsReconnect: () => localStorage.getItem(AUTO_RECONNECT_STORE) === '1',
  };
})();
