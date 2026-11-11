package dev.hfps.reveal.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetaDaoTest {
    private lateinit var database: RevealDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a stored value reads back and a replaced value wins`() = runTest {
        val dao = database.metaDao()

        dao.put(MetaEntity("seed", "a"))
        dao.put(MetaEntity("seed", "b"))

        assertThat(dao.get("seed")).isEqualTo("b")
        assertThat(dao.get("missing")).isNull()
    }
}
