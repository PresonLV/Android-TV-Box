-keep class app.jianxia.core.** { *; }
-keep class androidx.media3.** { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class <1> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

-dontwarn okhttp3.**
-keep class okhttp3.dnsoverhttps.** { *; }
-dontwarn org.jsoup.**
-dontwarn kotlinx.serialization.**
