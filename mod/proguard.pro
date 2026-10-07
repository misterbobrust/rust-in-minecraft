# Obfuscation only: nothing removed or optimised, so behaviour stays exactly the same.
-dontshrink
-dontoptimize
-dontusemixedcaseclassnames
-ignorewarnings
-dontwarn **
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Record,PermittedSubclasses,NestHost,NestMembers,Exceptions

# Mixins are looked up by name from the mixin configs and woven into Minecraft's classes
-keep class local.rustak.mixin.** { *; }
-keep class local.rustak.client.mixin.** { *; }

# Fabric entrypoints (fabric.mod.json)
-keep class local.rustak.RustAk
-keep class local.rustak.client.RustAkClient

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
