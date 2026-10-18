# LiteRT-LM talks to its native engine over JNI and builds JSON with Gson reflection.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
