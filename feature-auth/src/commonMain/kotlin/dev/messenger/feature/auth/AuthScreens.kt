package dev.messenger.feature.auth
import dev.messenger.core.ui.*

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
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ServerScreen(onContinue: (String, Int) -> Unit) {
    val viewModel = koinViewModel<SetupViewModel>()
    val host by viewModel.host.collectAsStateWithLifecycle()
    val port by viewModel.port.collectAsStateWithLifecycle()
    val endpoint = viewModel.endpoint()
    var hostEdited by rememberSaveable { mutableStateOf(false) }
    var portEdited by rememberSaveable { mutableStateOf(false) }
    val hostInvalid=(hostEdited || host.isNotEmpty()) && dev.messenger.domain.AddressValidation.normalizeHost(host)==null
    val portInvalid=(portEdited || port.isNotEmpty()) && dev.messenger.domain.AddressValidation.port(port)==null
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(viewModel,lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.cancelCheck()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);viewModel.cancelCheck() }
    }
    AppPage(stringResource(Res.string.server_title)) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max=480.dp)) {
        SectionCard {
        Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)

        Text(stringResource(Res.string.server_description))
        OutlinedTextField(host, { hostEdited=true;viewModel.changeHost(it) }, enabled = !busy,
            isError=hostInvalid, supportingText={if(hostInvalid) Text(stringResource(Res.string.invalid_host))},
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,autoCorrectEnabled=false), label = { Text(stringResource(Res.string.host)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { portEdited=true;viewModel.changePort(it) }, enabled = !busy,
            isError=portInvalid, supportingText={if(portInvalid) Text(stringResource(Res.string.invalid_port))}, label = { Text(stringResource(Res.string.port)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Button(modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled = endpoint != null && !busy, onClick = { viewModel.connect(onContinue) }) {
            Text(stringResource(Res.string.continue_label))
        }
        if(busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(Res.string.checking_server))
            TextButton(onClick=viewModel::cancelCheck) {Text(stringResource(Res.string.cancel))}
        }
        ErrorNotice(error)
        }
        }
    }
    }
}

@Composable
fun LoginScreen(host: String, port: Int, back: () -> Unit) {
    val model = koinViewModel<AuthViewModel>()
    val leaving by model.leaving.collectAsStateWithLifecycle()
    val cancelLogin = { model.cancelLogin(back) }
    androidx.navigationevent.compose.NavigationBackHandler(
        state=androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None),
        onBackCompleted=cancelLogin,
    )
    var email by rememberSaveable { mutableStateOf("") }
    var emailEdited by rememberSaveable { mutableStateOf(false) }
    val validEmail=dev.messenger.domain.AddressValidation.normalizeEmail(email)!=null
    val emailInvalid=(emailEdited || email.isNotEmpty()) && !validEmail
    // OTP and TOTP are ephemeral: never SavedStateHandle/rememberSaveable/Room.
    var code by remember { mutableStateOf("") }
    var mfa by remember { mutableStateOf("") }
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val challenge by model.challengeReady.collectAsStateWithLifecycle()
    val resendAt by model.resendAt.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(resendAt) {
        while (now < resendAt) { kotlinx.coroutines.delay(1000); now = kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }
    AppPage(stringResource(Res.string.sign_in),cancelLogin) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max=480.dp)) {
        SectionCard {


        Text("${host}:${port}")
        Text(stringResource(Res.string.email_description))
        OutlinedTextField(email, { emailEdited=true;email = it;model.reportError(null) },
            isError=emailInvalid, supportingText={if(emailInvalid) Text(stringResource(Res.string.invalid_email))}, enabled = !busy && !leaving, label = { Text(stringResource(Res.string.email)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email,autoCorrectEnabled=false), modifier = Modifier.fillMaxWidth())
        Button(modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled = !busy && !leaving && validEmail && now >= resendAt,
            onClick = { model.requestCode(host, port, email.trim()) }) {
            Text(stringResource(if (challenge) Res.string.resend_code else Res.string.send_code))
        }
        if (challenge) {
            Text(stringResource(Res.string.code_sent))
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) },
                label = { Text(stringResource(Res.string.email_code)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(mfa, { mfa = it.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(Res.string.mfa_optional)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Button(modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled = !busy && !leaving && code.length == 8, onClick = { model.verify(code, mfa); code = ""; mfa = "" }) {
                Text(stringResource(Res.string.sign_in))
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorNotice(error)
        }
        }
    }
    }
}

