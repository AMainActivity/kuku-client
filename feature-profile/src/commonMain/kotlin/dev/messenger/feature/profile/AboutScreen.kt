package dev.messenger.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.messenger.core.ui.*
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable fun AboutScreen(back:()->Unit) {
    AppPage(stringResource(Res.string.about_app),back) {
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SectionCard {
            Text(stringResource(Res.string.app_name),style=MaterialTheme.typography.headlineLarge)
            Text(stringResource(Res.string.app_version))
            Text(stringResource(Res.string.about_description))
            }
        }
    }
}