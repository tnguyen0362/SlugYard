package com.sluggyard.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Sentry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device-scoped crash reporting preference.
 *
 * Uses SharedPreferences (not DataStore) so [SlugYardApplication] can read the
 * value synchronously before Sentry init. Default is **on**.
 *
 * Reports go to the configured Sentry DSN (our project backend) — not Supabase.
 * We never attach full logcat; only crash/ANR + bounded safe breadcrumbs.
 */
@Singleton
class CrashReportingPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val enabledState = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))

    val enabled: Flow<Boolean> = enabledState.asStateFlow()

    fun isEnabled(): Boolean = enabledState.value

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        enabledState.value = enabled
        // Hot-toggle without restart. Init still respects this flag on next cold start.
        runCatching {
            Sentry.getCurrentScopes().options.isEnabled = enabled
        }
    }

    companion object {
        const val PREFS_NAME = "sentry_settings"
        const val KEY_ENABLED = "crash_reporting_enabled"
        const val DEFAULT_ENABLED = true
    }
}
