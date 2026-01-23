# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# Gemini SDK dependencies
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Keep Gemini API classes
-keep class com.google.ai.client.generativeai.** { *; }

-keep class helium314.keyboard.latin.utils.ProofreadHelper { *; }
-keep class helium314.keyboard.latin.utils.ProofreadHelper$* { *; }

# Keep java-llama.cpp classes
-keep class de.kherud.llama.** { *; }

# ONNX Runtime configurations
-dontwarn com.google.protobuf.**
-keep class ai.onnxruntime.** { *; }

# Fix correct service name
-keep class helium314.keyboard.latin.utils.ProofreadService { *; }
