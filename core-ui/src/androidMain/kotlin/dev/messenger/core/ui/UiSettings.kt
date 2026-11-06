package dev.messenger.core.ui
import android.content.Context
fun androidUiSettings(context:Context):UiSettings {
    val preferences=context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
    return UiSettings(preferences.getString("theme","system") ?: "system") {
        preferences.edit().putString("theme",it).apply()
    }
}
