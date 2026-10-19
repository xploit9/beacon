package dev.hermes.android.data

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class ExternalMediaSecurityTest {
    private fun address(value: String) = InetAddress.getByName(value)
    private fun mappedIpv4(value: String): InetAddress {
        val bytes = ByteArray(16).apply {
            this[10] = 0xff.toByte()
            this[11] = 0xff.toByte()
            address(value).address.copyInto(this, 12)
        }
        return Inet6Address.getByAddress(null, bytes, null)
    }

    @Test fun rejectsNonPublicAddressClassesIncludingIpv4MappedIpv6() {
        listOf(
            "127.0.0.1", "::1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "169.254.1.1", "fe80::1", "224.0.0.1", "ff02::1", "0.0.0.0",
            "::", "fc00::1", "fd00::1",
        ).forEach { value -> assertFalse("$value must not be fetched", isPublicExternalMediaAddress(address(value))) }
        listOf("127.0.0.1", "192.168.1.1").forEach { value ->
            val mapped = mappedIpv4(value)
            assertTrue(mapped is Inet6Address)
            assertFalse("IPv4-mapped $value must not be fetched", isPublicExternalMediaAddress(mapped))
        }

        listOf("8.8.8.8", "2606:4700:4700::1111").forEach { value ->
            assertTrue("$value is a public address", isPublicExternalMediaAddress(address(value)))
        }
    }

    @Test fun dnsDropsUnsafeAnswersAndFailsClosedWhenNoPublicAddressRemains() {
        val mixed = externalMediaDns(fixedDns(address("192.168.1.1"), address("8.8.8.8")))
        assertEquals(listOf(address("8.8.8.8")), mixed.lookup("media.example"))

        val privateOnly = externalMediaDns(fixedDns(address("127.0.0.1"), address("::1")))
        assertThrows(UnknownHostException::class.java) { privateOnly.lookup("media.example") }
    }
}

private fun fixedDns(vararg answers: java.net.InetAddress) = object : Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> = answers.toList()
}
