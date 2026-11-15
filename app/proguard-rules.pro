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
