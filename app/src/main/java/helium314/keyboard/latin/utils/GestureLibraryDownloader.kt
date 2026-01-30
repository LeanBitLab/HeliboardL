// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helper class to download the gesture typing library from a trusted source.
 * The library is downloaded from the openboard repository which is referenced
 * in the official HeliBoard README.
 */
object GestureLibraryDownloader {
    private const val TAG = "GestureLibraryDownloader"
    private const val USER_AGENT = "LeanType/1.0"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    
    // Base URL for the gesture library files from the trusted openboard repository
    // This is the official source referenced in HeliBoard's README
    private const val BASE_URL = "https://github.com/erkserkserks/openboard/raw/46fdf2b550035ca69299ce312fa158e7ade36967/app/src/main/jniLibs"
    private const val LIB_NAME = "libjni_latinimegoogle.so"
    
    /**
     * Returns the download URL for the device's primary architecture.
     */
    fun getDownloadUrl(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return "$BASE_URL/$abi/$LIB_NAME"
    }
    
    /**
     * Returns the device's primary ABI (architecture).
     */
    fun getDeviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    
    /**
     * Downloads the gesture library to the app's files directory.
     * 
     * @param context Application context
     * @param onProgress Callback for download progress (0-100)
     * @return The downloaded file, or null if download failed
     */
    suspend fun downloadLibrary(
        context: Context,
        onProgress: ((Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val downloadUrl = getDownloadUrl() ?: return@withContext Result.failure(Exception("Unsupported device architecture"))
        
        try {
            Log.i(TAG, "Downloading gesture library from: $downloadUrl")
            
            val url = URL(downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed with response code: $responseCode")
                return@withContext Result.failure(Exception("Download failed: HTTP $responseCode"))
            }
            
            val contentLength = connection.contentLength
            val tempFile = File(context.filesDir, "tmplib_download")
            
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 100) / contentLength).toInt()
                            onProgress?.invoke(progress)
                        }
                    }
                }
            }
            
            Log.i(TAG, "Download complete: ${tempFile.length()} bytes")
            Result.success(tempFile)
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Checks if the gesture library is available for download (device architecture supported).
     */
    fun isDownloadAvailable(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        // Only these architectures have libraries available
        return abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }
}
