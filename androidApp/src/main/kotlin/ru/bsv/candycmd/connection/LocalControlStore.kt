package ru.bsv.candycmd.connection

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.bsv.candycmd.shared.control.*
import ru.bsv.candycmd.shared.connection.ConnectionException
import ru.bsv.candycmd.shared.connection.ConnectionProblem
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream

/** User settings contain no device identity or ready-to-send request; all files exclude backup. */
class LocalControlStore(context: Context) : PendingCommandStore {
    private val presets = AtomicFile(File(context.noBackupFilesDir, "presets-v1"))
    private val favoritePrograms = AtomicFile(File(context.noBackupFilesDir, "favorite-programs-v1"))
    private val marker = AtomicFile(File(context.noBackupFilesDir, "command-pending"))
    private val mutex = Mutex()

    suspend fun loadPresets(): List<Preset> = storage {
        read(presets)?.let(PresetCodec::decode).orEmpty()
    }
    suspend fun savePresets(values: List<Preset>) = storage { write(presets, PresetCodec.encode(values)) }

    /** One catalog program id per line; ids no longer in the catalog are kept but never shown. */
    suspend fun loadFavoritePrograms(): Set<String> = storage {
        read(favoritePrograms)?.lines()?.filter(String::isNotEmpty)?.onEach(::requireProgramId)?.toSet().orEmpty()
    }
    suspend fun saveFavoritePrograms(ids: Set<String>) = storage {
        ids.forEach(::requireProgramId)
        write(favoritePrograms, ids.joinToString("\n"))
    }

    override suspend fun isPending(): Boolean = storage {
        val value = read(marker)
        require(value == null || value == "pending")
        value != null
    }
    override suspend fun setPending(pending: Boolean) = storage {
        if (pending) write(marker, "pending") else {
            marker.delete()
            check(!marker.baseFile.exists())
        }
    }

    private fun requireProgramId(id: String) = require(id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' })

    private fun read(file: AtomicFile): String? {
        val stream = try { file.openRead() } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return null
        }
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 64 * 1024)
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }

    private fun write(file: AtomicFile, value: String) {
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(value.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
    }

    private suspend fun <T> storage(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw ConnectionException(ConnectionProblem.STORAGE_UNAVAILABLE) }
        }
    }
}
