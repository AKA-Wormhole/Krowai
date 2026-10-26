# Tink (pulled in by androidx.security:security-crypto) references compile-time-only annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**

# Tink KeysDownloader (unused on Android) references Google HTTP client + Joda-Time
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn com.google.crypto.tink.util.KeysDownloader

# Keep Tink / EncryptedSharedPreferences intact (uses reflection + protobuf-lite)
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# OkHttp / Okio optional platforms
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.krow.** { kotlinx.serialization.KSerializer serializer(...); }

# jsoup optional annotations / regex engine
-dontwarn javax.annotation.**
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
