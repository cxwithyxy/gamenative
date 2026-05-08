package app.gamenative.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Independent download log system, decoupled from DownloadInfo.
 * Features:
 * - Per-appId log storage with batched disk writes (every 200 entries)
 * - StateFlow for reactive UI updates (throttled to every 50 entries)
 * - Clear and copy support
 */
object DownloadLogger {

    private const val LOG_DIR = ".DownloadLogs"
    private const val MAX_LOG_ENTRIES = 2000

    // Throttling: persist to disk every N entries
    private const val DISK_FLUSH_INTERVAL = 200
    // Throttling: update StateFlow every N entries
    private const val FLOW_UPDATE_INTERVAL = 50

    private val logFlows = ConcurrentHashMap<Int, MutableStateFlow<List<String>>>()
    private val logBuffers = ConcurrentHashMap<Int, MutableList<String>>()
    private val writeCounters = ConcurrentHashMap<Int, AtomicInteger>()
    private val lastPersistTime = ConcurrentHashMap<Int, AtomicLong>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun getLogFile(gameId: Int): File? {
        val dir = app.gamenative.service.SteamService.getAppDirPath(gameId)
        if (dir.isNullOrBlank()) return null
        val logDir = File(dir, LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()
        return File(logDir, "download.log")
    }

    /**
     * Get the log flow for a game. Creates it if it doesn't exist.
     * The flow will be pre-populated from persisted logs on disk.
     */
    fun getLogFlow(gameId: Int): StateFlow<List<String>> {
        return logFlows.getOrPut(gameId) {
            val persisted = loadFromDisk(gameId)
            logBuffers[gameId] = persisted.toMutableList()
            writeCounters[gameId] = AtomicInteger(0)
            lastPersistTime[gameId] = AtomicLong(System.currentTimeMillis())
            MutableStateFlow(persisted)
        }
    }

    /**
     * Append a log entry with timestamp.
     * StateFlow updates are throttled (every FLOW_UPDATE_INTERVAL entries).
     * Disk writes are batched (every DISK_FLUSH_INTERVAL entries or 10 seconds).
     */
    fun append(gameId: Int, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message"

        val buffer = logBuffers.getOrPut(gameId) {
            writeCounters[gameId] = AtomicInteger(0)
            lastPersistTime[gameId] = AtomicLong(System.currentTimeMillis())
            loadFromDisk(gameId).toMutableList()
        }

        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size > MAX_LOG_ENTRIES) {
                buffer.removeAt(0)
            }
        }

        val counter = writeCounters.getOrPut(gameId) { AtomicInteger(0) }
        val count = counter.incrementAndGet()

        // Throttled StateFlow update
        if (count % FLOW_UPDATE_INTERVAL == 0) {
            flushFlow(gameId, buffer)
        }

        // Batched disk persist
        val lastTime = lastPersistTime.getOrPut(gameId) { AtomicLong(System.currentTimeMillis()) }
        val now = System.currentTimeMillis()
        val timeSinceLastPersist = now - lastTime.get()
        if (count % DISK_FLUSH_INTERVAL == 0 || timeSinceLastPersist > 10_000L) {
            lastTime.set(now)
            persistToDiskAsync(gameId, buffer)
        }
    }

    /**
     * Force flush pending StateFlow updates and disk writes.
     * Call this when download completes or fails.
     */
    fun flush(gameId: Int) {
        val buffer = logBuffers[gameId] ?: return
        synchronized(buffer) {
            flushFlow(gameId, buffer)
        }
        persistToDiskAsync(gameId, buffer)
    }

    private fun flushFlow(gameId: Int, buffer: MutableList<String>) {
        logFlows.getOrPut(gameId) {
            MutableStateFlow(buffer.toList())
        }.value = buffer.toList()
    }

    private fun persistToDiskAsync(gameId: Int, buffer: MutableList<String>) {
        // Run disk write on a background thread
        Thread {
            try {
                val file = getLogFile(gameId) ?: return@Thread
                synchronized(buffer) {
                    file.writeText(buffer.joinToString("\n"))
                }
            } catch (_: Exception) {
                // silently ignore disk write failures
            }
        }.start()
    }

    /**
     * Clear all logs for a game.
     */
    fun clear(gameId: Int) {
        logBuffers.remove(gameId)
        writeCounters.remove(gameId)
        lastPersistTime.remove(gameId)
        logFlows[gameId]?.value = emptyList()
        deleteFromDisk(gameId)
    }

    /**
     * Get all log text as a single string (for copy).
     */
    fun getAllText(gameId: Int): String {
        val buffer = logBuffers[gameId] ?: return loadFromDisk(gameId).joinToString("\n")
        synchronized(buffer) {
            return buffer.joinToString("\n")
        }
    }

    /**
     * Get log line count.
     */
    fun getCount(gameId: Int): Int {
        return logBuffers[gameId]?.size ?: 0
    }

    // --- Persistence ---

    private fun loadFromDisk(gameId: Int): List<String> {
        return try {
            val file = getLogFile(gameId) ?: return emptyList()
            if (file.exists()) file.readLines().takeLast(MAX_LOG_ENTRIES) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun deleteFromDisk(gameId: Int) {
        try {
            val file = getLogFile(gameId) ?: return
            if (file.exists()) file.delete()
        } catch (_: Exception) {
            // silently ignore
        }
    }
}
