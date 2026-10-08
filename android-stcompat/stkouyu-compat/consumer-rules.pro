# Keep the Shengtong-compatible public API intact when the host app is minified:
# customer code and reflection-based JSON parsing reference these names directly.
-keep class com.stkouyu.** { *; }
-keep interface com.stkouyu.** { *; }
-keepclassmembers enum com.stkouyu.** { *; }
-dontwarn com.stkouyu.**
