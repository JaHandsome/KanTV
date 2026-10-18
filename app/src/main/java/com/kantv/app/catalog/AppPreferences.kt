package com.kantv.app.catalog

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "viewing_state")

class AppPreferences(private val context: Context) {
    suspend fun lastChannelId(): String? = context.dataStore.data.first()[LAST_CHANNEL]

    suspend fun saveLastChannelId(channelId: String) {
        context.dataStore.edit { it[LAST_CHANNEL] = channelId }
    }

    private companion object {
        val LAST_CHANNEL = stringPreferencesKey("last_successful_channel")
    }
}
