# RR Device Manager ProGuard Rules

# Keep Device Admin Receiver
-keep class com.riad.rrlkr.admin.** { *; }

# Keep all services and receivers
-keep class com.riad.rrlkr.service.** { *; }
-keep class com.riad.rrlkr.receiver.** { *; }
-keep class com.riad.rrlkr.ui.** { *; }

# Keep Firebase Messaging
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep Retrofit and OkHttp
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Gson models
-keep class com.google.gson.** { *; }
-keep class com.riad.rrlkr.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep WorkManager
-keep class androidx.work.** { *; }

# Prevent stripping of accessibility service
-keep class com.riad.rrlkr.service.PowerButtonInterceptService { *; }

# Keep device policy manager related classes
-keep class android.app.admin.** { *; }
