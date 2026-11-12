package dev.hfps.reveal.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoListCodecTest {
    @Test
    fun everyScopeRoundTrips() {
        val scopes = listOf(LibraryScope.All, LibraryScope.Recent, LibraryScope.FromApps, LibraryScope.Source(42))
        for (scope in scopes) {
            val query = PhotoQuery(scope, PhotoFilter.RATED, 3, PhotoSort.NAME, "dsc 01")
            assertThat(PhotoListCodec.decode(PhotoListCodec.encode(query))).isEqualTo(query)
        }
    }

    @Test
    fun searchMayContainAnything() {
        val query = PhotoQuery(search = "a:b|c\nd")
        assertThat(PhotoListCodec.decode(PhotoListCodec.encode(query))).isEqualTo(query)
    }

    @Test
    fun garbageIsNull() {
        assertThat(PhotoListCodec.decode("")).isNull()
        assertThat(PhotoListCodec.decode("nope")).isNull()
        assertThat(PhotoListCodec.decode("x\u0001ALL\u00011\u0001NAME\u0001")).isNull()
    }
}
