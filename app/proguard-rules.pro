# JNI exported symbols in NativeEngine.cpp use the original Kotlin class/method names.
-keep class tw.idv.christorng.liveperformer.NativeEngine { *; }

# Android framework components referenced from the manifest are kept by AGP.
# Do not strip Kotlin serialization metadata used by the app's JSON document model.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
