-keepattributes Signature,InnerClasses,EnclosingMethod
# JLaTeXMath 0.2.0 loads named XML/font resources and reflects macro methods.
# Its published AAR has no consumer rules; preserve the reflected engine.
-keep class org.scilab.forge.jlatexmath.** { *; }
