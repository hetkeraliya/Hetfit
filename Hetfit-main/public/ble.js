/**
 * Kestrel Health - Mi Band 5 Web Bluetooth & Live Metrics Sync Module
 *
 * Implements:
 * 1. Xiaomi / Huami BLE Authentication handshake protocol (Mi Band 5 / 4 / 6).
 * 2. AES-128-ECB challenge-response encryption via aes-js.
 * 3. Real-time Heart Rate stream subscription (Service 0x180D, Char 0x2A37).
 * 4. Real-time Steps / Activity stream subscription (Service 0xFEE0, Char 0x0007).
 * 5. Direct live integration into Kestrel dashboard metrics without modifying UI layout.
 */

(function (window) {
  'use strict';

  // UUID Definitions for Mi Band 5 / Huami
  const UUIDS = {
    // Huami Auth Service & Characteristics
    AUTH_SERVICE: '0000fee1-0000-1000-8000-00805f9b34fb',
    AUTH_CHAR: '00000009-0000-3512-2118-0009af100700',

    // Heart Rate Service & Characteristics (Bluetooth SIG Standard)
    HR_SERVICE: '0000180d-0000-1000-8000-00805f9b34fb',
    HR_MEASUREMENT_CHAR: '00002a37-0000-1000-8000-00805f9b34fb',
    HR_CONTROL_POINT_CHAR: '00002a39-0000-1000-8000-00805f9b34fb',

    // Activity / Steps Service (Mi Basic / Huami)
    ACTIVITY_SERVICE: '0000fee0-0000-1000-8000-00805f9b34fb',
    STEPS_CHAR: '00000007-0000-3512-2118-0009af100700',
    BATTERY_CHAR: '00000006-0000-3512-2118-0009af100700'
  };

  // Auth Protocol Commands
  const CMD = {
    REQ_RANDOM_KEY: new Uint8Array([0x01, 0x08]),
    SEND_ENCRYPTED_KEY: 0x03,
    COMMAND_RESPONSE: 0x10,
    AUTH_SUCCESS: 0x01,
    AUTH_FAIL: 0x04
  };

  class MiBand5Sync {
    constructor() {
      this.device = null;
      this.server = null;
      this.authChar = null;
      this.hrChar = null;
      this.hrControlChar = null;
      this.stepsChar = null;

      this.authKey = null; // 16-byte Uint8Array
      this.isAuthenticated = false;
      this.isConnected = false;
      this.hrKeepAliveTimer = null;

      this.latestMetrics = {
        heartRate: null,
        steps: 0,
        distanceMeters: 0,
        calories: 0,
        timestamp: null
      };

      // Load existing key from localStorage if stored
      this.loadStoredAuthKey();
    }

    /**
     * Set the 16-byte Auth Key (supports 32-character hex string with or without '0x')
     */
    setAuthKey(hexString) {
      if (!hexString || typeof hexString !== 'string') {
        throw new Error('Auth key must be a 32-character hexadecimal string.');
      }
      const cleanHex = hexString.trim().replace(/^0x/i, '').replace(/[\s-:]/g, '');
      if (cleanHex.length !== 32) {
        throw new Error(`Invalid auth key length (${cleanHex.length}). Expected 32 hex characters (16 bytes).`);
      }

      const bytes = new Uint8Array(16);
      for (let i = 0; i < 16; i++) {
        bytes[i] = parseInt(cleanHex.substr(i * 2, 2), 16);
      }
      this.authKey = bytes;
      localStorage.setItem('kestrel_mi_auth_key', cleanHex.toLowerCase());
      console.log('[Kestrel BLE] Mi Band Auth key saved successfully.');
      return cleanHex.toLowerCase();
    }

    getStoredAuthKey() {
      return localStorage.getItem('kestrel_mi_auth_key') || null;
    }

    loadStoredAuthKey() {
      const stored = this.getStoredAuthKey();
      if (stored) {
        try {
          this.setAuthKey(stored);
        } catch (e) {
          console.warn('[Kestrel BLE] Failed to load stored auth key:', e);
        }
      }
    }

    /**
     * Request Bluetooth device & initiate connection
     */
    async connect() {
      if (!navigator.bluetooth) {
        throw new Error('Web Bluetooth is not supported or not enabled in this environment.');
      }

      // Check for auth key before proceeding
      if (!this.authKey) {
        const input = window.prompt(
          'Please enter your Mi Band 5 16-byte Auth Key (32 hex characters from Zepp / Freemyband):',
          this.getStoredAuthKey() || ''
        );
        if (!input) {
          throw new Error('Mi Band 5 authentication requires a 16-byte Auth Key.');
        }
        this.setAuthKey(input);
      }

      this.updateStatus('Requesting Mi Band 5...');
      console.log('[Kestrel BLE] Requesting Bluetooth Device...');

      try {
        this.device = await navigator.bluetooth.requestDevice({
          filters: [
            { namePrefix: 'Mi Smart Band 5' },
            { namePrefix: 'Mi Band 5' },
            { services: [UUIDS.AUTH_SERVICE] }
          ],
          optionalServices: [
            UUIDS.AUTH_SERVICE,
            UUIDS.HR_SERVICE,
            UUIDS.ACTIVITY_SERVICE
          ]
        });

        this.device.addEventListener('gattserverdisconnected', () => this.onDisconnected());

        this.updateStatus('Connecting to GATT Server...');
        this.server = await this.device.gatt.connect();
        this.isConnected = true;
        console.log('[Kestrel BLE] Connected to GATT Server:', this.device.name);

        // Step 1: Perform Huami / Xiaomi Authentication handshake
        await this.authenticate();

        // Step 2: Subscribe to Real-Time Streams
        await this.initRealTimeStreams();

        this.updateStatus('Connected & Streaming Live');
        return true;
      } catch (err) {
        console.error('[Kestrel BLE] Connection error:', err);
        this.updateStatus(`Connection failed: ${err.message}`);
        this.cleanup();
        throw err;
      }
    }

    /**
     * Step-by-step Huami Authentication Protocol
     */
    async authenticate() {
      this.updateStatus('Authenticating with Mi Band 5...');
      console.log('[Kestrel BLE] Starting Huami Auth Handshake...');

      const authService = await this.server.getPrimaryService(UUIDS.AUTH_SERVICE);
      this.authChar = await authService.getCharacteristic(UUIDS.AUTH_CHAR);

      await this.authChar.startNotifications();

      return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => {
          this.authChar.removeEventListener('characteristicvaluechanged', handleAuthNotification);
          reject(new Error('Authentication handshake timed out after 15s.'));
        }, 15000);

        const handleAuthNotification = async (event) => {
          try {
            const dataView = event.target.value;
            const bytes = new Uint8Array(dataView.buffer, dataView.byteOffset, dataView.byteLength);
            console.log('[Kestrel BLE] Auth notification received:', Array.from(bytes).map(b => '0x' + b.toString(16).padStart(2, '0')).join(' '));

            // Validate Command Response: [0x10, opcode, status, ...payload]
            if (bytes.length < 3 || bytes[0] !== CMD.COMMAND_RESPONSE) {
              return;
            }

            const opCode = bytes[1];
            const status = bytes[2];

            // Response to Step 1 (Request Random Key): [0x10, 0x01, 0x01, ...16 challenge bytes]
            if (opCode === 0x01) {
              if (status !== CMD.AUTH_SUCCESS) {
                clearTimeout(timeout);
                this.authChar.removeEventListener('characteristicvaluechanged', handleAuthNotification);
                return reject(new Error(`Random challenge request failed (status: 0x${status.toString(16)}). Band may require re-pairing.`));
              }

              if (bytes.length < 19) {
                clearTimeout(timeout);
                this.authChar.removeEventListener('characteristicvaluechanged', handleAuthNotification);
                return reject(new Error(`Invalid challenge payload length (${bytes.length}). Expected 19 bytes.`));
              }

              // Extract 16-byte challenge
              const challenge = bytes.slice(3, 19);
              console.log('[Kestrel BLE] Received 16-byte Challenge. Encrypting with AES-128-ECB...');

              // Encrypt challenge with Auth Key using aesjs (loaded from public/vendor/aes-js.min.js)
              if (typeof window.aesjs === 'undefined') {
                clearTimeout(timeout);
                this.authChar.removeEventListener('characteristicvaluechanged', handleAuthNotification);
                return reject(new Error('AES library (aes-js) is not loaded on the page.'));
              }

              const aesEcb = new window.aesjs.ModeOfOperation.ecb(this.authKey);
              const encryptedChallenge = aesEcb.encrypt(challenge);

              // Send back Step 3 response: [0x03, 0x08, ...16 encrypted bytes]
              const responsePacket = new Uint8Array(18);
              responsePacket[0] = CMD.SEND_ENCRYPTED_KEY;
              responsePacket[1] = 0x08;
              responsePacket.set(encryptedChallenge, 2);

              console.log('[Kestrel BLE] Sending encrypted challenge response...');
              await this.authChar.writeValue(responsePacket);
            }

            // Response to Step 3 (Encrypted Challenge Verification): [0x10, 0x03, 0x01]
            else if (opCode === 0x03) {
              clearTimeout(timeout);
              this.authChar.removeEventListener('characteristicvaluechanged', handleAuthNotification);

              if (status === CMD.AUTH_SUCCESS) {
                this.isAuthenticated = true;
                console.log('[Kestrel BLE] *** MI BAND 5 AUTHENTICATION SUCCESSFUL ***');
                this.updateStatus('Authentication Confirmed!');
                resolve(true);
              } else {
                reject(new Error(`Authentication challenge rejected (status: 0x${status.toString(16)}). Check your 16-byte Auth Key.`));
              }
            }
          } catch (err) {
            clearTimeout(timeout);
            this.authChar.removeEventListener('characteristicvaluechanged', handleAuthNotification);
            reject(err);
          }
        };

        this.authChar.addEventListener('characteristicvaluechanged', handleAuthNotification);

        // Step 1: Send Request Random Key [0x01, 0x08]
        console.log('[Kestrel BLE] Requesting random key from band [0x01, 0x08]...');
        this.authChar.writeValue(CMD.REQ_RANDOM_KEY).catch(reject);
      });
    }

    /**
     * Subscribe to Real-Time Heart Rate and Activity / Steps
     */
    async initRealTimeStreams() {
      console.log('[Kestrel BLE] Initializing real-time sensor streams...');

      // 1. Heart Rate Stream
      try {
        const hrService = await this.server.getPrimaryService(UUIDS.HR_SERVICE);
        this.hrChar = await hrService.getCharacteristic(UUIDS.HR_MEASUREMENT_CHAR);

        await this.hrChar.startNotifications();
        this.hrChar.addEventListener('characteristicvaluechanged', (e) => this.handleHeartRateData(e));
        console.log('[Kestrel BLE] Subscribed to Heart Rate notifications.');

        // Activate continuous HR measurement via Control Point
        try {
          this.hrControlChar = await hrService.getCharacteristic(UUIDS.HR_CONTROL_POINT_CHAR);
          // Command [0x15, 0x01, 0x01] = Continuous HR Mode start
          await this.hrControlChar.writeValue(new Uint8Array([0x15, 0x01, 0x01]));
          console.log('[Kestrel BLE] Triggered continuous heart rate tracking.');

          // Ping keepalive every 12 seconds to keep continuous HR active
          this.hrKeepAliveTimer = setInterval(async () => {
            if (this.isConnected && this.hrControlChar) {
              try {
                await this.hrControlChar.writeValue(new Uint8Array([0x16]));
              } catch (_) {}
            }
          }, 12000);
        } catch (cpErr) {
          console.warn('[Kestrel BLE] Could not access HR Control Point (using default measurement):', cpErr);
        }
      } catch (err) {
        console.warn('[Kestrel BLE] Heart Rate service subscription notice:', err.message);
      }

      // 2. Activity / Steps Stream
      try {
        const activityService = await this.server.getPrimaryService(UUIDS.ACTIVITY_SERVICE);
        this.stepsChar = await activityService.getCharacteristic(UUIDS.STEPS_CHAR);

        await this.stepsChar.startNotifications();
        this.stepsChar.addEventListener('characteristicvaluechanged', (e) => this.handleStepsData(e));
        console.log('[Kestrel BLE] Subscribed to Real-Time Steps & Activity notifications.');

        // Initial read of steps
        try {
          const initialVal = await this.stepsChar.readValue();
          this.handleStepsData({ target: { value: initialVal } });
        } catch (_) {}
      } catch (err) {
        console.warn('[Kestrel BLE] Activity service subscription notice:', err.message);
      }
    }

    /**
     * Heart Rate Measurement Data Parser (Bluetooth SIG 0x2A37)
     */
    handleHeartRateData(event) {
      const dataView = event.target.value;
      if (!dataView || dataView.byteLength < 2) return;

      const flags = dataView.getUint8(0);
      let heartRate = 0;

      // 8-bit vs 16-bit HR representation
      if ((flags & 0x01) === 0) {
        heartRate = dataView.getUint8(1);
      } else {
        heartRate = dataView.getUint16(1, true);
      }

      if (heartRate > 0 && heartRate < 250) {
        const now = new Date();
        this.latestMetrics.heartRate = heartRate;
        this.latestMetrics.timestamp = now;

        console.log(`[Kestrel Live HR] ${heartRate} BPM at ${now.toLocaleTimeString()}`);
        this.feedDashboardMetrics('heart_rate', heartRate, now);
      }
    }

    /**
     * Activity & Steps Data Parser (Huami Characteristic 0x0007)
     */
    handleStepsData(event) {
      const dataView = event.target.value;
      if (!dataView || dataView.byteLength < 4) return;

      let steps = 0;
      let distanceMeters = 0;
      let calories = 0;

      // Mi Band 5 packet layout:
      // Bytes 1..4: Steps uint32 (little endian) or Bytes 0..3 depending on payload header
      if (dataView.byteLength >= 5) {
        steps = dataView.getUint32(1, true);
      } else {
        steps = dataView.getUint16(1, true);
      }

      if (dataView.byteLength >= 9) {
        distanceMeters = dataView.getUint32(5, true);
      }
      if (dataView.byteLength >= 13) {
        calories = dataView.getUint32(9, true);
      }

      const now = new Date();
      this.latestMetrics.steps = steps;
      this.latestMetrics.distanceMeters = distanceMeters;
      this.latestMetrics.calories = calories;
      this.latestMetrics.timestamp = now;

      console.log(`[Kestrel Live Steps] ${steps} steps, ${distanceMeters}m, ${calories}kcal`);
      this.feedDashboardMetrics('steps', { steps, distanceMeters, calories }, now);
    }

    /**
     * Feed live stream directly into Kestrel Dashboard metrics
     * Updates internal state, triggers custom events, and updates dials without altering existing layouts.
     */
    feedDashboardMetrics(type, value, timestamp) {
      // 1. Dispatch custom DOM event for any Kestrel visual listeners
      const event = new CustomEvent('kestrel:live-metric', {
        detail: { type, value, timestamp, allMetrics: this.latestMetrics },
        bubbles: true
      });
      window.dispatchEvent(event);

      // 2. Integration hook: Update state object if exposed in app.js
      if (window.kestrelState) {
        if (type === 'heart_rate') {
          window.kestrelState.currentHr = value;
          window.kestrelState.lastHrTimestamp = timestamp;
          if (Array.isArray(window.kestrelState.heartRateSamples)) {
            window.kestrelState.heartRateSamples.push({ timestamp, hr: value });
          }
        } else if (type === 'steps') {
          window.kestrelState.liveSteps = typeof value === 'object' ? value.steps : value;
          if (value.distanceMeters) window.kestrelState.liveDistance = value.distanceMeters;
          if (value.calories) window.kestrelState.liveCalories = value.calories;
        }
      }

      // 3. Update existing DOM metric elements if they exist in Kestrel UI
      if (type === 'heart_rate') {
        const hrDials = document.querySelectorAll('#liveHeartRate, #currentHr, .metric-hr-live, [data-metric="heart_rate"]');
        hrDials.forEach(el => {
          el.textContent = value;
        });
      } else if (type === 'steps') {
        const stepCount = typeof value === 'object' ? value.steps : value;
        const stepElements = document.querySelectorAll('#liveSteps, #currentSteps, .metric-steps-live, [data-metric="steps"]');
        stepElements.forEach(el => {
          el.textContent = Number(stepCount).toLocaleString();
        });
      }

      // 4. Trigger dashboard chart update function if present
      if (typeof window.refreshDashboardLiveMetrics === 'function') {
        window.refreshDashboardLiveMetrics(type, value);
      }
    }

    updateStatus(message) {
      console.log(`[Kestrel BLE Status] ${message}`);
      const statusEl = document.getElementById('bleStatus') || document.querySelector('.ble-status');
      if (statusEl) {
        statusEl.textContent = message;
      }
      window.dispatchEvent(new CustomEvent('kestrel:ble-status', { detail: { message } }));
    }

    onDisconnected() {
      console.warn('[Kestrel BLE] Device disconnected.');
      this.isConnected = false;
      this.isAuthenticated = false;
      this.cleanup();
      this.updateStatus('Mi Band disconnected');

      window.dispatchEvent(new CustomEvent('kestrel:ble-disconnected', {
        detail: { device: this.device }
      }));
    }

    cleanup() {
      if (this.hrKeepAliveTimer) {
        clearInterval(this.hrKeepAliveTimer);
        this.hrKeepAliveTimer = null;
      }
      this.authChar = null;
      this.hrChar = null;
      this.hrControlChar = null;
      this.stepsChar = null;
    }

    async disconnect() {
      if (this.device && this.device.gatt.connected) {
        this.device.gatt.disconnect();
      }
      this.cleanup();
    }
  }

  // Create global singleton instance
  const kestrelBle = new MiBand5Sync();
  window.kestrelBle = kestrelBle;

  // Auto-bind to existing Bluetooth trigger buttons in ble-ui.js
  function bindBleButtons() {
    const triggers = document.querySelectorAll('#connectBle, #btnBleConnect, [data-action="ble-connect"], .btn-connect-ble');
    triggers.forEach(btn => {
      if (!btn._kestrelBound) {
        btn._kestrelBound = true;
        btn.addEventListener('click', async (e) => {
          e.preventDefault();
          try {
            btn.disabled = true;
            await kestrelBle.connect();
          } catch (err) {
            console.error('BLE connection failed:', err);
          } finally {
            btn.disabled = false;
          }
        });
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindBleButtons);
  } else {
    bindBleButtons();
  }

})(window);
