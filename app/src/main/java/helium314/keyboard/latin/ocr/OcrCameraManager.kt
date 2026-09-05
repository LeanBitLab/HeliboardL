// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.ocr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import helium314.keyboard.latin.utils.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OcrCameraManager(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isTorchOn: Boolean = false
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lifecycleOwner: ImeLifecycleOwner = ImeLifecycleOwner()

    companion object {
        private const val TAG = "OcrCameraManager"
        private const val MAX_IMAGE_DIMENSION = 1920
    }

    private class ImeLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }

        override val lifecycle: Lifecycle get() = registry
    }

    @SuppressLint("RestrictedApi")
    fun startCamera(previewView: PreviewView, onReady: () -> Unit = {}, onError: (Exception) -> Unit = {}) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera(previewView)
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera(previewView: PreviewView) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        lifecycleOwner.start()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            isTorchOn = false
        } catch (e: Exception) {
            Log.e(TAG, "Binding camera use cases failed", e)
        }
    }

    fun toggleTorch(): Boolean {
        val cam = camera ?: return false
        return try {
            if (cam.cameraInfo.hasFlashUnit()) {
                isTorchOn = !isTorchOn
                cam.cameraControl.enableTorch(isTorchOn)
                isTorchOn
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch", e)
            false
        }
    }

    fun isTorchEnabled(): Boolean = isTorchOn

    fun focus(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.e(TAG, "Focus failed", e)
        }
    }

    fun capturePhoto(onCaptured: (Bitmap) -> Unit, onError: (Exception) -> Unit) {
        val capture = imageCapture ?: run {
            onError(IllegalStateException("Camera capture is not ready"))
            return
        }

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val rawBitmap = image.toBitmap()
                        val scaledBitmap = scaleAndRotateBitmap(rawBitmap, rotation)
                        if (scaledBitmap != rawBitmap) {
                            rawBitmap.recycle()
                        }
                        image.close()
                        onCaptured(scaledBitmap)
                    } catch (e: Exception) {
                        image.close()
                        Log.e(TAG, "Error processing captured frame", e)
                        onError(e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture error", exception)
                    onError(exception)
                }
            }
        )
    }

    private fun scaleAndRotateBitmap(src: Bitmap, rotationDegrees: Int): Bitmap {
        var width = src.width
        var height = src.height

        val maxDim = maxOf(width, height)
        val scale = if (maxDim > MAX_IMAGE_DIMENSION) {
            MAX_IMAGE_DIMENSION.toFloat() / maxDim.toFloat()
        } else {
            1.0f
        }

        val matrix = Matrix()
        if (scale < 1.0f) {
            matrix.postScale(scale, scale)
        }
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }

        return if (!matrix.isIdentity) {
            Bitmap.createBitmap(src, 0, 0, width, height, matrix, true)
        } else {
            src
        }
    }

    fun stopCamera() {
        try {
            if (isTorchOn) {
                camera?.cameraControl?.enableTorch(false)
                isTorchOn = false
            }
            cameraProvider?.unbindAll()
            lifecycleOwner.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }

    fun release() {
        stopCamera()
        lifecycleOwner.destroy()
        cameraExecutor.shutdown()
    }
}
