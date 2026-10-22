# Used only when building with -Pminify=true.
# Libraries that load algorithms or classes reflectively:
-keep class com.jcraft.jsch.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.apache.commons.net.** { *; }
-keep class net.engio.mbassy.** { *; }
# Optional / desktop-only classes referenced by those libraries:
-dontwarn javax.el.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.**
-dontwarn org.apache.log4j.**
-dontwarn com.sun.jna.**
-dontwarn net.i2p.crypto.**
-dontwarn org.bouncycastle.**
-dontwarn java.lang.management.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.jcraft.jsch.jgss.**
-dontwarn com.jcraft.jsch.jce.**
-dontwarn com.jcraft.jsch.PageantConnector
-dontwarn com.jcraft.jsch.JUnixSocketFactory
