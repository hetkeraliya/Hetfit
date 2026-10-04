const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3000;
const APK_PATHS = [
  path.join(__dirname, 'public', 'kestrel-debug.apk'),
  path.join(__dirname, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
  path.join(__dirname, 'public', 'app-debug.apk')
];

function getApkPath() {
  for (const p of APK_PATHS) {
    if (fs.existsSync(p)) return p;
  }
  return null;
}

const server = http.createServer((req, res) => {
  const urlPath = req.url.split('?')[0];

  // APK Direct Download endpoints
  if (urlPath === '/kestrel-debug.apk' || urlPath === '/app-debug.apk' || urlPath === '/download/apk' || urlPath === '/download') {
    const apkFile = getApkPath();
    if (!apkFile) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      return res.end('APK not found. Building in progress...');
    }

    const stat = fs.statSync(apkFile);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': 'attachment; filename="kestrel-debug.apk"',
      'Content-Length': stat.size,
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*'
    });

    const stream = fs.createReadStream(apkFile);
    return stream.pipe(res);
  }

  // Health / ping endpoint
  if (urlPath === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ status: 'ok', apkReady: !!getApkPath() }));
  }

  // Static files in public directory
  const publicFile = path.join(__dirname, 'public', urlPath.replace(/^\//, ''));
  if (urlPath !== '/' && fs.existsSync(publicFile) && fs.statSync(publicFile).isFile()) {
    const ext = path.extname(publicFile).toLowerCase();
    const mimeTypes = {
      '.html': 'text/html',
      '.js': 'application/javascript',
      '.css': 'text/css',
      '.json': 'application/json',
      '.png': 'image/png',
      '.jpg': 'image/jpeg',
      '.apk': 'application/vnd.android.package-archive',
      '.zip': 'application/zip'
    };
    const contentType = mimeTypes[ext] || 'application/octet-stream';
    const stat = fs.statSync(publicFile);
    res.writeHead(200, {
      'Content-Type': contentType,
      'Content-Length': stat.size,
      'Access-Control-Allow-Origin': '*'
    });
    return fs.createReadStream(publicFile).pipe(res);
  }

  // Beautiful APK Download Landing Page at root /
  const apkFile = getApkPath();
  const apkSizeMb = apkFile ? (fs.statSync(apkFile).size / (1024 * 1024)).toFixed(1) : '23.0';

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Download Kestrel APK</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
    body {
      background: linear-gradient(135deg, #1F1A20 0%, #2A232B 50%, #3D3340 100%);
      color: #FFEED7;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
    }
    .card {
      background: rgba(42, 35, 43, 0.85);
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
      border: 1px solid rgba(170, 152, 169, 0.3);
      border-radius: 28px;
      max-width: 480px;
      width: 100%;
      padding: 36px 28px;
      text-align: center;
      box-shadow: 0 20px 50px rgba(0, 0, 0, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.1);
    }
    .badge {
      display: inline-block;
      background: rgba(170, 152, 169, 0.2);
      border: 1px solid rgba(170, 152, 169, 0.4);
      color: #C4B3C2;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 1px;
      text-transform: uppercase;
      padding: 6px 14px;
      border-radius: 20px;
      margin-bottom: 20px;
    }
    .icon {
      width: 72px;
      height: 72px;
      background: linear-gradient(135deg, #AA98A9 0%, #7A6879 100%);
      border-radius: 20px;
      display: flex;
      align-items: center;
      justify-content: center;
      margin: 0 auto 20px;
      box-shadow: 0 10px 25px rgba(0,0,0,0.3);
      font-size: 34px;
    }
    h1 {
      font-size: 26px;
      font-weight: 800;
      margin-bottom: 8px;
      color: #FFEED7;
      letter-spacing: -0.5px;
    }
    p.sub {
      color: #C4B3C2;
      font-size: 14px;
      line-height: 1.5;
      margin-bottom: 28px;
    }
    .btn-download {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      width: 100%;
      background: linear-gradient(135deg, #10B981 0%, #059669 100%);
      color: #FFFFFF;
      text-decoration: none;
      font-size: 16px;
      font-weight: 700;
      padding: 16px 24px;
      border-radius: 16px;
      box-shadow: 0 8px 20px rgba(16, 185, 129, 0.35);
      transition: transform 0.15s ease, box-shadow 0.15s ease;
      cursor: pointer;
    }
    .btn-download:hover {
      transform: translateY(-2px);
      box-shadow: 0 12px 25px rgba(16, 185, 129, 0.45);
    }
    .meta {
      display: flex;
      justify-content: center;
      gap: 16px;
      margin-top: 14px;
      font-size: 12px;
      color: #AA98A9;
    }
    .instructions {
      margin-top: 28px;
      padding-top: 24px;
      border-top: 1px solid rgba(170, 152, 169, 0.2);
      text-align: left;
    }
    .instructions h3 {
      font-size: 13px;
      text-transform: uppercase;
      letter-spacing: 0.8px;
      color: #AA98A9;
      margin-bottom: 12px;
    }
    .instructions ol {
      padding-left: 20px;
      font-size: 13px;
      color: #FFEED7;
      line-height: 1.6;
    }
    .features {
      margin-top: 20px;
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: center;
    }
    .feature-tag {
      background: rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      padding: 6px 12px;
      font-size: 11px;
      color: #C4B3C2;
    }
  </style>
</head>
<body>
  <div class="card">
    <div class="badge">Official Android Build</div>
    <div class="icon">🦅</div>
    <h1>Kestrel Health Tracker</h1>
    <p class="sub">Complete Android APK with Widgets, Gadgetbridge Zip Workout Import, and Direct Mi Band 5 Bluetooth hardware sync.</p>

    <a href="/kestrel-debug.apk" class="btn-download" download="kestrel-debug.apk">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
        <path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/>
      </svg>
      Download APK (${apkSizeMb} MB)
    </a>

    <div class="meta">
      <span>Version 1.0.0</span>
      <span>•</span>
      <span>Size: ~${apkSizeMb} MB</span>
      <span>•</span>
      <span>Android 7.0+</span>
    </div>

    <div class="features">
      <span class="feature-tag">📱 Themed Home Widgets</span>
      <span class="feature-tag">📦 Gadgetbridge Zip Import</span>
      <span class="feature-tag">⌚ Mi Band 5 Bluetooth</span>
      <span class="feature-tag">🔒 100% Offline</span>
    </div>

    <div class="instructions">
      <h3>Installation Instructions:</h3>
      <ol>
        <li>Tap the <strong>Download APK</strong> button above on your Android phone.</li>
        <li>Open the downloaded <code>kestrel-debug.apk</code> file from your notifications or Downloads.</li>
        <li>If prompted, tap <em>Settings</em> and enable <em>"Allow from this source"</em> to install.</li>
        <li>Enjoy your Kestrel health app and add the home screen widgets!</li>
      </ol>
    </div>
  </div>
</body>
</html>`;

  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-cache'
  });
  res.end(html);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Kestrel APK Download Server listening on http://0.0.0.0:${PORT}`);
});
