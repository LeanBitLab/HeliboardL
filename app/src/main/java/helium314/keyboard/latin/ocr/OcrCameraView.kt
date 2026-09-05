// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ocr

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsDestination

class OcrCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OcrViewListener {
        fun onOcrTextExtracted(lines: List<String>)
        fun onCloseOcr()
    }

    private var previewView: PreviewView? = null
    private var touchShield: View? = null
    private var pluginPanel: LinearLayout? = null
    private var controlsBar: LinearLayout? = null
    private var shutterBtn: ImageButton? = null
    private var shutterProgress: ProgressBar? = null
    private var flashBtn: ImageButton? = null
    private var closeBtn: ImageButton? = null
    private var statusIndicator: TextView? = null

    private var cameraManager: OcrCameraManager? = null
    private var pipeline: OcrPipeline? = null
    private var listener: OcrViewListener? = null
    private var isCameraStarted = false
    private var isLoadingAnimationActive = false
    private var loadingAnimator: ValueAnimator? = null
    private val loadingBorderDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setStroke(4, Color.TRANSPARENT)
        setColor(Color.TRANSPARENT)
    }

    companion object {
        private const val TAG = "OcrCameraView"
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != VISIBLE) {
            return super.dispatchTouchEvent(ev)
        }
        // Let buttons and children handle their own clicks
        super.dispatchTouchEvent(ev)
        // Always return true so KeyboardWrapperView never falls through to MainKeyboardView
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            previewView?.let { pv ->
                cameraManager?.focus(pv, event.x, event.y)
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onFinishInflate() {
        super.onFinishInflate()
        previewView = findViewById(R.id.ocr_preview_view)
        touchShield = findViewById(R.id.ocr_touch_shield)
        pluginPanel = findViewById(R.id.ocr_plugin_required_panel)
        controlsBar = findViewById(R.id.ocr_controls_bar)
        shutterBtn = findViewById(R.id.btn_ocr_shutter)
        shutterProgress = findViewById(R.id.ocr_shutter_progress)
        flashBtn = findViewById(R.id.btn_ocr_flash)
        closeBtn = findViewById(R.id.btn_ocr_close)
        statusIndicator = findViewById(R.id.ocr_status_indicator)

        previewView?.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView?.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        isClickable = true
        isFocusable = true
        isSoundEffectsEnabled = false

        findViewById<Button>(R.id.ocr_btn_load_plugin)?.setOnClickListener {
            openOcrSettings()
        }

        setupButtons()
    }

    fun setListener(listener: OcrViewListener) {
        this.listener = listener
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        touchShield?.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    previewView?.let { pv ->
                        cameraManager?.focus(pv, event.x, event.y)
                    }
                    true
                }
                else -> true
            }
        }
        touchShield?.setOnClickListener { }

        shutterBtn?.setOnClickListener {
            captureFromCamera()
        }

        flashBtn?.setOnClickListener {
            val isNowOn = cameraManager?.toggleTorch() ?: false
            flashBtn?.setImageResource(if (isNowOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        }

        closeBtn?.setOnClickListener {
            stopCamera()
            listener?.onCloseOcr()
        }
    }

    fun startCamera() {
        if (!OcrPluginLoader.hasPlugin(context)) {
            pluginPanel?.visibility = View.VISIBLE
            controlsBar?.visibility = View.GONE
            previewView?.visibility = View.GONE
            return
        }

        pluginPanel?.visibility = View.GONE
        controlsBar?.visibility = View.VISIBLE
        previewView?.visibility = View.VISIBLE

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, R.string.ocr_camera_permission_required, Toast.LENGTH_LONG).show()
            openOcrSettings()
            listener?.onCloseOcr()
            return
        }

        if (cameraManager == null) {
            cameraManager = OcrCameraManager(context)
        }
        if (pipeline == null) {
            pipeline = OcrPipeline(context)
        }

        previewView?.let { pv ->
            cameraManager?.startCamera(
                previewView = pv,
                onReady = {
                    isCameraStarted = true
                },
                onError = { e ->
                    Log.e(TAG, "Failed to start camera viewfinder", e)
                }
            )
        }
    }

    fun stopCamera() {
        showLoading(false)
        cameraManager?.stopCamera()
        isCameraStarted = false
        flashBtn?.setImageResource(R.drawable.ic_flash_off)
    }

    fun release() {
        stopCamera()
        cameraManager?.release()
        cameraManager = null
        pipeline = null
    }

    private fun captureFromCamera() {
        if (!isCameraStarted || isLoadingAnimationActive) return
        showLoading(true)

        cameraManager?.capturePhoto(
            onCaptured = { bitmap ->
                pipeline?.processImage(
                    bitmap = bitmap,
                    onSuccess = { lines ->
                        showLoading(false)
                        if (lines.isEmpty()) {
                            showNoTextDetected()
                        } else {
                            listener?.onOcrTextExtracted(lines)
                        }
                    },
                    onError = { errorMsg ->
                        showLoading(false)
                        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                    }
                )
            },
            onError = { e ->
                showLoading(false)
                Toast.makeText(context, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showLoading(show: Boolean) {
        if (show) {
            if (isLoadingAnimationActive) return
            isLoadingAnimationActive = true
            shutterBtn?.isEnabled = false

            val accentColor = Settings.getValues().mColors.get(ColorType.GESTURE_TRAIL)

            // Color change and scale animation on shutter button
            shutterBtn?.let { btn ->
                btn.imageTintList = ColorStateList.valueOf(accentColor)
                btn.animate()
                    .scaleX(0.82f)
                    .scaleY(0.82f)
                    .setDuration(200)
                    .start()
            }

            // Prominent circular progress spinner around shutter
            shutterProgress?.let { progress ->
                progress.indeterminateTintList = ColorStateList.valueOf(accentColor)
                progress.visibility = View.VISIBLE
            }

            // Ensure no pill is shown during processing
            statusIndicator?.visibility = View.GONE
            statusIndicator?.text = ""

            controlsBar?.foreground = loadingBorderDrawable
            loadingAnimator = ValueAnimator.ofFloat(0.25f, 1f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { animator ->
                    val alpha = (animator.animatedValue as Float * 255).toInt()
                    val animatedColor = (alpha shl 24) or (accentColor and 0x00FFFFFF)
                    loadingBorderDrawable.setStroke(4, animatedColor)
                }
                start()
            }
        } else {
            if (!isLoadingAnimationActive) return
            isLoadingAnimationActive = false

            // Restore shutter button
            shutterBtn?.let { btn ->
                btn.imageTintList = null
                btn.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()
                btn.isEnabled = true
            }

            // Hide circular progress spinner
            shutterProgress?.visibility = View.GONE

            // Hide status indicator
            statusIndicator?.visibility = View.GONE

            loadingAnimator?.cancel()
            loadingAnimator = null
            loadingBorderDrawable.setStroke(4, Color.TRANSPARENT)
            controlsBar?.foreground = null
        }
    }

    private fun showNoTextDetected() {
        statusIndicator?.apply {
            text = context.getString(R.string.ocr_no_text_detected)
            animate().cancel()
            alpha = 0f
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(200)
                .withEndAction {
                    postDelayed({
                        animate()
                            .alpha(0f)
                            .setDuration(300)
                            .withEndAction { visibility = View.GONE }
                            .start()
                    }, 1800)
                }
                .start()
        }
    }

    private fun openOcrSettings() {
        val intent = Intent().apply {
            setClass(context, SettingsActivity::class.java)
            putExtra("screen", SettingsDestination.OCR)
            putExtra("from_ime", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OCR settings activity", e)
        }
    }

    fun applyColors(colors: Colors) {
        statusIndicator?.let {
            it.setTextColor(Color.WHITE)
        }
    }
}
