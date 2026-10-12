# Health Connect / Zepp import — manifest entries

Add these to `AndroidManifest.xml` if they are not already present:

```xml
<!-- Health Connect (read data Zepp writes on this phone) -->
<uses-permission android:name="android.permission.health.READ_STEPS" />
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />
<uses-permission android:name="android.permission.health.READ_SLEEP" />
<uses-permission android:name="android.permission.health.READ_DISTANCE" />
<uses-permission android:name="android.permission.health.READ_TOTAL_CALORIES_BURNED" />
<uses-permission android:name="android.permission.health.READ_EXERCISE" />

<queries>
  <package android:name="com.google.android.apps.healthdata" />
  <package android:name="com.huami.watch.hmwatchmanager" />
  <package android:name="com.xiaomi.hm.health" />
  <intent>
    <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
  </intent>
</queries>

<application ...>
  <activity ...>
    <!-- Optional rationale activity for HC permission rationale on Android 13 -->
    <intent-filter>
      <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
    </intent-filter>
  </activity>

  <meta-data
    android:name="health_permissions"
    android:resource="@xml/health_permissions" />
</application>
```

## User setup

1. Open **Zepp** (or Zepp Life) → Profile → **3rd-party account linking** → **Health Connect**
2. Allow write for Steps, Heart rate, Sleep, Workouts
3. Sync the band in Zepp once
4. In Hetfit → Settings → **Import from Zepp** (grant read permissions when asked)
