package com.zcw.chatai.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zcw.chatai.data.model.ChatConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ChatSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val temperature: Double?,
    val themeMode: ThemeMode,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        val Default = ChatSettings(DEFAULT_BASE_URL, "", DEFAULT_MODEL, "", null, ThemeMode.SYSTEM)
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<ChatSettings> = store.data.map { it.toChatSettings() }

    fun chatConfig(): Flow<ChatConfig> = settings.map { settings ->
        ChatConfig(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.model,
            systemPrompt = settings.systemPrompt,
            temperature = settings.temperature,
        )
    }

    suspend fun updateConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        temperature: Double?,
    ) {
        store.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl
            prefs[KEY_API_KEY] = apiKey
            prefs[KEY_MODEL] = model
            prefs[KEY_SYSTEM_PROMPT] = systemPrompt
            if (temperature == null) {
                prefs.remove(KEY_TEMPERATURE)
            } else {
                prefs[KEY_TEMPERATURE] = temperature
            }
        }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        store.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    private fun Preferences.toChatSettings(): ChatSettings = ChatSettings(
        baseUrl = this[KEY_BASE_URL] ?: ChatSettings.DEFAULT_BASE_URL,
        apiKey = this[KEY_API_KEY].orEmpty(),
        model = this[KEY_MODEL] ?: ChatSettings.DEFAULT_MODEL,
        systemPrompt = this[KEY_SYSTEM_PROMPT].orEmpty(),
        temperature = this[KEY_TEMPERATURE],
        themeMode = this[KEY_THEME_MODE].toThemeMode(),
    )

    private fun String?.toThemeMode(): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name == this } ?: ThemeMode.SYSTEM

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL = stringPreferencesKey("model")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_TEMPERATURE = doublePreferencesKey("temperature")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
