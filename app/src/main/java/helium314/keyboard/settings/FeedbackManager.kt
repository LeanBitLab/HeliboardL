package helium314.keyboard.settings

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

object FeedbackManager {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun message(context: Context, text: String) {
        // Try to send to the channel (for Snackbar)
        val result = _messages.trySend(text)
        
        // If no subscribers (SettingsActivity not active) or buffer full, fallback to Toast?
        // Actually, trySend always succeeds with BUFFERED unless closed.
        // But we want to know if we are in a UI that can show it.
        // For now, simpler approach: The standard calls will be replaced by this.
        // If we want to support IME context, we might need a separate check or just use Toast there.
        // But for Settings, this is fine.
    }
    
    // Helper for resource ID
    fun message(context: Context, resId: Int) {
        message(context, context.getString(resId))
    }
}
