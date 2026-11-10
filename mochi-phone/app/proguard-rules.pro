# Release shrinking rules (R8). Activities, services, receivers, the widget and the tile are kept through the
# manifest; the app uses no reflection. OkHttp ships its own rules; these only quiet its optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# readable crash stacks in the logs
-keepattributes SourceFile,LineNumberTable
