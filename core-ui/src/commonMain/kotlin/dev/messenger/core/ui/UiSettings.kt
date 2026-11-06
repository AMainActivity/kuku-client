package dev.messenger.core.ui
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class UiSettings(initialTheme:String,private val persist:(String)->Unit) {
    private val selectedTheme=MutableStateFlow(initialTheme.takeIf { it in setOf("system","light","dark") } ?: "system")
    val theme=selectedTheme.asStateFlow()
    fun selectTheme(value:String) {
        require(value in setOf("system","light","dark"))
        persist(value);selectedTheme.value=value
    }
}
