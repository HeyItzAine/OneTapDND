# Add project specific ProGuard rules here.
# Room creates generated database implementations through reflection at startup.
# Keep the no-argument constructor explicitly for R8 full mode.
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}

# WorkManager creates this worker by its persisted class name and constructor.
-keep,allowoptimization class com.example.onetapdnd.PlaceRegistrationWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
