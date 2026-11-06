package dev.messenger.core.ui
import platform.Foundation.NSUserDefaults
fun iosUiSettings()=UiSettings(NSUserDefaults.standardUserDefaults.stringForKey("theme") ?: "system") {
    NSUserDefaults.standardUserDefaults.setObject(it,forKey="theme")
}
