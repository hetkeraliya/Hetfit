# Kestrel / Hetfit - Build Instructions

## GitHub Actions (Recommended - works from mobile)

1. Push this entire folder to a GitHub repository.
2. Go to the **Actions** tab → select **Build APK** → **Run workflow**.
3. After ~4-6 minutes the workflow finishes.
4. Download the artifact named **kestrel-debug-apk**.

## Local Build (Computer)

```bash
# Requires JDK 17 + Android SDK (platform 36)
./gradlew assembleDebug
```

APK location: `app/build/outputs/apk/debug/app-debug.apk`

## What was fixed

- Made debug signing config optional (no longer fails when `debug.keystore` is missing)
- Added complete Gradle wrapper
- Added GitHub Actions workflow
- Restored sensible Gradle memory settings
