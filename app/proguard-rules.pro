# Expense Tracker ProGuard Rules for Production Minification

# Preserve line numbers and source files for stack trace debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager Workers (instantiated dynamically via reflection by FQN)
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Glance AppWidget ActionCallbacks (instantiated dynamically via reflection)
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}

# Room Database & Entities
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
