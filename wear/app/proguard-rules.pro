-keep class vn.edu.uit.tpkd.wear.cogload.** { *; }

# MediaPipe Tasks uses protobuf-lite fields reflectively. Keep the fields while
# allowing the rest of the generated/runtime implementation to be optimized.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# MediaPipe's native graph runtime resolves these members by their Java names.
# Without these rules an optimized APK can build successfully but fail during
# model/graph startup when JNI looks up fields or callbacks removed by R8.
-keep class com.google.mediapipe.framework.ProtoUtil$SerializedMessage { *; }
-keep class com.google.mediapipe.framework.MediaPipeException { *; }
-keep class com.google.mediapipe.framework.Packet { *; }
-keep interface com.google.mediapipe.framework.PacketListCallback { *; }
-keepclassmembers class * implements com.google.mediapipe.framework.PacketListCallback {
    public void process(java.util.List);
}
