package dev.messenger.domain

import kotlin.test.*
import kotlin.uuid.Uuid

class IdentityTest {
    @Test fun credentialsAndUrlsCannotBecomeHosts() {
        listOf("https://example.com", "user@example.com", "example.com/path", "host?token=x",
            "-host", "host..com", "host\\path", "例子.cn").forEach {
            assertNull(ServerEndpoint.parse(it, "443"), it)
        }
        assertNull(ServerEndpoint.parse("example.com", "0"))
        assertNull(ServerEndpoint.parse("example.com", "65536"))
        assertEquals("example.com", ServerEndpoint.parse(" EXAMPLE.com ", "443")?.authority)
        assertEquals("example.com:8443", ServerEndpoint.parse("example.com", "8443")?.authority)
    }
    @Test fun sameUserOnDifferentServersIsNotSameAccount() {
        val userId = Uuid.random()
        assertNotEquals(AccountId(Uuid.random(), userId).storageKey, AccountId(Uuid.random(), userId).storageKey)
    }
}
