import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.kestrel.health',
  appName: 'Kestrel',
  webDir: 'public',
  server: {
    // Allows loading from the deployed URL or falling back to bundled web assets
    url: 'https://kestrelx.vercel.app',
    androidScheme: 'https',
    cleartext: false
  },
  android: {
    allowMixedContent: false,
    captureInput: true,
    webContentsDebuggingEnabled: false
  },
  plugins: {
    CapacitorCookies: {
      enabled: true
    },
    CapacitorHttp: {
      enabled: true
    }
  }
};

export default config;
