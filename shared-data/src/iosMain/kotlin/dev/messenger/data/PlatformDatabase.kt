package dev.messenger.data

import androidx.room.Room
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
fun iosDatabase(): MessengerDatabase {
    val directory = NSFileManager.defaultManager.URLForDirectory(
        NSApplicationSupportDirectory, NSUserDomainMask, null, true, null,
    )
    val path = requireNotNull(directory?.path) + "/messenger.db"
    return buildDatabase(Room.databaseBuilder<MessengerDatabase>(name = path))
}

fun iosLocalStore(): LocalStore = LocalStore(iosDatabase())

@OptIn(ExperimentalForeignApi::class)
fun iosManagedFiles(): ManagedFiles {
    val directory = NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
    return ManagedFiles(requireNotNull(directory?.path) + "/attachments")
}