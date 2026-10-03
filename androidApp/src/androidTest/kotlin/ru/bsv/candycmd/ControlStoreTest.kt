package ru.bsv.candycmd

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.bsv.candycmd.connection.LocalControlStore
import ru.bsv.candycmd.shared.control.*
import java.io.File
import android.content.ContextWrapper

class ControlStoreTest {
    private fun isolatedContext(): ContextWrapper {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = File(target.cacheDir, "control-store-test").apply { mkdirs() }
        }
    }

    @Test fun newStoreInstanceRestoresPresetsAndUncertainMarker() = runBlocking {
        // Isolated test files, never the user's profile or presets.
        val context = isolatedContext()
        val first = LocalControlStore(context)
        val values = listOf(Preset("one", "Через полчаса", Settings("daily-59", delayMinutes = 30)))
        try {
            first.savePresets(values)
            first.setPending(true)
            val recreated = LocalControlStore(context)
            assertEquals(values, recreated.loadPresets())
            assertTrue(recreated.isPending())
            recreated.savePresets(listOf(values.single().copy(name = "Другое имя")))
            assertTrue(first.isPending())
            recreated.savePresets(emptyList())
            assertTrue(first.loadPresets().isEmpty())
            recreated.setPending(false)
            assertFalse(first.isPending())
        } finally { first.savePresets(emptyList()); first.setPending(false) }
    }

    @Test fun favoriteProgramsSurviveRecreationAndRejectCorruptFile() = runBlocking {
        val context = isolatedContext()
        val file = File(context.noBackupFilesDir, "favorite-programs-v1")
        try {
            LocalControlStore(context).saveFavoritePrograms(setOf("wool", "extra-duvet"))
            assertEquals(setOf("wool", "extra-duvet"), LocalControlStore(context).loadFavoritePrograms())
            file.writeText("wool\nbroken id")
            assertTrue(runCatching { LocalControlStore(context).loadFavoritePrograms() }.isFailure)
            assertEquals("wool\nbroken id", file.readText())
        } finally { file.delete() }
    }

    @Test fun corruptFilesFailClosedAndAreNotReplacedByRead() = runBlocking {
        val context = isolatedContext()
        val presetFile = File(context.noBackupFilesDir, "presets-v1")
        val markerFile = File(context.noBackupFilesDir, "command-pending")
        try {
            presetFile.writeText("broken")
            markerFile.writeText("broken")
            val store = LocalControlStore(context)
            assertTrue(runCatching { store.loadPresets() }.isFailure)
            assertTrue(runCatching { store.isPending() }.isFailure)
            assertEquals("broken", presetFile.readText())
            assertEquals("broken", markerFile.readText())
        } finally { presetFile.delete(); markerFile.delete() }
    }
}
