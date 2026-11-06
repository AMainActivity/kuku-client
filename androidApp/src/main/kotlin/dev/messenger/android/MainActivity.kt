package dev.messenger.android

import android.app.Application
import android.graphics.Color
import androidx.activity.SystemBarStyle
import android.os.Bundle
import android.content.Intent
import dev.messenger.ui.receiveShare
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.messenger.ui.App
import dev.messenger.ui.AppContainer
import dev.messenger.ui.androidContainer

class MessengerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = androidContainer(this)
    }
}

class MainActivity : ComponentActivity() {
    private var shareId=UUID.randomUUID().toString()
    override fun onSaveInstanceState(outState:Bundle) { outState.putString("incomingShareId",shareId); super.onSaveInstanceState(outState) }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent); setIntent(intent); shareId=UUID.randomUUID().toString()
        val container=(application as MessengerApplication).container
        container.openNotification(intent.getStringExtra("notificationAccount"),intent.getStringExtra("notificationChat"))
        container.receiveShare(this,intent,shareId)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        val container = (application as MessengerApplication).container
        shareId=savedInstanceState?.getString("incomingShareId") ?: shareId
        container.receiveShare(this,intent,shareId)
        if(savedInstanceState==null) container.openNotification(intent.getStringExtra("notificationAccount"),intent.getStringExtra("notificationChat"))
        setContent { App(container) }
    }
}
