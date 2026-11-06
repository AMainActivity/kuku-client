package dev.messenger.data

import android.content.Context
import androidx.room.Room

fun androidDatabase(context: Context): MessengerDatabase {
    val app = context.applicationContext
    return buildDatabase(Room.databaseBuilder<MessengerDatabase>(app, app.getDatabasePath("messenger.db").absolutePath))
}

fun androidLocalStore(context: Context): LocalStore = LocalStore(androidDatabase(context))
