# Atlantis consumer ProGuard rules
# Keep all public APIs
-keep class com.proxyman.atlantis.Atlantis { *; }
-keep class com.proxyman.atlantis.AtlantisInterceptor { *; }
-keep class com.proxyman.atlantis.AtlantisDelegate { *; }
-keep class com.proxyman.atlantis.TrafficPackage { *; }

# Keep data classes for Gson serialization
-keep class com.proxyman.atlantis.** { *; }
-keepclassmembers class com.proxyman.atlantis.** { *; }
