# Run in Termux:
#   cd ~/pichaplus-android
#   python3 patch_tv_support.py

path = "app/src/main/AndroidManifest.xml"
src = open(path, encoding="utf-8").read()

old = """    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW"/>
            <data android:scheme="https"/>
        </intent>
        <package android:name="com.whatsapp"/>
        <package android:name="com.facebook.katana"/>
        <package android:name="com.zhiliaoapp.musically"/>
    </queries>

    <application
        android:label="PichaPlus"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.PichaPlus">
        <activity
            android:name=".SplashActivity"
            android:exported="true"
            android:theme="@style/Theme.PichaPlus.Splash"
            android:screenOrientation="unspecified">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>"""

new = """    <!-- Declare TV support: leanback UI is used but not strictly required,
         so this same APK can still install on phones/tablets. -->
    <uses-feature
        android:name="android.software.leanback"
        android:required="false"/>
    <!-- TVs have no touchscreen — without this, some stores/filters exclude the app from TV -->
    <uses-feature
        android:name="android.hardware.touchscreen"
        android:required="false"/>

    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW"/>
            <data android:scheme="https"/>
        </intent>
        <package android:name="com.whatsapp"/>
        <package android:name="com.facebook.katana"/>
        <package android:name="com.zhiliaoapp.musically"/>
    </queries>

    <application
        android:label="PichaPlus"
        android:icon="@mipmap/ic_launcher"
        android:banner="@drawable/tv_banner"
        android:theme="@style/Theme.PichaPlus">
        <activity
            android:name=".SplashActivity"
            android:exported="true"
            android:theme="@style/Theme.PichaPlus.Splash"
            android:screenOrientation="unspecified">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <!-- Makes the app appear on the Android TV home screen launcher -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
            </intent-filter>
        </activity>"""

if old not in src:
    print("❌ Pattern not found. No changes made.")
else:
    src = src.replace(old, new)
    open(path, "w", encoding="utf-8").write(src)
    print("✅ Added Android TV support to AndroidManifest.xml.")
