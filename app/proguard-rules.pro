# MyFriend release 混淆规则。
#
# 原则：只保留「运行期真的会被反射/按名字找」的东西，其余交给 R8 去瘦身。
# 这个 App 没有管理员式的内部开关，所以混淆出错的表现往往不是崩溃日志里的一行栈，
# 而是「某个功能悄悄不工作了」——那种问题用户说不清、我们也难查，因此宁可多留一点。

# ---------- kotlinx.serialization ----------
# @Serializable 的 serializer() 和 $$serializer 都是按名字/伴生对象反射拿的，必须保留。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.myfriend.app.**$$serializer { *; }
-keepclassmembers class com.myfriend.app.** { *** Companion; }
-keepclasseswithmembers class com.myfriend.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers @kotlinx.serialization.Serializable class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- Room ----------
# 实体/DAO 由 KSP 生成实现，但 Room 会通过反射找 RoomDatabase 子类与实体构造器；
# 生成的 *_Impl 类名也不能被改，否则打开数据库时直接「找不到实现」。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * implements androidx.room.migration.Migration { *; }
-keepnames class androidx.room.**
-keepnames class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---------- Hilt / Dagger ----------
# 生成组件、@Module/@Provides、@AndroidEntryPoint 注入点都按名字装配，保留起来最省事。
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.Module class * { *; }

# ---------- WorkManager ----------
# Worker 是按类名反射实例化的；被 R8 改名或删掉构造器，后台的「他还在」就直接停摆。
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger
-keep class androidx.work.impl.WorkManagerImpl { *; }

# ---------- OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# ---------- Compose ----------
# Compose 运行时不需要额外 keep 规则；这里只关掉它自带的、对我们无意义的提示。
-dontwarn androidx.compose.**

# ---------- 保留行号，崩溃栈才读得懂 ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
