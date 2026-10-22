# Expense Tracker ProGuard Rules for Production Minification (v1.2.0)

# Preserve line numbers and source files for stack trace debugging
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# Keep all Kotlin enums (used with Enum.valueOf in Room Converters, SmsPatternLearner, NecessityManager, CsvEngine)
-keepclassmembers enum com.expensetracker.offline.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep enum com.expensetracker.offline.** { *; }

# Room Database, Entities, Relation POJOs & DAO Query Projection classes
-keep class * extends androidx.room.RoomDatabase
-keep class com.expensetracker.offline.data.local.** { *; }
-dontwarn androidx.room.paging.**

# WorkManager Workers (instantiated dynamically via reflection by FQN)
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Glance AppWidget Receivers & ActionCallbacks (instantiated dynamically via reflection)
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
-keep class com.expensetracker.offline.widget.** { *; }

# ML Kit On-Device Text Recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
