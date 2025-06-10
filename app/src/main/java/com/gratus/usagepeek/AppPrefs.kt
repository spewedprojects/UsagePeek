package com.gratus.usagepeek

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("usagepeek_prefs")

object AppPrefs {
    private val ENABLED_PACKAGES = stringSetPreferencesKey("enabled_packages")

    fun enabledPackages(context: Context): Flow<Set<String>> =
        context.dataStore.data.map { it[ENABLED_PACKAGES] ?: emptySet() }

    suspend fun setEnabled(context: Context, pkg: String, on: Boolean) {
        context.dataStore.edit { prefs ->
            val set = (prefs[ENABLED_PACKAGES] ?: emptySet()).toMutableSet()
            if (on) set += pkg else set -= pkg
            prefs[ENABLED_PACKAGES] = set
        }
    }
}