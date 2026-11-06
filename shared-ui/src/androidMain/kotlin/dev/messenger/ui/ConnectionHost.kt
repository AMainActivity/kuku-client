package dev.messenger.ui
import dev.messenger.runtime.*
import dev.messenger.core.ui.*
import dev.messenger.feature.chats.*
import dev.messenger.feature.auth.*
import dev.messenger.feature.profile.*
import dev.messenger.feature.conversation.*
import dev.messenger.feature.groups.*
import dev.messenger.feature.admin.*

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable actual fun ConnectionHost(authenticated:Boolean,dark:Boolean) {
    val context=LocalContext.current
    DisposableEffect(context,dark) {
        val activity=context as? android.app.Activity
        activity?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
        onDispose {}
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val currentAuthenticated by rememberUpdatedState(authenticated)
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted && currentAuthenticated) AndroidRuntime.start(context)
    }
    LaunchedEffect(authenticated) { if(authenticated && Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(authenticated,lifecycle) {
        if(authenticated) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            AndroidRuntime.container?.transfers?.activeWork?.collect { active ->
                if(active) try {
                    androidx.core.content.ContextCompat.startForegroundService(context,android.content.Intent(context,FileTransferService::class.java))
                } catch(_:IllegalStateException) { /* UI still owns transfers; leave background start for a visible retry. */ }
                catch(_:SecurityException) { /* No privileged fallback. */ }
            }
        }
    }
    DisposableEffect(authenticated,lifecycle) {
        fun start() { if(authenticated && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) AndroidRuntime.start(context) }
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_START) start() }
        lifecycle.addObserver(observer); start()
        onDispose { lifecycle.removeObserver(observer) }
    }
}
