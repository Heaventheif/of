# commons-compress يحمّل بعض الضاغطات بالانعكاس؛ نُبقي ما نستخدمه فقط.
-keep class org.apache.commons.compress.compressors.gzip.** { *; }
-keep class org.apache.commons.compress.compressors.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.crypto.**

# قارئ DOCX: واجهة JavaScript يجب أن تبقى أسماء دوالها (يستدعيها نص الصفحة المضمّن بالاسم).
-keepclassmembers class org.openfilemanager.app.reader.DocxReaderState$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
