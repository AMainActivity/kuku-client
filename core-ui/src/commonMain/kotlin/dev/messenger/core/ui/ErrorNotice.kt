package dev.messenger.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.messenger.domain.OutboxState
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable fun ErrorNotice(code: String?,onDismiss:(()->Unit)?=null) {
    if (code == null) return
    // Authenticated screens share one connection indicator in the toolbar.
    // A stored transport failure must never become a second, persistent status banner.
    if(LocalConnectionIndicatorEnabled.current &&
        code in setOf("CONNECTION_ERROR","CONNECTION_LOST","AUTH_REQUIRED","TIMEOUT")) return
    Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()) {
    Column {
    Text(stringResource(when(code) {
        "ATTACHMENT_LIMIT" -> Res.string.capture_limit
        "FILE_OPEN_FAILED" -> Res.string.file_open_failed
        "SHARE_FILES_UNAVAILABLE" -> Res.string.share_files_unavailable
        "CONNECTION_ERROR", "CONNECTION_LOST", "AUTH_REQUIRED" -> Res.string.connection_failed
        "TIMEOUT" -> Res.string.connection_timeout
        "DISCOVERY_HTTP_ERROR", "INVALID_DISCOVERY", "HELLO_REQUIRED", "UNSUPPORTED_AUTH", "MALFORMED_FRAME", "UNSUPPORTED_PROTOCOL" -> Res.string.invalid_server
        "MFA_REQUIRED" -> Res.string.admin_mfa_required
        "STEP_UP_REQUIRED" -> Res.string.admin_step_up_required
        "FORBIDDEN" -> Res.string.access_denied
        "INVALID_EMAIL" -> Res.string.invalid_email
        "INVALID_CODE" -> Res.string.invalid_code
        "RATE_LIMITED" -> Res.string.rate_limited
        "LOGIN_REQUIRED", "UNAUTHENTICATED", "REFRESH_REUSE", "REFRESH_WINDOW_EXPIRED" -> Res.string.login_again
        "SERVER_IDENTITY_CHANGED", "SESSION_IDENTITY_CHANGED" -> Res.string.server_changed
        "UNSUPPORTED_CONTRACT" -> Res.string.unsupported_server
        "CHAT_UNAVAILABLE" -> Res.string.chat_unavailable
        "LOCAL_LOGOUT_ONLY" -> Res.string.local_logout_only
        else -> Res.string.operation_failed
    }), color = MaterialTheme.colorScheme.onErrorContainer,modifier=Modifier.padding(16.dp))
    if(onDismiss!=null) TextButton(onClick=onDismiss,modifier=Modifier.align(Alignment.End)) {Text(stringResource(Res.string.dismiss_error))}
    }
    }
}

