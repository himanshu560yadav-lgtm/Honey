package com.panda.ai.intents.impl

import android.content.Context
import android.content.Intent
import com.panda.ai.intents.AppIntent
import com.panda.ai.intents.ParameterSpec

class ShareTextIntent : AppIntent {
    override val name: String = "ShareText"

    override fun description(): String =
        "Open the system share sheet to send text to any app."

    override fun parametersSpec(): List<ParameterSpec> = listOf(
        ParameterSpec("text", "string", true, "The text to share."),
        ParameterSpec("chooser_title", "string", false, "Optional title for share sheet.")
    )

    override fun buildIntent(context: Context, params: Map<String, Any?>): Intent? {
        val text = params["text"]?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(base, params["chooser_title"]?.toString() ?: "Share via")
    }
}