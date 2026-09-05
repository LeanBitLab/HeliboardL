// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ocr

import android.content.Context
import android.net.Uri
import dalvik.system.DexClassLoader
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File

object OcrPluginLoader {
    private const val CURRENT_INTERFACE_VERSION = 1
    private const val PLUGIN_FILENAME = "ocr_plugin.apk"
    private const val PLUGIN_CLASS_NAME = "helium314.keyboard.ocr.plugin.TextRecognizerImpl"
    private const val PREF_HAS_PLUGIN = "pref_ocr_has_plugin"
    const val PREF_OCR_SCRIPT = "pref_ocr_script"
    const val PREF_OCR_KEEP_LINE_BREAKS = "pref_ocr_keep_line_breaks"
    const val PREF_OCR_TRIM_WHITESPACE = "pref_ocr_trim_whitespace"
    const val PREF_OCR_CASING = "pref_ocr_casing"
    const val PREF_OCR_LINE_JOIN_FORMAT = "pref_ocr_line_join_format"
    const val PREF_OCR_DEHYPHENATE = "pref_ocr_dehyphenate"
    const val PREF_OCR_NORMALIZE_PUNCTUATION = "pref_ocr_normalize_punctuation"
    const val PREF_OCR_STRIP_BULLETS = "pref_ocr_strip_bullets"
    const val PREF_OCR_REMOVE_NOISE = "pref_ocr_remove_noise"
    const val PREF_OCR_AUTO_COPY = "pref_ocr_auto_copy"
    const val PREF_OCR_AUTO_INSERT = "pref_ocr_auto_insert"
    const val PREF_OCR_SUGGEST_SCREENSHOT_TEXT = "pref_ocr_suggest_screenshot_text"
    const val PREF_OCR_PERSIST_FLASH = "pref_ocr_persist_flash"
    private const val TAG = "OcrPluginLoader"

    private var activeRecognizer: ITextRecognizer? = null
    private var cachedClassLoader: PluginClassLoader? = null
    private var cachedApkModified: Long = 0L

    @JvmStatic
    fun resetRecognizer() {
        activeRecognizer?.release()
        activeRecognizer = null
    }

    private fun invalidateClassLoader() {
        resetRecognizer()
        cachedClassLoader = null
        cachedApkModified = 0L
    }

    @JvmStatic
    fun getTargetAbi(): String {
        for (abi in android.os.Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a" -> return "arm64-v8a"
                "armeabi-v7a" -> return "armeabi-v7a"
                "x86_64" -> return "x86_64"
                "x86" -> return "x86"
            }
        }
        return "arm64-v8a"
    }

    @JvmStatic
    fun getPluginDownloadUrl(tag: String? = null): String {
        val abi = getTargetAbi()
        val filename = "ocr_plugin-$abi.apk"
        return if (tag == null || tag == "latest") {
            "https://github.com/LeanBitLab/LeanType-OCR-Plugin/releases/latest/download/$filename"
        } else {
            "https://github.com/LeanBitLab/LeanType-OCR-Plugin/releases/download/$tag/$filename"
        }
    }

    @JvmStatic
    fun downloadPluginApk(context: Context, tag: String? = null, tempFile: File): Boolean {
        val urlsToTry = listOf(
            getPluginDownloadUrl(tag),
            if (tag == null || tag == "latest") {
                "https://github.com/LeanBitLab/LeanType-OCR-Plugin/releases/latest/download/ocr_plugin.apk"
            } else {
                "https://github.com/LeanBitLab/LeanType-OCR-Plugin/releases/download/$tag/ocr_plugin.apk"
            }
        ).distinct()

        for (urlStr in urlsToTry) {
            try {
                val url = java.net.URL(urlStr)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "HeliboardL")
                conn.connect()

                var redirectConn = conn
                var status = redirectConn.responseCode
                var redirectCount = 0
                while ((status == java.net.HttpURLConnection.HTTP_MOVED_TEMP || status == java.net.HttpURLConnection.HTTP_MOVED_PERM || status == java.net.HttpURLConnection.HTTP_SEE_OTHER) && redirectCount < 5) {
                    val newUrl = redirectConn.getHeaderField("Location")
                    redirectConn.disconnect()
                    val nextUrl = java.net.URL(newUrl)
                    redirectConn = nextUrl.openConnection() as java.net.HttpURLConnection
                    redirectConn.setRequestProperty("User-Agent", "HeliboardL")
                    redirectConn.connect()
                    status = redirectConn.responseCode
                    redirectCount++
                }

                if (status == java.net.HttpURLConnection.HTTP_OK) {
                    redirectConn.inputStream.use { input ->
                        java.io.FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    redirectConn.disconnect()
                    return true
                }
                redirectConn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download from $urlStr", e)
            }
        }
        return false
    }

    private fun getNativeLibDir(context: Context, apkFile: File): File {
        val baseDir = File(context.filesDir, "plugin_libs")
        if (!baseDir.exists()) baseDir.mkdirs()
        val targetName = "ocr_${apkFile.lastModified()}"
        val targetDir = File(baseDir, targetName)
        baseDir.listFiles()?.forEach { f ->
            if (f.isDirectory && (f.name.startsWith("ocr_") || f.name == "ocr") && f.name != targetName) {
                try {
                    f.deleteRecursively()
                } catch (_: Exception) {}
            }
        }
        return targetDir
    }

    private fun extractNativeLibs(apkFile: File, outputDir: File) {
        if (!outputDir.exists()) outputDir.mkdirs()
        try {
            java.util.zip.ZipFile(apkFile).use { zip ->
                val abis = android.os.Build.SUPPORTED_ABIS
                var targetAbi: String? = null
                for (abi in abis) {
                    if (zip.entries().asSequence().any { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }) {
                        targetAbi = abi
                        break
                    }
                }
                if (targetAbi != null) {
                    val prefix = "lib/$targetAbi/"
                    for (entry in zip.entries().asSequence()) {
                        if (entry.name.startsWith(prefix) && entry.name.endsWith(".so")) {
                            val fileName = entry.name.substring(prefix.length)
                            val outFile = File(outputDir, fileName)
                            if (!outFile.exists() || outFile.length() != entry.size) {
                                zip.getInputStream(entry).use { input ->
                                    outFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                outFile.setReadable(true, false)
                                outFile.setExecutable(true, false)
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to extract native libraries", e)
        }
    }

    private fun ensureWorkManagerInitialized(context: Context) {
        try {
            androidx.work.WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            try {
                androidx.work.WorkManager.initialize(
                    context.applicationContext,
                    (context.applicationContext as? androidx.work.Configuration.Provider)?.workManagerConfiguration
                        ?: androidx.work.Configuration.Builder().build()
                )
            } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun getRecognizer(context: Context): ITextRecognizer? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return null
        if (activeRecognizer != null) return activeRecognizer
        if (!hasPlugin(context)) return null

        val apkFile = File(context.filesDir, PLUGIN_FILENAME)
        if (!apkFile.exists()) {
            context.prefs().edit().putBoolean(PREF_HAS_PLUGIN, false).apply()
            return null
        }
        apkFile.setReadOnly()

        return loadRecognizerInternal(context, apkFile)
    }

    private fun loadRecognizerInternal(context: Context, apkFile: File): ITextRecognizer? {
        return try {
            ensureWorkManagerInitialized(context)
            val apkLastModified = apkFile.lastModified()
            val nativeLibDir = getNativeLibDir(context, apkFile)
            extractNativeLibs(apkFile, nativeLibDir)

            val classLoader = if (cachedClassLoader != null && cachedApkModified == apkLastModified) {
                cachedClassLoader!!
            } else {
                PluginClassLoader(
                    apkFile.absolutePath,
                    context.codeCacheDir.absolutePath,
                    nativeLibDir.absolutePath,
                    context.classLoader
                ).also {
                    cachedClassLoader = it
                    cachedApkModified = apkLastModified
                }
            }

            val clazz = classLoader.loadClass(PLUGIN_CLASS_NAME)
            val recognizer = clazz.getDeclaredConstructor().newInstance() as ITextRecognizer

            if (recognizer.getInterfaceVersion() > CURRENT_INTERFACE_VERSION) {
                Log.w(TAG, "Plugin interface version is newer than supported")
                return null
            }

            val pluginContext = PluginContext(context.applicationContext, apkFile.absolutePath, classLoader)
            recognizer.init(pluginContext)

            if (recognizer.isAvailable()) {
                activeRecognizer = recognizer
                Log.i(TAG, "OCR recognizer loaded successfully (${recognizer.getScriptName()})")
                recognizer
            } else {
                Log.w(TAG, "OCR recognizer is not available after initialization")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load OCR plugin", e)
            null
        }
    }

    fun hasPlugin(context: Context): Boolean {
        val has = context.prefs().getBoolean(PREF_HAS_PLUGIN, false)
        if (!has) return false
        val apkFile = File(context.filesDir, PLUGIN_FILENAME)
        return apkFile.exists() && apkFile.length() > 0
    }

    fun getPluginVersion(context: Context): String? {
        val apkFile = File(context.filesDir, PLUGIN_FILENAME)
        if (!apkFile.exists()) return null
        return try {
            val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            info?.versionName
        } catch (_: Exception) {
            null
        }
    }

    fun getActiveScriptName(context: Context): String? {
        val recognizer = getRecognizer(context)
        return recognizer?.getDisplayName() ?: recognizer?.getScriptName()
    }

    fun importPlugin(context: Context, uri: Uri): Boolean {
        return try {
            try {
                context.codeCacheDir.deleteRecursively()
            } catch (_: Exception) {}

            val targetFile = File(context.filesDir, PLUGIN_FILENAME)
            if (targetFile.exists()) targetFile.delete()

            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return false

            targetFile.setReadOnly()
            invalidateClassLoader()

            val recognizer = loadRecognizerInternal(context, targetFile)
            val success = recognizer != null
            if (success) {
                context.prefs().edit().putBoolean(PREF_HAS_PLUGIN, true).apply()
                Log.i(TAG, "OCR plugin imported and verified successfully")
            } else {
                targetFile.delete()
                context.prefs().edit().putBoolean(PREF_HAS_PLUGIN, false).apply()
                Log.w(TAG, "OCR plugin verification failed")
            }
            success
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to import OCR plugin", e)
            false
        }
    }

    fun importPluginFromTempFile(context: Context, tempFile: File): Boolean {
        return try {
            try {
                context.codeCacheDir.deleteRecursively()
            } catch (_: Exception) {}

            val targetFile = File(context.filesDir, PLUGIN_FILENAME)
            if (targetFile.exists()) targetFile.delete()

            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
            targetFile.setReadOnly()

            invalidateClassLoader()

            val recognizer = loadRecognizerInternal(context, targetFile)
            val success = recognizer != null
            if (success) {
                context.prefs().edit().putBoolean(PREF_HAS_PLUGIN, true).apply()
                Log.i(TAG, "OCR plugin imported from temp file successfully")
            } else {
                targetFile.delete()
                context.prefs().edit().putBoolean(PREF_HAS_PLUGIN, false).apply()
                Log.w(TAG, "OCR plugin temp file verification failed")
            }
            success
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to import OCR plugin from temp file", e)
            false
        }
    }

    fun removePlugin(context: Context) {
        try {
            invalidateClassLoader()
            val apkFile = File(context.filesDir, PLUGIN_FILENAME)
            if (apkFile.exists()) apkFile.delete()
            val baseDir = File(context.filesDir, "plugin_libs")
            baseDir.listFiles()?.forEach { f ->
                if (f.isDirectory && (f.name.startsWith("ocr_") || f.name == "ocr")) {
                    try { f.deleteRecursively() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        context.prefs().edit().putBoolean(PREF_HAS_PLUGIN, false).apply()
    }

    @JvmStatic
    fun release() {
        resetRecognizer()
    }

    private class PluginContext(
        base: Context,
        private val apkPath: String,
        private val pluginClassLoader: ClassLoader
    ) : android.content.ContextWrapper(base), androidx.work.Configuration.Provider {
        private val pluginResources: android.content.res.Resources by lazy {
            try {
                val assetManager = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
                val addAssetPathMethod = android.content.res.AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
                addAssetPathMethod.invoke(assetManager, apkPath)
                android.content.res.Resources(assetManager, base.resources.displayMetrics, base.resources.configuration)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create plugin resources", e)
                base.resources
            }
        }

        override fun getResources(): android.content.res.Resources = pluginResources

        override fun getAssets(): android.content.res.AssetManager = pluginResources.assets

        override fun getClassLoader(): ClassLoader = pluginClassLoader

        override fun getApplicationContext(): Context = this

        override val workManagerConfiguration: androidx.work.Configuration
            get() = (baseContext.applicationContext as? androidx.work.Configuration.Provider)?.workManagerConfiguration
                ?: androidx.work.Configuration.Builder().build()
    }

    private class PluginClassLoader(
        dexPath: String,
        optimizedDirectory: String?,
        private val librarySearchPath: String?,
        parent: ClassLoader
    ) : DexClassLoader(dexPath, optimizedDirectory, librarySearchPath, parent) {
        override fun findLibrary(name: String): String? {
            if (librarySearchPath != null) {
                val filename = System.mapLibraryName(name)
                val file = java.io.File(librarySearchPath, filename)
                if (file.exists()) {
                    return file.absolutePath
                }
            }
            return super.findLibrary(name)
        }

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("helium314.keyboard.ocr.plugin.") ||
                name.startsWith("com.google.mlkit.") ||
                name.startsWith("com.google.android.datatransport.") ||
                name.startsWith("com.google.android.gms.") ||
                name.startsWith("com.google.firebase.")
            ) {
                val loaded = findLoadedClass(name)
                if (loaded != null) return loaded
                try {
                    return findClass(name)
                } catch (_: ClassNotFoundException) {
                    // fallback to parent
                }
            }
            return super.loadClass(name, resolve)
        }
    }
}
