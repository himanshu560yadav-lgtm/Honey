package com.panda.ai.intents.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.panda.ai.intents.AppIntent
import com.panda.ai.intents.ParameterSpec

class EmailComposeIntent : AppIntent {
    override val name: String = "EmailCompose"

    override fun description(): String =
        "Use this intent to send an email using the default email app."

    override fun parametersSpec(): List<ParameterSpec> = listOf(
        ParameterSpec("to", "string", false, "Comma-separated email recipients."),
        ParameterSpec("subject", "string", false, "Email subject."),
        ParameterSpec("body", "string", false, "Email body text.")
    )

    override fun buildIntent(context: Context, params: Map<String, Any?>): Intent? {
        val to = params["to"]?.toString()?.trim().orEmpty()
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            params["subject"]?.toString()?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            params["body"]?.toString()?.takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
    }
}