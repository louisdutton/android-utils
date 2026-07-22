package app.grapheneos.apps.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.grapheneos.apps.PackageStates
import app.grapheneos.apps.R
import app.grapheneos.apps.core.appResources
import app.grapheneos.apps.core.normalizeRepositoryUrl
import app.grapheneos.apps.core.repositoryBaseUrl

class SettingsScreen : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = appResources.getString(R.string.pref_file_settings)
        addPreferencesFromResource(R.xml.settings)

        val key = getString(R.string.pref_key_repository_url)
        val preference = requireNotNull(findPreference<EditTextPreference>(key))
        if (preference.text == null) {
            preference.text = repositoryBaseUrl()
        }
        preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            normalizeRepositoryUrl(it.text.orEmpty()) ?: repositoryBaseUrl()
        }
        preference.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            it.setSelectAllOnFocus(true)
            it.setSingleLine(true)
        }
        preference.setOnPreferenceChangeListener { _, newValue ->
            val rawValue = newValue as String
            val normalized = normalizeRepositoryUrl(rawValue)
            if (normalized == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.pref_repository_url_invalid,
                    Toast.LENGTH_SHORT,
                ).show()
                false
            } else if (normalized != rawValue) {
                preference.text = normalized
                false
            } else {
                true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSlideTransitions(this)
    }

    override fun onStart() {
        super.onStart()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == getString(R.string.pref_key_repository_url)) {
            PackageStates.requestRepoUpdateNoSuspend(force = true)
        }
    }
}
