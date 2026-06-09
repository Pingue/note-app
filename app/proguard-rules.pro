# kotlinx.serialization keeps generated serializers via @Serializable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.pennotes.model.** {
    *** Companion;
}
-keepclasseswithmembers class app.pennotes.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
