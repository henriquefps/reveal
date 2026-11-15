package dev.hfps.reveal.sync.account

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** ADR 0018 "W5 (f)": the addresses the device key may go to without asking. */
class ServerAddressesTest {
    @Test
    fun `https goes anywhere`() {
        assertThat(ServerAddresses.keySafe("https://photos.example.org")).isTrue()
        assertThat(ServerAddresses.keySafe("HTTPS://203.0.113.7:8443/")).isTrue()
    }

    @Test
    fun `loopback, Tailscale, private LAN and link-local http addresses are safe`() {
        val safe = listOf(
            "http://localhost:3471", "http://127.0.0.1:3471", "http://127.8.9.10",
            "http://100.64.0.1:3471", "http://100.87.163.92:3471", "http://100.127.255.254",
            "http://nas.tail1234.ts.net:3471", "http://NAS.Tail1234.TS.NET.",
            "http://10.0.0.5", "http://172.16.0.1", "http://172.31.255.255", "http://192.168.1.20:8080",
            "http://169.254.10.1", "http://[fd7a:115c:a1e0::1]:3471", "http://[fd00::1]", "http://[fe80::1]",
            "http://[febf::1]", "http://[::1]:3471", "http://[::ffff:192.168.0.2]",
        )
        for (url in safe) assertWithMessage(url).that(ServerAddresses.keySafe(url)).isTrue()
    }

    @Test
    fun `public addresses and plain names over http ask first`() {
        val ask = listOf(
            "http://203.0.113.7:3471", "http://8.8.8.8", "http://100.63.255.255", "http://100.128.0.1",
            "http://172.15.0.1", "http://172.32.0.1", "http://192.169.1.1", "http://169.255.0.1",
            "http://photos.example.org", "http://nas.local", "http://ts.net.example.org", "http://evil-ts.net",
            "http://[2001:db8::1]", "http://[fc00::1]", "http://[fec0::1]", "http://[::ffff:8.8.8.8]",
            "http://10.0.0.256", "http://10.0.0", "http://010.0.0.1.2", "not a url", "ftp://10.0.0.1",
        )
        for (url in ask) assertWithMessage(url).that(ServerAddresses.keySafe(url)).isFalse()
    }

    @Test
    fun `the host shown to the user`() {
        assertThat(ServerAddresses.host("http://203.0.113.7:3471/x")).isEqualTo("203.0.113.7")
        assertThat(ServerAddresses.host("http://[2001:db8::1]:3471")).isEqualTo("2001:db8::1")
        assertThat(ServerAddresses.host("http://photos.example.org")).isEqualTo("photos.example.org")
    }
}
