package dev.messenger.data

import dev.messenger.data.network.*
import io.ktor.client.engine.okhttp.OkHttp

fun androidNetwork(): MessengerNetwork = MessengerNetwork(messengerHttpClient(OkHttp.create {
    config { followRedirects(false); followSslRedirects(false) }
}))
