package dev.messenger.data

import dev.messenger.data.network.*
import io.ktor.client.engine.darwin.Darwin

fun iosNetwork(): MessengerNetwork = MessengerNetwork(messengerHttpClient(Darwin.create()))
