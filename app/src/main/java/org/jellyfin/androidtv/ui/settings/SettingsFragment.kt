package org.jellyfin.androidtv.ui.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.jellyfin.androidtv.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val trickplayPreference: SwitchPreferenceCompat? = findPreference("pref_enable_trickplay")
        trickplayPreference?.setOnPreferenceChangeListener { _, newValue ->
            // Handle the preference change here
            true
        }
    }
}
