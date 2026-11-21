# Minimal ProGuard / R8 rules for LightPDF
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Custom views instantiated from XML
-keep class com.nyoki.lightpdf.ui.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
