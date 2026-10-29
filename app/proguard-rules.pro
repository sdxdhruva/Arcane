# --- BouncyCastle lightweight API (Argon2id) ---
# Only org.bouncycastle.crypto.* is used, never the JCE provider, so no provider registration to keep.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.beans.**

# --- kotlinx.serialization (vault index) ---
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault, InnerClasses, Signature
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# --- Media3 pulls optional surfaces at compile time ---
-dontwarn androidx.media3.**

# No logcat in a release build: plaintext paths and sizes must not be recoverable from logs.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
