package dev.messenger.domain

import kotlin.test.*

class AddressValidationTest {
    @Test fun hostAcceptsDnsAndStrictIpAddresses() {
        listOf("chat.example.com","localhost","xn--e1afmkfd.xn--p1ai","192.168.1.111","0.0.0.0","255.255.255.255",
            "::1","2001:db8::1","[2001:db8::1]","::ffff:192.0.2.1","1:2:3:4:5:6:7:8").forEach {
            assertNotNull(AddressValidation.normalizeHost(it),it)
        }
        assertEquals("chat.example.com",AddressValidation.normalizeHost(" CHAT.example.com "))
        assertEquals("[2001:db8::1]:8443",ServerEndpoint.parse("[2001:db8::1]","8443")?.authority)
        assertEquals("[::1]",ServerEndpoint.parse("::1","443")?.authority)
    }
    @Test fun malformedHostsAndAmbiguousIpv4AreRejected() {
        listOf(""," ","https://chat.example.com","host:443","host/path","user@host","host..ru","-host.ru","host-.ru",
            "host_name.ru","999.1.1.1","192.168.1","192.168.01.1","1.2.3.4.5","1234","[1.2.3.4]",
            "1::2::3","2001:db8:::1","1:2:3:4:5:6:7","1:2:3:4:5:6:7:8:9","1:2:3:4:5:6:7:8::",
            "::ffff:999.0.0.1","[::1]:443","fe80::1%wlan0","[::1","host?token=x").forEach {
            assertNull(AddressValidation.normalizeHost(it),it)
        }
    }
    @Test fun portIsDecimalAndWithinRange() {
        listOf("0","65536","-1","+443","4.43","4 43","abc","").forEach { assertNull(AddressValidation.port(it),it) }
        assertEquals(1,AddressValidation.port("1"));assertEquals(65535,AddressValidation.port("65535"))
        assertEquals(443,AddressValidation.port(" 443 "))
    }
    @Test fun emailAllowsCommonMailboxNamesAndRejectsInvalidSyntax() {
        listOf("name@example.com","name.surname+tag@example.co.uk","o'connor@example.com","почта@пример.рф").forEach {
            assertNotNull(AddressValidation.normalizeEmail(it),it)
        }
        assertEquals("Name+tag@example.com",AddressValidation.normalizeEmail(" Name+tag@EXAMPLE.COM "))
        listOf("","name","name@","@example.com","name@@example.com","name@example","name@127.0.0.1",
            "name..part@example.com",".name@example.com","name.@example.com","na me@example.com",
            "name@ex ample.com","Name <name@example.com>","name@example..com","name@-example.com",
            "name@exam_ple.com","name@example.com\nBcc:other@example.com","a".repeat(65)+"@example.com").forEach {
            assertNull(AddressValidation.normalizeEmail(it),it)
        }
    }
}