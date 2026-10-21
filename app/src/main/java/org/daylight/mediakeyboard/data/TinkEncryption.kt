package org.daylight.mediakeyboard.data

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadFactory
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import android.content.Context
import com.google.crypto.tink.RegistryConfiguration
import java.nio.charset.StandardCharsets

class TinkEncryption(context: Context) {
    private val aead: Aead
    private val keysetName = "my_keyset"
    private val prefFileName = "tink_keyset_prefs"

    init {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetName, prefFileName)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://my_master_key")
            .build()
            .keysetHandle
        aead = keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun encrypt(plaintext: String): String {
        val ciphertext = aead.encrypt(plaintext.toByteArray(StandardCharsets.UTF_8), null)
        return android.util.Base64.encodeToString(ciphertext, android.util.Base64.DEFAULT)
    }

    fun decrypt(ciphertextBase64: String): String {
        val ciphertext = android.util.Base64.decode(ciphertextBase64, android.util.Base64.DEFAULT)
        val plaintext = aead.decrypt(ciphertext, null)
        return String(plaintext, StandardCharsets.UTF_8)
    }
}
