package com.panda.ai.intents.impl

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.panda.ai.intents.AppIntent
import com.panda.ai.intents.ParameterSpec

class DialIntent : AppIntent {
    override val name: String = "Dial"

    override fun description(): String =
        "Open the phone dialer with the specified phone number prefilled (no call is placed)."

    override fun parametersSpec(): List<ParameterSpec> = listOf(
        ParameterSpec("phone_number", "string", true, "The phone number to dial.")
    )

    override fun buildIntent(context: Context, params: Map<String, Any?>): Intent? {
        val raw = params["phone_number"]?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return Intent(Intent.ACTION_DIAL, "tel:${raw.replace(" ", "")}".toUri())
    }
}