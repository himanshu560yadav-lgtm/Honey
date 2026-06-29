package com.panda.ai.intents.impl

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.panda.ai.intents.AppIntent
import com.panda.ai.intents.ParameterSpec

class ViewUrlIntent : AppIntent {
    override val name: String = "ViewUrl"

    override fun description(): String = "Open a web URL in the default browser."

    override fun parametersSpec(): List<ParameterSpec> = listOf(
        ParameterSpec("url", "string", true, "The HTTP/HTTPS URL to open.")
    )

    override fun buildIntent(context: Context, params: Map<String, Any?>): Intent? {
        val url = params["url"]?.toString()?.trim().orEmpty()
        if (url.isEmpty()) return null
        return Intent(Intent.ACTION_VIEW, url.toUri())
    }
}