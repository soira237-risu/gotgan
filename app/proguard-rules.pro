# R8 rules for the release build. AndroidX (Room, Media3, Compose, WorkManager, DataStore), OkHttp and Coil
# ship their own consumer rules; the entries below cover what the libraries leave to the app.

# Stack traces in DiagnosticLog and crash reports stay readable after obfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Binder interfaces are used across processes (PdfService runs in its own isolated process).
-keep class com.quickfiles.app.viewer.IPdfService$* { *; }
-keep class com.quickfiles.app.viewer.IPdfService { *; }

# NextLib FFmpeg decoders: native code calls back into these classes by name.
-keep class io.github.anilbeesetti.nextlib.** { *; }

# zstd-jni reads and writes Java fields from native code by name.
-keep class com.github.luben.zstd.** { *; }

# JSch looks up ciphers, KEX, MACs and key types with Class.forName from its config table.
-keep class com.jcraft.jsch.** { *; }

# smbj: the event bus dispatches to annotated handler methods by reflection, and the crypto
# provider resolves BouncyCastle SPIs by class name.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }

# Optional integrations these libraries probe for at runtime but the app does not bundle.
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.log4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.el.**
-dontwarn javax.annotation.**
