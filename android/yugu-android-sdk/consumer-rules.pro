# Keep the public API and models of the Yugu SDK so that reflection-free JSON mapping and
# listener interfaces survive R8 in host apps.
-keep class com.shengzhiai.yugu.** { public protected *; }
-keepclassmembers class com.shengzhiai.yugu.** { public protected *; }
