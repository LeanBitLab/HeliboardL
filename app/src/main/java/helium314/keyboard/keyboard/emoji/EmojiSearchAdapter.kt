package helium314.keyboard.keyboard.emoji

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings

class EmojiSearchAdapter(
    private val onEmojiClicked: (String) -> Unit
) : RecyclerView.Adapter<EmojiSearchAdapter.ViewHolder>() {

    private var emojis: List<String> = emptyList()

    fun submitList(newEmojis: List<String>) {
        this.emojis = newEmojis
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // We can reuse emoji_key_view or just a simple text view.
        // Let's use a simple TextView with appropriate styling.
        // Actually, we can inflate a standard key layout or just create a text view programmatically.
        // For simplicity and performance, programmatic TextView is fine, or a simple layout.
        val textView = TextView(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, // Width (was MATCH_PARENT which forced one item per view)
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 16) // Padding for touch target
            textSize = 24f // Emoji size
            setTextColor(Settings.getValues().mColors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT))
        }
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val emoji = emojis[position]
        holder.textView.text = emoji
        holder.textView.setOnClickListener { onEmojiClicked(emoji) }
    }

    override fun getItemCount(): Int = emojis.size

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
