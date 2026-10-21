package org.daylight.mediakeyboard;

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.daylight.mediakeyboard.data.ApiKeyStore
import org.daylight.mediakeyboard.data.MediaResolver
import org.daylight.mediakeyboard.data.ResolverRegistry

class SettingsActivity : AppCompatActivity() {

    private lateinit var apiKeyStore: ApiKeyStore
    private val registry = ResolverRegistry.createDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        apiKeyStore = ApiKeyStore.get(this)
        val container = findViewById<LinearLayout>(R.id.apiKeysContainer)

        registry.all().forEach { resolver ->
            container.addView(buildKeyRow(resolver))
        }
    }

    private fun buildKeyRow(resolver: MediaResolver): View {
        val row = layoutInflater.inflate(R.layout.item_api_key, containerOrNull(), false)
        val title = row.findViewById<TextView>(R.id.apiKeyTitle)
        val input = row.findViewById<EditText>(R.id.apiKeyInput)
        val save = row.findViewById<Button>(R.id.apiKeySave)

        title.text = resolver.displayName
        input.setText(apiKeyStore.get(resolver.serviceId) ?: "")
        input.hint = "API key для ${resolver.serviceId}"

        save.setOnClickListener {
            val value = input.text.toString().trim()
            apiKeyStore.set(resolver.serviceId, value)
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }
        return row
    }

    private fun containerOrNull(): ViewGroup? = findViewById(R.id.apiKeysContainer)
}
