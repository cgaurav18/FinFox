# kotlinx.serialization (backup format)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.mymoney.app.**$$serializer { *; }
-keepclassmembers class com.mymoney.app.** { *** Companion; }
-keepclasseswithmembers class com.mymoney.app.** { kotlinx.serialization.KSerializer serializer(...); }
# Room entities are referenced reflectively by generated code only; enums by name.
-keepclassmembers enum com.mymoney.app.** { *; }

# Google Drive REST (Retrofit + kotlinx.serialization models)
-keep,allowobfuscation interface com.mymoney.app.cloud.DriveService
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Argon2id (BouncyCastle lightweight API only; JCE provider classes are unused)
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# OkHttp optional platforms
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Glance action callbacks are instantiated by class name.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
