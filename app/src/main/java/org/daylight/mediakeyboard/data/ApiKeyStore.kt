package org.daylight.mediakeyboard.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class ApiKeyStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("api_keys", Context.MODE_PRIVATE)

    private val tink: TinkEncryption by lazy {
        TinkEncryption(appContext)
    }

    fun get(serviceId: String): String? {
        val encrypted = prefs.getString(key(serviceId), null) ?: return null
        return try {
            tink.decrypt(encrypted)
        } catch (e: Exception) {
            Log.e("ApiKeyStore", "decrypt failed for $serviceId", e)
            null
        }
    }

    fun set(serviceId: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key(serviceId))
            else putString(key(serviceId), tink.encrypt(value))
        }.commit()
    }

    private fun key(serviceId: String) = "api_key_$serviceId"

    companion object {
        @Volatile
        private var INSTANCE: ApiKeyStore? = null

        fun get(context: Context): ApiKeyStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ApiKeyStore(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }
}
