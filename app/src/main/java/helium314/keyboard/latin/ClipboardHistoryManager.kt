// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isGone
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.ToolbarKey
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.prefs

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    // Cache the main-thread Handler. ClipboardHistoryManager is a
    // singleton scoped to the IME service, so a single Handler bound
    // to the main Looper is fine for the whole process. This avoids
    // allocating a fresh Handler on every postDelayed().
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clipboardSuggestionView: View? = null
    private var _clipboardDao: ClipboardDao? = null
    private var clipboardDao: ClipboardDao?
        get() {
            if (_clipboardDao == null || _clipboardDao?.isClosed == true) {
                _clipboardDao = ClipboardDao.getInstance(latinIME)
            }
            return _clipboardDao
        }
        set(value) {
            _clipboardDao = value
        }
    private var dontShowCurrentSuggestion: Boolean = false
    // ponytail: track last clip state to avoid resetting dismiss state on duplicate events
    private var lastPrimaryClipText: String? = null
    private var lastPrimaryClipUri: String? = null
    private var lastPrimaryClipTimestamp: Long = 0L

    private data class ScreenshotInfo(
        val uri: Uri,
        val fileName: String,
        val fullPath: String,
        val dateAdded: Long
    )

    @Volatile
    private var cachedScreenshotInfo: ScreenshotInfo? = null

    private var screenshotObserver: ContentObserver? = null

    private fun registerScreenshotObserver() {
        if (screenshotObserver != null) return
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (latinIME.checkCallingOrSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            screenshotObserver = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    if (latinIME.mSettings.current.mSuggestScreenshots) {
                        updateLatestScreenshotCache {
                            // Race guard (field NPE 22 Aug 2026): a screenshot can land before the
                            // keyboard view exists — getSuggestionStripView() must not run on a
                            // half-built IME. Skip; the next input show will surface the suggestion.
                            if (!latinIME.isInputViewShown) return@updateLatestScreenshotCache
                            latinIME.tryShowClipboardSuggestion()
                        }
                    }
                }
            }
            latinIME.contentResolver.registerContentObserver(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                screenshotObserver!!
            )
        } catch (e: Exception) {
            // Ignore observer registration failures
        }
    }

    private fun unregisterScreenshotObserver() {
        try {
            screenshotObserver?.let {
                latinIME.contentResolver.unregisterContentObserver(it)
                screenshotObserver = null
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun updateLatestScreenshotCache(onComplete: (() -> Unit)? = null) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (latinIME.checkCallingOrSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            cachedScreenshotInfo = null
            onComplete?.invoke()
            return
        }

        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
            val projection = mutableListOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.DATE_ADDED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection.add(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                projection.add(android.provider.MediaStore.Images.Media.DATA)
            }
            
            val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
            
            try {
                val cursor = latinIME.contentResolver.query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection.toTypedArray(),
                    null,
                    null,
                    sortOrder
                )
                
                cursor?.use {
                    var count = 0
                    while (it.moveToNext() && count < 10) {
                        count++
                        val dateIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED)
                        val dateAdded = it.getLong(dateIndex) * 1000L
                        val diff = System.currentTimeMillis() - dateAdded
                        
                        if (diff < RECENT_SCREENSHOT_TIME_MILLIS) {
                            val nameIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                            val fileName = it.getString(nameIndex) ?: ""
                            
                            var fullPath = "Unknown"
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val relIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
                                fullPath = it.getString(relIndex) ?: ""
                            } else {
                                val dataIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATA)
                                @Suppress("DEPRECATION")
                                fullPath = it.getString(dataIndex) ?: ""
                            }

                            val isScreenshot = fileName.contains("Screenshot", ignoreCase = true)
                                || fileName.contains("Screen", ignoreCase = true)
                                || fullPath.contains("Screenshot", ignoreCase = true)
                                || fullPath.contains("Screenshots", ignoreCase = true)
                                || fullPath.contains("Pictures", ignoreCase = true)
                                || fullPath.contains("DCIM", ignoreCase = true)
                            if (isScreenshot) {
                                val idIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                                val id = it.getLong(idIndex)
                                val contentUri = android.content.ContentUris.withAppendedId(
                                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                                )
                                val prefs = latinIME.prefs()
                                val rawDeletedSet = prefs.getStringSet("deleted_screenshot_uris", emptySet()) ?: emptySet()
                                val deletedSet = rawDeletedSet.filter { it.startsWith("content://") || it.contains("clipboard_images") }.toSet()
                                if (deletedSet.contains(contentUri.toString())) {
                                    return@execute
                                }

                                if (cachedScreenshotInfo?.uri != contentUri) {
                                    dontShowCurrentSuggestion = false
                                }
                                cachedScreenshotInfo = ScreenshotInfo(contentUri, fileName, fullPath, dateAdded)
                                
                                if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
                                    val cachedPath = cacheImage(contentUri)
                                    if (cachedPath != null && !deletedSet.contains(cachedPath) && !deletedSet.contains(contentUri.toString())) {
                                        clipboardDao?.addClip(System.currentTimeMillis(), false, "[Screenshot]", cachedPath)
                                    }
                                }

                                mainHandler.post {
                                    onComplete?.invoke()
                                    latinIME.tryShowClipboardSuggestion()
                                }
                                return@execute
                            }
                        } else {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                helium314.keyboard.latin.utils.Log.e("ClipboardHistoryManager", "Failed to query screenshots in background", e)
            }
            cachedScreenshotInfo = null
            mainHandler.post {
                onComplete?.invoke()
            }
        }
    }

    fun stopListening() {
        try {
            if (::clipboardManager.isInitialized) {
                clipboardManager.removePrimaryClipChangedListener(this)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (latinIME.prefs().getBoolean(helium314.keyboard.latin.settings.Settings.PREF_ENABLE_CLIPBOARD_LISTENER, helium314.keyboard.latin.settings.Defaults.PREF_ENABLE_CLIPBOARD_LISTENER)) {
            clipboardManager.addPrimaryClipChangedListener(this)
        }
        clipboardDao = ClipboardDao.getInstance(latinIME)
        // ponytail: initialize last clip state
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                lastPrimaryClipText = clipData.getItemAt(0)?.coerceToText(latinIME)?.toString()
                lastPrimaryClipUri = clipData.getItemAt(0)?.uri?.toString()
                lastPrimaryClipTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clipData.description.timestamp else 0L
            }
        } catch (e: Exception) {
            // Ignore
        }
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { fetchPrimaryClip() }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { cleanUpImageCache() }
        if (latinIME.mSettings.current.mSuggestScreenshots) {
            registerScreenshotObserver()
            updateLatestScreenshotCache()
        }
    }

    fun onStartInputView() {
        val prefs = latinIME.prefs()
        val lastDismissed = prefs.getString("last_dismissed_screenshot_uri", "")
        if (cachedScreenshotInfo != null && cachedScreenshotInfo?.uri?.toString() != lastDismissed) {
            dontShowCurrentSuggestion = false
        }
        if (latinIME.mSettings.current.mSuggestScreenshots) {
            updateLatestScreenshotCache()
        }
    }

    fun onFinishInputView() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun cleanUpImageCache() {
        try {
            val cacheDir = java.io.File(latinIME.cacheDir, "clipboard_images")
            if (!cacheDir.exists()) return
            
            val validUris = clipboardDao?.getClips()?.mapNotNull { it.imageUri }?.toSet() ?: emptySet()
            
            cacheDir.listFiles()?.forEach { file ->
                if (!validUris.contains(file.absolutePath)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            helium314.keyboard.latin.utils.Log.e("ClipboardHistoryManager", "Failed to clean up image cache: " + e.message)
        }
    }

    fun onDestroy() {
        unregisterScreenshotObserver()
        clipboardManager.removePrimaryClipChangedListener(this)
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onPrimaryClipChanged() {
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            // ponytail: ignore duplicate events where clipboard contents didn't actually change
            val clipData = try {
                clipboardManager.primaryClip
            } catch (e: Exception) {
                null
            }
            val currentText = if (clipData != null && clipData.itemCount > 0) {
                clipData.getItemAt(0)?.coerceToText(latinIME)?.toString()
            } else {
                null
            }
            val currentUri = if (clipData != null && clipData.itemCount > 0) {
                clipData.getItemAt(0)?.uri?.toString()
            } else {
                null
            }
            val currentTimestamp = if (clipData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clipData.description.timestamp
            } else {
                0L
            }

            val hasChanged = currentText != lastPrimaryClipText
                    || currentUri != lastPrimaryClipUri
                    || (currentTimestamp != 0L && currentTimestamp != lastPrimaryClipTimestamp)

            if (hasChanged) {
                lastPrimaryClipText = currentText
                lastPrimaryClipUri = currentUri
                lastPrimaryClipTimestamp = currentTimestamp

                ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { fetchPrimaryClip() }
                dontShowCurrentSuggestion = false
                val prefs = latinIME.prefs()
                prefs.edit().remove("last_dismissed_clipboard_text").apply()
            }
        }
    }

    private fun fetchPrimaryClip() {
        val clipData = try {
            clipboardManager.primaryClip
        } catch (e: Exception) {
            null
        }
        
        if (clipData == null || clipData.itemCount == 0) return
        
        var hasText = clipData.description?.hasMimeType("text/*") == true || clipData.description?.hasMimeType("text/plain") == true || clipData.description?.hasMimeType("text/html") == true
        var hasImage = clipData.description?.hasMimeType("image/*") == true
        
        if (!hasImage && clipData.itemCount > 0) {
            val uri = clipData.getItemAt(0)?.uri
            if (uri != null) {
                val type = latinIME.contentResolver.getType(uri)
                if (type?.startsWith("image/") == true) {
                    hasImage = true
                }
            }
        }
        
        if (!hasText && !hasImage) return
        
        clipData.getItemAt(0)?.let { clipItem ->
            val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
            var content = ""
            var imageUri: String? = null
            
            if (hasImage && clipItem.uri != null) {
                // Determine mime type and copy to local cache
                imageUri = cacheImage(clipItem.uri)
                if (imageUri != null) {
                    content = "[Image]"
                }
            }
            if (hasText && imageUri == null) {
                content = clipItem.coerceToText(latinIME)?.toString() ?: ""
            }
            
            if (TextUtils.isEmpty(content) && imageUri == null) return
            clipboardDao?.addClip(timeStamp, false, content, imageUri)
        }
    }

    private fun cacheImage(uri: android.net.Uri): String? {
        try {
            val resolver = latinIME.contentResolver
            val cacheDir = java.io.File(latinIME.cacheDir, "clipboard_images")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            
            val md = java.security.MessageDigest.getInstance("MD5")
            val digest = md.digest(uri.toString().toByteArray())
            val hash = digest.joinToString("") { "%02x".format(it) }
            val suffix = if (latinIME.mSettings.current.mCompressScreenshots) "_compressed" else ""
            val file = java.io.File(cacheDir, "img_${hash}${suffix}.jpg")
            if (file.exists() && file.length() > 0) {
                return file.absolutePath
            }
            
            if (!latinIME.mSettings.current.mCompressScreenshots) {
                resolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                    return file.absolutePath
                }
                return null
            }
            
            resolver.openInputStream(uri)?.use { input ->
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeStream(input, null, options)
                
                resolver.openInputStream(uri)?.use { actualInput ->
                    val reqWidth = 1024
                    val reqHeight = 1024
                    var inSampleSize = 1
                    if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                        val halfHeight = options.outHeight / 2
                        val halfWidth = options.outWidth / 2
                        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                            inSampleSize *= 2
                        }
                    }
                    
                    val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                    }
                    val bitmap = android.graphics.BitmapFactory.decodeStream(actualInput, null, decodeOptions)
                    if (bitmap != null) {
                        file.outputStream().use { output ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output)
                        }
                        bitmap.recycle()
                        return file.absolutePath
                    }
                }
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    fun toggleClipPinned(id: Long) {
        clipboardDao?.togglePinned(id)
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = clipboardDao?.isPinned(index) == false

    fun removeEntry(index: Int): ClipboardHistoryEntry? {
        if (!canRemove(index)) return null
        val entry = clipboardDao?.deleteClipAt(index)
        if (entry != null) {
            if (entry.imageUri != null) {
                val prefs = latinIME.prefs()
                val deletedSet = prefs.getStringSet("deleted_screenshot_uris", emptySet())?.toMutableSet() ?: mutableSetOf()
                deletedSet.add(entry.imageUri)
                prefs.edit().putStringSet("deleted_screenshot_uris", deletedSet).apply()
                if (cachedScreenshotInfo?.fullPath == entry.imageUri || cachedScreenshotInfo?.uri?.toString() == entry.imageUri) {
                    cachedScreenshotInfo = null
                }
            }
            try {
                val primaryText = retrieveClipboardContent().toString()
                if (primaryText == entry.text || (entry.text == "[Screenshot]" && entry.imageUri != null)) {
                    ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return entry
    }

    fun restoreEntry(entry: ClipboardHistoryEntry) {
        clipboardDao?.restoreClip(entry)
    }

    fun updateClipText(id: Long, newText: String) {
        clipboardDao?.updateClipText(id, newText)
    }

    fun sortHistoryEntries() {
        clipboardDao?.sort()
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() = clipboardDao?.clearOldClips(true)

    fun getHistorySize() = clipboardDao?.count() ?: 0

    fun getClips() = clipboardDao?.getClips() ?: emptyList()

    fun getHistoryEntry(position: Int) = clipboardDao?.getAt(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        clipboardDao?.listener = listener
    }

    fun retrieveClipboardContent(): CharSequence {
        val clipData = clipboardManager.primaryClip ?: return ""
        if (clipData.itemCount == 0) return ""
        return clipData.getItemAt(0)?.coerceToText(latinIME) ?: ""
    }

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        clipboardSuggestionView = null

        // check for screenshot first if enabled
        if (latinIME.mSettings.current.mSuggestScreenshots) {
            val screenshotView = getScreenshotSuggestionView(parent)
            if (screenshotView != null) {
                clipboardSuggestionView = screenshotView
                return screenshotView
            }
        }

        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0 || clipData.description?.hasMimeType("text/*") == false) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null
        val content = clipItem.coerceToText(latinIME)
        if (TextUtils.isEmpty(content)) return null
        val prefs = latinIME.prefs()
        val lastDismissedText = prefs.getString("last_dismissed_clipboard_text", "")
        if (content.toString() == lastDismissedText) return null
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        latinIME.mSettings.getCustomTypeface()?.let { textView.typeface = it }
        textView.text = (if (isClipSensitive(inputType)) "*".repeat(content.length) else content)
            .take(200) // truncate displayed text for performance reasons
        val clipIcon = activeKeyboardIcons()?.getIconDrawable(ToolbarKey.PASTE.name.lowercase())
        clipIcon?.let { textView.setCompoundDrawablesRelativeWithIntrinsicBounds(it, null, null, null) }
        textView.setOnClickListener {
            dontShowCurrentSuggestion = true
            latinIME.onTextInput(content.toString())
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }
        val closeButton = binding.clipboardSuggestionClose
        activeKeyboardIcons()?.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase())?.let { closeIcon ->
            closeButton.setImageDrawable(closeIcon)
        }
        closeButton.setOnClickListener {
            val prefs = latinIME.prefs()
            prefs.edit().putString("last_dismissed_clipboard_text", content.toString()).apply()
            removeClipboardSuggestion()
        }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        clipIcon?.let { colors.setColor(it, ColorType.KEY_ICON) }
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private var lastSuggestedScreenshotUri: String? = null

    /** Safe access to the active keyboard's icon set. The keyboard view is built lazily and torn
     *  down with input views, so a spontaneous clipboard/screenshot callback can run while it does
     *  not exist (field NPE 22 Aug 2026: Keyboard.mIconsSet read on null reference). Returns null
     *  instead of crashing; callers degrade to an icon-less suggestion. */
    private fun activeKeyboardIcons(): helium314.keyboard.keyboard.internal.KeyboardIconsSet? {
        return try {
            val kb = latinIME.mKeyboardSwitcher.keyboard ?: return null
            kb.mIconsSet
        } catch (e: Exception) {
            null
        }
    }

    private fun getScreenshotSuggestionView(parent: ViewGroup?): View? {
        if (parent == null || dontShowCurrentSuggestion) return null
        
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (latinIME.checkCallingOrSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val screenshotInfo = cachedScreenshotInfo
        if (screenshotInfo == null) {
            updateLatestScreenshotCache()
            return null
        }

        val prefs = latinIME.prefs()
        val lastDismissedScreenshotUri = prefs.getString("last_dismissed_screenshot_uri", "")
        if (screenshotInfo.uri.toString() == lastDismissedScreenshotUri) return null

        val diff = System.currentTimeMillis() - screenshotInfo.dateAdded
        if (diff >= RECENT_SCREENSHOT_TIME_MILLIS) {
            cachedScreenshotInfo = null
            return null
        }

        val contentUri = screenshotInfo.uri
        val isAlreadySuggested = contentUri.toString() == lastSuggestedScreenshotUri

        if (!isAlreadySuggested) {
            if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
                ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {
                    val cachedPath = cacheImage(contentUri)
                    if (cachedPath != null) {
                        mainHandler.post {
                            clipboardDao?.addClip(System.currentTimeMillis(), false, "[Screenshot]", cachedPath)
                        }
                    }
                }
            }
            lastSuggestedScreenshotUri = contentUri.toString()
        }

        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        textView.text = "Screenshot"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val thumb = latinIME.contentResolver.loadThumbnail(contentUri, android.util.Size(120, 120), null)
                
                val size = Math.min(thumb.width, thumb.height)
                val x = (thumb.width - size) / 2
                val y = (thumb.height - size) / 2
                val croppedThumb = android.graphics.Bitmap.createBitmap(thumb, x, y, size, size)
                
                val drawable = android.graphics.drawable.BitmapDrawable(latinIME.resources, croppedThumb)
                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
            } catch (e: Exception) {
                activeKeyboardIcons()?.getIconDrawable(ToolbarKey.PASTE.name.lowercase())?.let { clipIcon ->
                    textView.setCompoundDrawablesRelativeWithIntrinsicBounds(clipIcon, null, null, null)
                }
            }
        }

        textView.setOnClickListener {
            dontShowCurrentSuggestion = true
            lastSuggestedScreenshotUri = contentUri.toString()
            latinIME.onImageSelected(contentUri.toString())
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }
        
        val closeButton = binding.clipboardSuggestionClose
        activeKeyboardIcons()?.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase())?.let { closeIcon ->
            closeButton.setImageDrawable(closeIcon)
        }
        closeButton.setOnClickListener { 
            val prefs = latinIME.prefs()
            val rawDeletedSet = prefs.getStringSet("deleted_screenshot_uris", emptySet()) ?: emptySet()
            val deletedSet = rawDeletedSet.filter { it.startsWith("content://") || it.contains("clipboard_images") }.toMutableSet()
            deletedSet.add(contentUri.toString())
            prefs.edit().putStringSet("deleted_screenshot_uris", deletedSet)
                .putString("last_dismissed_screenshot_uri", contentUri.toString()).apply()
            cachedScreenshotInfo = null
            dontShowCurrentSuggestion = true
            lastSuggestedScreenshotUri = contentUri.toString()
            removeClipboardSuggestion() 
        }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        
        return binding.root
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    fun pasteLargeText(text: String) {
        val primaryClip = try { clipboardManager.primaryClip } catch (e: Exception) { null }
        
        // Remove listener temporarily to avoid re-triggering history capture
        clipboardManager.removePrimaryClipChangedListener(this)
        
        try {
            clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("Clipboard", text))
            latinIME.mInputLogic.connection.performContextMenuAction(android.R.id.paste)
        } catch (e: Exception) {
            // Fallback to standard commit if context paste fails
            latinIME.onTextInput(text)
        }
        
        // Restore original clip after a tiny delay to allow paste process to complete
        mainHandler.postDelayed({
            try {
                if (primaryClip != null) {
                    clipboardManager.setPrimaryClip(primaryClip)
                } else {
                    ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
                }
            } catch (e: Exception) {
                // Ignore restore failures
            } finally {
                clipboardManager.addPrimaryClipChangedListener(this)
            }
        }, 200)
    }

    companion object {
        const val RECENT_TIME_MILLIS = 1 * 60 * 1000L // 1 minute (for clipboard suggestions)
        const val RECENT_SCREENSHOT_TIME_MILLIS = 5 * 60 * 1000L // 5 minutes
    }
}
