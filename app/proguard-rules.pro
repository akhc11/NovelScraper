# JavaScriptInterface の保護（WebView IPC通信の必須設定）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Kotlinx Serialization の保護（JSONパースの必須設定）
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}

# R8 の最適化設定
-dontwarn kotlinx.serialization.**