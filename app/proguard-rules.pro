# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Cast framework
-keep class com.google.android.gms.cast.** { *; }
-keep class androidx.mediarouter.** { *; }

# WebView JS bridge — must keep or JS calls will break
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep annotations
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Keep our app classes that Android needs by name
-keep public class * extends android.app.Activity
-keep public class * extends androidx.appcompat.app.AppCompatActivity
-keep public class * extends com.google.firebase.messaging.FirebaseMessagingService
-keep class com.pichaplus.app.CastOptionsProvider { *; }
