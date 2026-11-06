package dev.messenger.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import dev.messenger.core.ui.AppPage
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun LicensesScreen(back: () -> Unit, loadLibraries: suspend () -> String) {
    val libraries by produceLibraries(block = loadLibraries)
    AppPage(stringResource(Res.string.third_party_licenses), back) {
        if (libraries == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LibrariesContainer(libraries, Modifier.fillMaxSize(), detailMode = LibraryDetailMode.Dialog)
        }
    }
}
