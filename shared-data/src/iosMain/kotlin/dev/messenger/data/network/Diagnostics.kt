package dev.messenger.data.network

internal actual fun writeDiagnostic(summary:String) { platform.Foundation.NSLog("%@",summary) }