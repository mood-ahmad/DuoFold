# DuoFold R8 keep rules

# 反射访问系统隐藏 API 与 UserService 组件
-keep class dev.duofold.bridge.** { *; }
-keep class dev.duofold.FoldService { *; }
-keep class dev.duofold.StopReceiver { *; }

# Shizuku
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Compose / kotlinx
-dontwarn org.jetbrains.annotations.**
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class * { @androidx.annotation.Keep *; }

# Keep AIDL 生成的 Bind 类
-keep class * extends android.os.Binder { *; }

# Keep BuildConfig (BuildConfig.DEBUG 用于调试帧导出)
-keep class dev.duofold.BuildConfig { *; }