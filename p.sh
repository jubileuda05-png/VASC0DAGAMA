set -e

if [ ! -f Main.kt ]; then
  echo "FALTA o arquivo Main.kt na raiz do repositorio"
  exit 1
fi

mkdir -p src/main/java/dev/venice/sniper
mv -f Main.kt src/main/java/dev/venice/sniper/Main.kt

cat > settings.gradle <<'EOF'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
rootProject.name = "sniper"
EOF

cat > build.gradle <<'EOF'
plugins { id 'com.android.application' version '8.5.2' }
repositories { google(); mavenCentral() }
android {
    namespace 'dev.venice.sniper'
    compileSdk 34
    defaultConfig {
        applicationId 'dev.venice.sniper'
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName '1.0'
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    buildTypes { release { minifyEnabled false } }
}
dependencies { implementation 'com.squareup.okhttp3:okhttp:4.12.0' }
EOF

cat > src/main/AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <application
        android:name=".App"
        android:label="Nitro Sniper"
        android:icon="@android:drawable/sym_def_app_icon"
        android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:allowBackup="false">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".SniperService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
EOF

if command -v sdkmanager >/dev/null 2>&1; then
  yes | sdkmanager --licenses >/dev/null 2>&1 || true
fi

echo "== pronto =="
find src -type f
