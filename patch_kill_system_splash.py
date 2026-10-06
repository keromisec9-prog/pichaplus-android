import os

# 1. Add a values-v31 theme override specifically for Android 12+ (API 31+)
# where the new Splash Screen API applies
v31_dir = "app/src/main/res/values-v31"
os.makedirs(v31_dir, exist_ok=True)

v31_content = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.PichaPlus.Splash" parent="Theme.PichaPlus">
        <item name="android:windowBackground">@drawable/splash_background</item>
        <item name="android:windowSplashScreenBackground">#0f0f0f</item>
        <item name="android:windowSplashScreenAnimatedIcon">@android:color/transparent</item>
        <item name="android:windowSplashScreenIconBackgroundColor">#0f0f0f</item>
        <item name="android:windowSplashScreenBrandingImage">@android:color/transparent</item>
    </style>
</resources>
'''

with open(os.path.join(v31_dir, "styles.xml"), "w", encoding="utf-8") as f:
    f.write(v31_content)

print("✅ Created values-v31/styles.xml to suppress system splash icon on Android 12+.")
