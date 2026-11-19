package com.example.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.engine.ImportantNotificationFilter
import com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert
import com.example.engine.ImportantNotificationFilter.ImportantNotificationType
import com.example.engine.ImportantNotificationFilter.NotificationImportanceTier
import com.example.processA.EmbeddingGemmaRuntime
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local SQLite FTS5 + Vector Indexer for Process A.
 * Uses SQLite FTS5 (`bm25` ranking) with automatic FTS4 fallback, and optional
 * EmbeddingGemma float32 vectors stored in `local_docs_meta.embedding` for hybrid retrieval.
 * Contains ONLY real on-device records (installed apps, contacts, calendar events,
 * user-imported documents, notifications, and memory notes) — zero simulated data.
 */
class LocalFtsIndexHelper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    var activeFtsMode: String = "FTS5"
        private set

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_docs_meta (
                doc_id TEXT PRIMARY KEY,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                uri_or_ref TEXT NOT NULL,
                embedding BLOB,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        ensureFtsTable(db)
        ensureNotificationArchiveTable(db)
        ensureAgenticMemoryTables(db)
    }

    private fun ensureAgenticMemoryTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agentic_memories (
                memory_id TEXT PRIMARY KEY,
                memory_type TEXT NOT NULL,
                topic TEXT NOT NULL,
                content TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.65,
                reinforcement_count INTEGER NOT NULL DEFAULT 1,
                time_bucket TEXT NOT NULL DEFAULT 'ANY',
                created_at INTEGER NOT NULL,
                last_reinforced_at INTEGER NOT NULL,
                embedding BLOB
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_agentic_mem_type_time ON agentic_memories (memory_type, time_bucket, confidence DESC)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS behavior_events (
                event_id TEXT PRIMARY KEY,
                normalized_command TEXT NOT NULL,
                tool_sequence TEXT NOT NULL,
                app_name TEXT NOT NULL DEFAULT '',
                topic TEXT NOT NULL DEFAULT '',
                time_bucket TEXT NOT NULL DEFAULT 'ANY',
                day_of_week INTEGER NOT NULL DEFAULT 0,
                success INTEGER NOT NULL DEFAULT 1,
                timestamp_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_behavior_events_time ON behavior_events (timestamp_ms DESC, time_bucket)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS learned_processes (
                process_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                trigger_phrase TEXT NOT NULL,
                steps_json TEXT NOT NULL,
                preferred_app TEXT NOT NULL DEFAULT '',
                time_bucket TEXT NOT NULL DEFAULT 'ANY',
                execution_count INTEGER NOT NULL DEFAULT 1,
                confidence REAL NOT NULL DEFAULT 0.45,
                auto_suggest INTEGER NOT NULL DEFAULT 1,
                last_executed_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_learned_proc_bucket ON learned_processes (time_bucket, confidence DESC)"
        )
    }

    private fun ensureNotificationArchiveTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notification_archive (
                alert_id TEXT PRIMARY KEY,
                notif_key TEXT NOT NULL,
                package_name TEXT NOT NULL,
                app_label TEXT NOT NULL,
                notif_type TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                importance_score INTEGER NOT NULL DEFAULT 55,
                importance_tier TEXT NOT NULL DEFAULT 'NORMAL',
                flag_reasons TEXT NOT NULL DEFAULT '',
                received_at INTEGER NOT NULL,
                is_read INTEGER NOT NULL DEFAULT 0,
                read_at INTEGER NOT NULL DEFAULT 0,
                reminded_count INTEGER NOT NULL DEFAULT 0,
                last_reminded_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_notif_archive_time_read ON notification_archive (received_at DESC, is_read ASC)"
        )
    }

    private fun ensureFtsTable(db: SQLiteDatabase) {
        val compileOptions = queryCompileOptions(db)
        val hasFts5 = compileOptions.any { it == "ENABLE_FTS5" || it.endsWith("FTS5") }
        val hasFts4 = compileOptions.isEmpty() ||
            compileOptions.any { it == "ENABLE_FTS4" || it == "ENABLE_FTS3" || it.endsWith("FTS4") || it.endsWith("FTS3") }

        if (hasFts5) {
            val createdFts5 = runCatching {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS nova_fts USING fts5(
                        doc_id UNINDEXED,
                        category,
                        title,
                        body,
                        tokenize='unicode61'
                    )
                    """.trimIndent()
                )
            }.isSuccess
            if (createdFts5) {
                activeFtsMode = "FTS5"
                return
            }
        }

        if (hasFts4) {
            val createdFts4 = runCatching {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS nova_fts USING fts4(
                        doc_id,
                        category,
                        title,
                        body
                    )
                    """.trimIndent()
                )
            }.isSuccess
            if (createdFts4) {
                activeFtsMode = "FTS4"
                return
            }
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS nova_fts (
                doc_id TEXT PRIMARY KEY,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL
            )
            """.trimIndent()
        )
        activeFtsMode = "LIKE"
    }

    private fun queryCompileOptions(db: SQLiteDatabase): Set<String> {
        val options = HashSet<String>()
        runCatching {
            db.rawQuery("PRAGMA compile_options;", null).use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.trim()?.uppercase(Locale.US)?.let { options.add(it) }
                }
            }
        }
        return options
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            runCatching { db.execSQL("ALTER TABLE local_docs_meta ADD COLUMN embedding BLOB") }
                .onFailure {
                    db.execSQL("DROP TABLE IF EXISTS nova_fts")
                    db.execSQL("DROP TABLE IF EXISTS local_docs_meta")
                    onCreate(db)
                    return
                }
        }
        if (oldVersion < 4) {
            ensureNotificationArchiveTable(db)
            migrateLegacyMessagesToArchive(db, System.currentTimeMillis())
        }
        if (oldVersion < 5) {
            ensureAgenticMemoryTables(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            var found = false
            db.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='nova_fts'",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    found = true
                    val sql = cursor.getString(0).orEmpty()
                    activeFtsMode = when {
                        sql.contains("fts5", ignoreCase = true) -> "FTS5"
                        sql.contains("fts4", ignoreCase = true) || sql.contains("fts3", ignoreCase = true) -> "FTS4"
                        else -> "LIKE"
                    }
                }
            }
            if (!found) {
                ensureFtsTable(db)
            }
            ensureNotificationArchiveTable(db)
            ensureAgenticMemoryTables(db)
        } catch (_: Exception) {
        }
    }

    private fun migrateLegacyMessagesToArchive(db: SQLiteDatabase, nowMs: Long) {
        val cutoff = nowMs - NOTIFICATION_RETENTION_MS
        runCatching {
            db.delete("local_docs_meta", "category = ? AND updated_at < ?", arrayOf("messages", cutoff.toString()))
            db.rawQuery(
                "SELECT doc_id, title, body, uri_or_ref, updated_at FROM local_docs_meta WHERE category = ? AND updated_at >= ?",
                arrayOf("messages", cutoff.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val title = cursor.getString(1)
                    val body = cursor.getString(2)
                    val uri = cursor.getString(3)
                    val updatedAt = cursor.getLong(4)
                    syncDocToNotificationArchive(db, docId, title, body, uri, updatedAt)
                }
            }
        }
    }

    fun upsertDocument(
        docId: String,
        category: String,
        title: String,
        body: String,
        uriOrRef: String,
        embedding: FloatArray? = null,
        timestampMs: Long = System.currentTimeMillis(),
        syncToNotifArchive: Boolean = true
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val meta = ContentValues().apply {
                put("doc_id", docId)
                put("category", category)
                put("title", title)
                put("body", body)
                put("uri_or_ref", uriOrRef)
                if (embedding != null && embedding.isNotEmpty()) {
                    put("embedding", floatsToBytes(embedding))
                } else {
                    putNull("embedding")
                }
                put("updated_at", timestampMs)
            }
            db.insertWithOnConflict("local_docs_meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)

            db.delete("nova_fts", "doc_id = ?", arrayOf(docId))
            val fts = ContentValues().apply {
                put("doc_id", docId)
                put("category", category)
                put("title", title)
                put("body", body)
            }
            db.insert("nova_fts", null, fts)

            if (category == "messages" && syncToNotifArchive) {
                syncDocToNotificationArchive(db, docId, title, body, uriOrRef, timestampMs)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateDocumentEmbedding(docId: String, embedding: FloatArray) {
        if (embedding.isEmpty()) return
        val db = writableDatabase
        val values = ContentValues().apply {
            put("embedding", floatsToBytes(embedding))
        }
        runCatching {
            db.update("local_docs_meta", values, "doc_id = ?", arrayOf(docId))
        }
    }

    fun deleteDocument(docId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("local_docs_meta", "doc_id = ?", arrayOf(docId))
            db.delete("nova_fts", "doc_id = ?", arrayOf(docId))
            db.delete("notification_archive", "alert_id = ?", arrayOf(docId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun countDocuments(category: String? = null): Int {
        return try {
            val sql = if (category == null) {
                "SELECT COUNT(*) FROM local_docs_meta"
            } else {
                "SELECT COUNT(*) FROM local_docs_meta WHERE category = ?"
            }
            val args = if (category == null) null else arrayOf(category)
            readableDatabase.rawQuery(sql, args).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Indexes real installed launcher apps from Android PackageManager.
     */
    fun syncInstalledApps(context: Context): Int {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        var count = 0
        apps.forEach { info ->
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isNotEmpty()) {
                upsertDocument(
                    docId = "app_$pkg",
                    category = "apps",
                    title = label,
                    body = "Installed Android application package $pkg",
                    uriOrRef = "package://$pkg"
                )
                count++
            }
        }
        return count
    }

    /**
     * Indexes real Android contacts when READ_CONTACTS permission is granted.
     */
    fun syncContacts(context: Context): Int =
        syncContactsInternal(context).also { entries ->
            entries?.forEach { (docId, title, body, uri) ->
                upsertDocument(docId, "contacts", title, body, uri)
            }
        }?.size ?: -1

    suspend fun syncContactsWithEmbeddings(
        context: Context,
        embeddingRuntime: EmbeddingGemmaRuntime?
    ): Int {
        val entries = syncContactsInternal(context) ?: return -1
        if (entries.isEmpty()) return 0
        val vectors = if (embeddingRuntime != null && embeddingRuntime.isModelFilePresent()) {
            embeddingRuntime.embedDocumentsBatch(entries.map { it.title to it.body })
        } else {
            List(entries.size) { null }
        }
        entries.forEachIndexed { idx, entry ->
            upsertDocument(
                docId = entry.docId,
                category = "contacts",
                title = entry.title,
                body = entry.body,
                uriOrRef = entry.uriOrRef,
                embedding = vectors.getOrNull(idx)
            )
        }
        return entries.size
    }

    private data class SyncEntry(
        val docId: String,
        val title: String,
        val body: String,
        val uriOrRef: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private fun syncContactsInternal(context: Context): List<SyncEntry>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val entries = mutableListOf<SyncEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (cursor.moveToNext() && count < 500) {
                    val cid = cursor.getString(idCol).orEmpty()
                    val name = cursor.getString(nameCol).orEmpty().trim()
                    val num = cursor.getString(numCol).orEmpty().trim()
                    if (name.isNotEmpty() && num.isNotEmpty()) {
                        entries.add(
                            SyncEntry(
                                docId = "contact_${cid}_${count}",
                                title = name,
                                body = "Phone: $num",
                                uriOrRef = "tel:$num"
                            )
                        )
                        count++
                    }
                }
            }
        }.onFailure { Log.w(TAG, "syncContacts failed: ${it.message}") }
        return entries
    }

    /**
     * Indexes real upcoming Android Calendar events when READ_CALENDAR permission is granted.
     */
    fun syncCalendarEvents(context: Context): Int =
        syncCalendarInternal(context).also { entries ->
            entries?.forEach { entry ->
                upsertDocument(
                    docId = entry.docId,
                    category = "calendar",
                    title = entry.title,
                    body = entry.body,
                    uriOrRef = entry.uriOrRef,
                    timestampMs = entry.timestampMs
                )
            }
        }?.size ?: -1

    suspend fun syncCalendarEventsWithEmbeddings(
        context: Context,
        embeddingRuntime: EmbeddingGemmaRuntime?
    ): Int {
        val entries = syncCalendarInternal(context) ?: return -1
        if (entries.isEmpty()) return 0
        val vectors = if (embeddingRuntime != null && embeddingRuntime.isModelFilePresent()) {
            embeddingRuntime.embedDocumentsBatch(entries.map { it.title to it.body })
        } else {
            List(entries.size) { null }
        }
        entries.forEachIndexed { idx, entry ->
            upsertDocument(
                docId = entry.docId,
                category = "calendar",
                title = entry.title,
                body = entry.body,
                uriOrRef = entry.uriOrRef,
                embedding = vectors.getOrNull(idx),
                timestampMs = entry.timestampMs
            )
        }
        return entries.size
    }

    private fun syncCalendarInternal(context: Context): List<SyncEntry>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val entries = mutableListOf<SyncEntry>()
        val now = System.currentTimeMillis()
        val windowStart = now - WEEK_MS
        val windowEnd = now + NOTIFICATION_RETENTION_MS
        val fmt = SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault())
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val args = arrayOf(windowStart.toString(), windowEnd.toString())
        runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val descCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
                val locCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
                val startCol = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                var count = 0
                while (cursor.moveToNext() && count < 100) {
                    val eid = cursor.getString(idCol).orEmpty()
                    val title = cursor.getString(titleCol).orEmpty().trim().ifEmpty { "Calendar Event" }
                    val desc = cursor.getString(descCol).orEmpty().trim()
                    val loc = cursor.getString(locCol).orEmpty().trim()
                    val dtStart = cursor.getLong(startCol)
                    val timeLabel = fmt.format(Date(dtStart))
                    val body = listOfNotNull(
                        timeLabel,
                        loc.takeIf { it.isNotEmpty() },
                        desc.takeIf { it.isNotEmpty() }?.take(180)
                    ).joinToString(" • ")
                    entries.add(
                        SyncEntry(
                            docId = "cal_$eid",
                            title = title,
                            body = body,
                            uriOrRef = "content://com.android.calendar/events/$eid",
                            timestampMs = dtStart
                        )
                    )
                    count++
                }
            }
        }.onFailure { Log.w(TAG, "syncCalendarEvents failed: ${it.message}") }
        return entries
    }

    /**
     * Generates EmbeddingGemma vectors for existing indexed rows whose `embedding` column is NULL
     * (e.g., records indexed before the EmbeddingGemma model was downloaded).
     */
    suspend fun backfillMissingEmbeddings(
        embeddingRuntime: EmbeddingGemmaRuntime,
        limit: Int = 150
    ): Int {
        if (!embeddingRuntime.isModelFilePresent()) return 0
        val pending = mutableListOf<Triple<String, String, String>>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT doc_id, title, body FROM local_docs_meta WHERE embedding IS NULL ORDER BY updated_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    pending.add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
        if (pending.isEmpty()) return 0
        val vectors = embeddingRuntime.embedDocumentsBatch(pending.map { it.second to it.third })
        var updated = 0
        pending.forEachIndexed { idx, (docId, _, _) ->
            val vec = vectors.getOrNull(idx)
            if (vec != null && vec.isNotEmpty()) {
                updateDocumentEmbedding(docId, vec)
                updated++
            }
        }
        return updated
    }

    /**
     * Indexes a user-picked document URI (SAF OpenDocument) without broad storage permissions.
     * Supports plain text/markdown/CSV/JSON and PDF metadata/page count via PdfRenderer.
     */
    fun indexDocumentFromUri(
        context: Context,
        uri: Uri,
        displayName: String?,
        embedding: FloatArray? = null
    ): LocalIndexDocument? {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment ?: "Document"
        val mime = context.contentResolver.getType(uri).orEmpty()
        val bodyText = when {
            mime.contains("pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true) -> {
                var pageCount = 0
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { renderer -> pageCount = renderer.pageCount }
                    }
                }
                "PDF document ($pageCount pages) • $name"
            }
            else -> {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        reader.readText().take(2000).trim()
                    }
                }.getOrNull().orEmpty().ifEmpty { "Local file $name ($mime)" }
            }
        }
        val docId = "file_${uri.toString().hashCode().toUInt()}"
        upsertDocument(
            docId = docId,
            category = "files",
            title = name,
            body = bodyText,
            uriOrRef = uri.toString(),
            embedding = embedding
        )
        return LocalIndexDocument(docId, "files", name, bodyText, uri.toString(), 1.0)
    }

    fun getDocumentsByCategory(category: String, limit: Int = 20): List<LocalIndexDocument> {
        val results = mutableListOf<LocalIndexDocument>()
        val sql = if (category == "all") {
            "SELECT doc_id, category, title, body, uri_or_ref FROM local_docs_meta ORDER BY updated_at DESC LIMIT ?"
        } else {
            "SELECT doc_id, category, title, body, uri_or_ref FROM local_docs_meta WHERE category = ? ORDER BY updated_at DESC LIMIT ?"
        }
        val args = if (category == "all") arrayOf(limit.toString()) else arrayOf(category, limit.toString())
        readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    LocalIndexDocument(
                        docId = cursor.getString(0),
                        category = cursor.getString(1),
                        title = cursor.getString(2),
                        body = cursor.getString(3),
                        uriOrRef = cursor.getString(4),
                        score = 1.0
                    )
                )
            }
        }
        return results
    }

    fun queryDocumentsByTimeRange(
        category: String,
        sinceMs: Long,
        untilMs: Long = Long.MAX_VALUE,
        ascending: Boolean = true,
        limit: Int = 20
    ): List<LocalIndexDocument> {
        val results = mutableListOf<LocalIndexDocument>()
        val dir = if (ascending) "ASC" else "DESC"
        val sql = """
            SELECT doc_id, category, title, body, uri_or_ref, updated_at
            FROM local_docs_meta
            WHERE category = ? AND updated_at >= ? AND updated_at <= ?
            ORDER BY updated_at $dir
            LIMIT ?
        """.trimIndent()
        readableDatabase.rawQuery(
            sql,
            arrayOf(category, sinceMs.toString(), untilMs.toString(), limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    LocalIndexDocument(
                        docId = cursor.getString(0),
                        category = cursor.getString(1),
                        title = cursor.getString(2),
                        body = cursor.getString(3),
                        uriOrRef = cursor.getString(4),
                        score = cursor.getLong(5).toDouble()
                    )
                )
            }
        }
        return results
    }

    data class ArchivedNotificationRecord(
        val alertId: String,
        val notifKey: String,
        val packageName: String,
        val appLabel: String,
        val notifType: ImportantNotificationType,
        val title: String,
        val body: String,
        val importanceScore: Int,
        val importanceTier: NotificationImportanceTier,
        val flagReasons: String,
        val receivedAt: Long,
        val isRead: Boolean,
        val readAt: Long,
        val remindedCount: Int,
        val lastRemindedAt: Long
    )

    data class NotificationAuditSnapshot(
        val totalSaved14Days: Int,
        val unreadTotal: Int,
        val unreadToday: Int,
        val readToday: Int,
        val totalToday: Int,
        val last24hTotal: Int,
        val last24hUnread: Int,
        val last24hHighOrCritical: Int,
        val weekTotal: Int,
        val weekUnread: Int,
        val weekHighOrCritical: Int,
        val missedCallsWeek: Int,
        val remindersWeek: Int,
        val carryOverImportant: List<ArchivedNotificationRecord>
    )

    fun upsertNotificationArchive(
        alert: ImportantNotificationAlert,
        markRead: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        indexFts: Boolean = true
    ) {
        pruneExpiredNotifications(nowMs)
        val effectiveTime = alert.timestampMs.takeIf { it > 0L } ?: nowMs
        if (effectiveTime < nowMs - NOTIFICATION_RETENTION_MS) return

        val cleanTitle = ImportantNotificationFilter.sanitizeNotificationText(alert.title, alert.appLabel, alert.packageName)
            .ifEmpty { alert.appLabel }
        val cleanBody = ImportantNotificationFilter.sanitizeNotificationText(alert.body, alert.appLabel, alert.packageName)
            .take(180)
        val typeTag = "[${alert.type.label}]"
        val cleanShort = alert.shortLabel.removePrefix("${alert.type.label} • ").trim()
        val ftsTitle = ImportantNotificationFilter.sanitizeNotificationText("$typeTag $cleanShort", alert.appLabel, alert.packageName)
        val ftsBody = cleanBody.ifEmpty { cleanTitle }

        val db = writableDatabase
        val supersededIds = mutableListOf<String>()
        db.beginTransaction()
        try {
            var existingFound = false
            var existingIsRead = false
            var existingReadAt = 0L
            var existingReceivedAt = effectiveTime
            var existingBody = ""
            var existingRemindedCount = 0
            var existingLastRemindedAt = 0L

            db.rawQuery(
                "SELECT is_read, read_at, received_at, body, reminded_count, last_reminded_at FROM notification_archive WHERE alert_id = ?",
                arrayOf(alert.id)
            ).use { c ->
                if (c.moveToFirst()) {
                    existingFound = true
                    existingIsRead = c.getInt(0) == 1
                    existingReadAt = c.getLong(1)
                    existingReceivedAt = c.getLong(2)
                    existingBody = c.getString(3).orEmpty()
                    existingRemindedCount = c.getInt(4)
                    existingLastRemindedAt = c.getLong(5)
                }
            }

            // Deduplicate in-place updates for the SAME live status-bar notification key
            // (e.g., Messenger/WhatsApp/Reminder updating "2 new messages" -> "3 new messages"
            // under the same sbn.key must NOT create 100 duplicate rows in notification_archive!)
            if (alert.key.isNotBlank() && alert.key != alert.id) {
                db.rawQuery(
                    "SELECT alert_id, is_read, read_at, received_at, body, reminded_count, last_reminded_at FROM notification_archive WHERE notif_key = ? AND alert_id != ? ORDER BY received_at DESC",
                    arrayOf(alert.key, alert.id)
                ).use { c ->
                    while (c.moveToNext()) {
                        val oldId = c.getString(0)
                        supersededIds.add(oldId)
                        if (!existingFound) {
                            existingFound = true
                            existingIsRead = c.getInt(1) == 1
                            existingReadAt = c.getLong(2)
                            existingReceivedAt = c.getLong(3)
                            existingBody = c.getString(4).orEmpty()
                            existingRemindedCount = c.getInt(5)
                            existingLastRemindedAt = c.getLong(6)
                        }
                    }
                }
                supersededIds.forEach { oldId ->
                    db.delete("notification_archive", "alert_id = ?", arrayOf(oldId))
                    db.delete("local_docs_meta", "doc_id = ?", arrayOf(oldId))
                    db.delete("nova_fts", "doc_id = ?", arrayOf(oldId))
                }
            }

            val isNewerUpdate = !existingFound ||
                effectiveTime > existingReceivedAt + 1500L ||
                (cleanBody.isNotEmpty() && cleanBody != existingBody && effectiveTime >= existingReceivedAt)

            val resolvedRead = when {
                markRead -> true
                isNewerUpdate -> false
                else -> existingIsRead
            }
            val resolvedReadAt = when {
                markRead -> if (existingReadAt > 0L) existingReadAt else nowMs
                isNewerUpdate -> 0L
                else -> existingReadAt
            }
            val resolvedReceived = if (isNewerUpdate) effectiveTime else existingReceivedAt

            val cv = ContentValues().apply {
                put("alert_id", alert.id)
                put("notif_key", alert.key)
                put("package_name", alert.packageName)
                put("app_label", alert.appLabel)
                put("notif_type", alert.type.name)
                put("title", cleanTitle)
                put("body", cleanBody)
                put("importance_score", alert.importanceScore)
                put("importance_tier", alert.importanceTier.name)
                put("flag_reasons", alert.flagReasons.joinToString(", "))
                put("received_at", resolvedReceived)
                put("is_read", if (resolvedRead) 1 else 0)
                put("read_at", resolvedReadAt)
                put("reminded_count", existingRemindedCount)
                put("last_reminded_at", existingLastRemindedAt)
            }
            db.insertWithOnConflict("notification_archive", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (indexFts) {
            upsertDocument(
                docId = alert.id,
                category = "messages",
                title = ftsTitle,
                body = ftsBody,
                uriOrRef = "notif://${alert.packageName}/${alert.id}",
                timestampMs = effectiveTime,
                syncToNotifArchive = false
            )
        }
    }

    private fun syncDocToNotificationArchive(
        db: SQLiteDatabase,
        docId: String,
        rawTitle: String,
        rawBody: String,
        uriOrRef: String,
        timestampMs: Long
    ) {
        val pkgFromUri = if (uriOrRef.startsWith("notif://")) {
            uriOrRef.removePrefix("notif://").substringBefore('/').trim()
        } else ""
        val appLabel = if (pkgFromUri.isNotEmpty()) {
            ImportantNotificationFilter.resolveFriendlyAppName("", pkgFromUri)
        } else if (uriOrRef.startsWith("nova://reminder")) {
            "Nova Reminders"
        } else {
            "Notifications"
        }
        val cleanTitle = ImportantNotificationFilter.sanitizeNotificationText(rawTitle, appLabel, pkgFromUri)
            .replace(Regex("^\\[(Message|Missed Call|Reminder)]\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { appLabel }
        val cleanBody = ImportantNotificationFilter.sanitizeNotificationText(rawBody, appLabel, pkgFromUri).take(180)
        val type = ImportantNotificationFilter.inferTypeFromTitleOrUri(rawTitle, cleanBody, uriOrRef)
        val importance = ImportantNotificationFilter.assessImportance(
            type = type,
            title = cleanTitle,
            body = cleanBody,
            appLabel = appLabel,
            hasMessagingMessages = type == ImportantNotificationType.MESSAGE
        )

        var existingFound = false
        var existingIsRead = false
        var existingReadAt = 0L
        var existingReceivedAt = timestampMs
        var existingBody = ""
        var existingReminded = 0
        var existingLastReminded = 0L
        db.rawQuery(
            "SELECT is_read, read_at, received_at, body, reminded_count, last_reminded_at FROM notification_archive WHERE alert_id = ?",
            arrayOf(docId)
        ).use { c ->
            if (c.moveToFirst()) {
                existingFound = true
                existingIsRead = c.getInt(0) == 1
                existingReadAt = c.getLong(1)
                existingReceivedAt = c.getLong(2)
                existingBody = c.getString(3).orEmpty()
                existingReminded = c.getInt(4)
                existingLastReminded = c.getLong(5)
            }
        }

        val isNewerUpdate = !existingFound ||
            timestampMs > existingReceivedAt + 1500L ||
            (cleanBody.isNotEmpty() && cleanBody != existingBody && timestampMs >= existingReceivedAt)

        val cv = ContentValues().apply {
            put("alert_id", docId)
            put("notif_key", docId)
            put("package_name", pkgFromUri)
            put("app_label", appLabel)
            put("notif_type", type.name)
            put("title", cleanTitle)
            put("body", cleanBody)
            put("importance_score", importance.score)
            put("importance_tier", importance.tier.name)
            put("flag_reasons", importance.flagReasons.joinToString(", "))
            put("received_at", if (isNewerUpdate) timestampMs else existingReceivedAt)
            put("is_read", if (!isNewerUpdate && existingIsRead) 1 else 0)
            put("read_at", if (!isNewerUpdate) existingReadAt else 0L)
            put("reminded_count", existingReminded)
            put("last_reminded_at", existingLastReminded)
        }
        db.insertWithOnConflict("notification_archive", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun pruneExpiredNotifications(nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMs - NOTIFICATION_RETENTION_MS
        var deleted = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            deleted = db.delete("notification_archive", "received_at < ?", arrayOf(cutoff.toString()))
            val expiredIds = mutableListOf<String>()
            db.rawQuery(
                "SELECT doc_id FROM local_docs_meta WHERE category = 'messages' AND updated_at < ?",
                arrayOf(cutoff.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    expiredIds.add(c.getString(0))
                }
            }
            if (expiredIds.isNotEmpty()) {
                db.delete("local_docs_meta", "category = 'messages' AND updated_at < ?", arrayOf(cutoff.toString()))
                expiredIds.forEach { id ->
                    db.delete("nova_fts", "doc_id = ?", arrayOf(id))
                }
            }
            deleted += deduplicateNotificationArchiveInternal(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return deleted
    }

    /**
     * Collapses duplicate archived rows that share the same Android status-bar `notif_key`
     * (or identical package + title + body within 10 minutes), keeping the newest row.
     */
    private fun deduplicateNotificationArchiveInternal(db: SQLiteDatabase): Int {
        val seenKeys = HashSet<String>()
        val seenContentBuckets = HashSet<String>()
        val duplicateIds = mutableListOf<String>()
        db.rawQuery(
            "SELECT alert_id, notif_key, package_name, title, body, received_at FROM notification_archive ORDER BY received_at DESC, rowid DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val alertId = c.getString(0).orEmpty()
                val notifKey = c.getString(1).orEmpty().trim()
                val pkg = c.getString(2).orEmpty().lowercase()
                val title = c.getString(3).orEmpty().trim().lowercase()
                val body = c.getString(4).orEmpty().trim().lowercase()
                val ts = c.getLong(5)
                val timeBucket = ts / (10L * 60_000L)

                val keyIsDuplicate = notifKey.isNotEmpty() && notifKey != alertId && !seenKeys.add(notifKey)
                val contentSig = "$pkg|$title|$body|$timeBucket"
                val contentIsDuplicate = title.isNotEmpty() && !seenContentBuckets.add(contentSig)
                if (keyIsDuplicate || contentIsDuplicate) {
                    duplicateIds.add(alertId)
                }
            }
        }
        if (duplicateIds.isEmpty()) return 0
        duplicateIds.forEach { dupId ->
            db.delete("notification_archive", "alert_id = ?", arrayOf(dupId))
            db.delete("local_docs_meta", "doc_id = ?", arrayOf(dupId))
            db.delete("nova_fts", "doc_id = ?", arrayOf(dupId))
        }
        return duplicateIds.size
    }

    fun markNotificationsRead(alertIds: Collection<String>, nowMs: Long = System.currentTimeMillis()): Int {
        if (alertIds.isEmpty()) return 0
        var updated = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            alertIds.distinct().forEach { id ->
                val cv = ContentValues().apply {
                    put("is_read", 1)
                    put("read_at", nowMs)
                }
                updated += db.update(
                    "notification_archive",
                    cv,
                    "alert_id = ? AND is_read = 0",
                    arrayOf(id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    fun markNotificationsReadByKey(notifKey: String?, nowMs: Long = System.currentTimeMillis()): Int {
        val key = notifKey?.trim().orEmpty()
        if (key.isEmpty()) return 0
        val cv = ContentValues().apply {
            put("is_read", 1)
            put("read_at", nowMs)
        }
        return writableDatabase.update(
            "notification_archive",
            cv,
            "notif_key = ? AND is_read = 0",
            arrayOf(key)
        )
    }

    fun markAllNotificationsRead(nowMs: Long = System.currentTimeMillis()): Int {
        val cv = ContentValues().apply {
            put("is_read", 1)
            put("read_at", nowMs)
        }
        return writableDatabase.update("notification_archive", cv, "is_read = 0", null)
    }

    fun markNotificationsReminded(alertIds: Collection<String>, nowMs: Long = System.currentTimeMillis()): Int {
        if (alertIds.isEmpty()) return 0
        var updated = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            alertIds.distinct().forEach { id ->
                db.execSQL(
                    "UPDATE notification_archive SET reminded_count = reminded_count + 1, last_reminded_at = ? WHERE alert_id = ?",
                    arrayOf<Any>(nowMs, id)
                )
                updated++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    fun queryArchivedNotifications(
        sinceMs: Long = 0L,
        untilMs: Long = Long.MAX_VALUE,
        unreadOnly: Boolean = false,
        minTier: NotificationImportanceTier? = null,
        prioritizeUnread: Boolean = true,
        limit: Int = 25
    ): List<ArchivedNotificationRecord> {
        val whereParts = mutableListOf("received_at >= ?", "received_at <= ?")
        val args = mutableListOf(sinceMs.toString(), untilMs.toString())
        if (unreadOnly) {
            whereParts.add("is_read = 0")
        }
        when (minTier) {
            NotificationImportanceTier.CRITICAL -> whereParts.add("importance_tier = 'CRITICAL'")
            NotificationImportanceTier.HIGH -> whereParts.add("importance_tier IN ('CRITICAL', 'HIGH')")
            else -> Unit
        }
        val orderClause = if (prioritizeUnread) {
            "is_read ASC, received_at DESC, importance_score DESC"
        } else {
            "received_at DESC, importance_score DESC"
        }
        val sql = """
            SELECT alert_id, notif_key, package_name, app_label, notif_type, title, body,
                   importance_score, importance_tier, flag_reasons, received_at,
                   is_read, read_at, reminded_count, last_reminded_at
            FROM notification_archive
            WHERE ${whereParts.joinToString(" AND ")}
            ORDER BY $orderClause
            LIMIT ?
        """.trimIndent()
        args.add(limit.toString())
        val out = mutableListOf<ArchivedNotificationRecord>()
        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(readArchivedNotificationCursor(c))
            }
        }
        return out
    }

    /**
     * Returns a map of `alert_id -> isRead` for all saved notifications currently in the 14-day archive
     * so live active notifications can be reconciled against what is already saved.
     */
    fun getArchivedNotificationStateMap(): Map<String, Boolean> {
        val state = LinkedHashMap<String, Boolean>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT alert_id, is_read FROM notification_archive",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    state[c.getString(0)] = c.getInt(1) == 1
                }
            }
        }
        return state
    }

    /**
     * Fetches archived notification records matching a specific set of live alert IDs,
     * preserving newest-first order.
     */
    fun getArchivedNotificationsByIds(alertIds: Collection<String>): List<ArchivedNotificationRecord> {
        val distinct = alertIds.distinct().filter { it.isNotBlank() }
        if (distinct.isEmpty()) return emptyList()
        val placeholders = distinct.joinToString(",") { "?" }
        val sql = """
            SELECT alert_id, notif_key, package_name, app_label, notif_type, title, body,
                   importance_score, importance_tier, flag_reasons, received_at,
                   is_read, read_at, reminded_count, last_reminded_at
            FROM notification_archive
            WHERE alert_id IN ($placeholders)
            ORDER BY is_read ASC, received_at DESC, importance_score DESC
        """.trimIndent()
        val out = mutableListOf<ArchivedNotificationRecord>()
        runCatching {
            readableDatabase.rawQuery(sql, distinct.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    out.add(readArchivedNotificationCursor(c))
                }
            }
        }
        return out
    }

    fun getImportantCarryOverReminders(
        nowMs: Long = System.currentTimeMillis(),
        olderThanMs: Long = ImportantNotificationFilter.startOfDayMillis(nowMs),
        cooldownMs: Long = 8L * 3600_000L,
        limit: Int = 3
    ): List<ArchivedNotificationRecord> {
        val retentionStart = nowMs - NOTIFICATION_RETENTION_MS
        val remindCutoff = nowMs - cooldownMs
        val sql = """
            SELECT alert_id, notif_key, package_name, app_label, notif_type, title, body,
                   importance_score, importance_tier, flag_reasons, received_at,
                   is_read, read_at, reminded_count, last_reminded_at
            FROM notification_archive
            WHERE received_at >= ?
              AND received_at < ?
              AND is_read = 0
              AND (importance_tier IN ('CRITICAL', 'HIGH') OR notif_type IN ('MISSED_CALL', 'REMINDER'))
              AND (last_reminded_at = 0 OR last_reminded_at <= ?)
            ORDER BY importance_score DESC, received_at DESC
            LIMIT ?
        """.trimIndent()
        val out = mutableListOf<ArchivedNotificationRecord>()
        readableDatabase.rawQuery(
            sql,
            arrayOf(retentionStart.toString(), olderThanMs.toString(), remindCutoff.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(readArchivedNotificationCursor(c))
            }
        }
        return out
    }

    fun countUnreadNotifications(sinceMs: Long = 0L): Int {
        return try {
            val effectiveSince = maxOf(sinceMs, System.currentTimeMillis() - NOTIFICATION_RETENTION_MS)
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM notification_archive WHERE is_read = 0 AND received_at >= ?",
                arrayOf(effectiveSince.toString())
            ).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    fun runBackgroundNotificationAudit(
        nowMs: Long = System.currentTimeMillis(),
        carryOverCooldownMs: Long = 8L * 3600_000L
    ): NotificationAuditSnapshot {
        pruneExpiredNotifications(nowMs)
        val retentionStart = nowMs - NOTIFICATION_RETENTION_MS
        val todayStart = ImportantNotificationFilter.startOfDayMillis(nowMs)
        val last24hStart = nowMs - DAY_MS
        val weekStart = nowMs - WEEK_MS

        var total14 = 0
        var unreadTotal = 0
        var unreadToday = 0
        var readToday = 0
        var totalToday = 0
        var last24hTotal = 0
        var last24hUnread = 0
        var last24hHigh = 0
        var weekTotal = 0
        var weekUnread = 0
        var weekHigh = 0
        var missedCallsWeek = 0
        var remindersWeek = 0

        readableDatabase.rawQuery(
            "SELECT notif_type, importance_tier, received_at, is_read FROM notification_archive WHERE received_at >= ?",
            arrayOf(retentionStart.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val typeStr = c.getString(0).orEmpty()
                val tierStr = c.getString(1).orEmpty()
                val ts = c.getLong(2)
                val isRead = c.getInt(3) == 1
                val isHighOrCrit = tierStr == NotificationImportanceTier.CRITICAL.name ||
                    tierStr == NotificationImportanceTier.HIGH.name

                total14++
                if (!isRead) unreadTotal++

                if (ts >= todayStart) {
                    totalToday++
                    if (isRead) readToday++ else unreadToday++
                }
                if (ts >= last24hStart) {
                    last24hTotal++
                    if (!isRead) last24hUnread++
                    if (isHighOrCrit) last24hHigh++
                }
                if (ts >= weekStart) {
                    weekTotal++
                    if (!isRead) weekUnread++
                    if (isHighOrCrit) weekHigh++
                    if (typeStr == ImportantNotificationType.MISSED_CALL.name) missedCallsWeek++
                    if (typeStr == ImportantNotificationType.REMINDER.name) remindersWeek++
                }
            }
        }

        val carryOver = getImportantCarryOverReminders(
            nowMs = nowMs,
            olderThanMs = todayStart,
            cooldownMs = carryOverCooldownMs,
            limit = 3
        )

        return NotificationAuditSnapshot(
            totalSaved14Days = total14,
            unreadTotal = unreadTotal,
            unreadToday = unreadToday,
            readToday = readToday,
            totalToday = totalToday,
            last24hTotal = last24hTotal,
            last24hUnread = last24hUnread,
            last24hHighOrCritical = last24hHigh,
            weekTotal = weekTotal,
            weekUnread = weekUnread,
            weekHighOrCritical = weekHigh,
            missedCallsWeek = missedCallsWeek,
            remindersWeek = remindersWeek,
            carryOverImportant = carryOver
        )
    }

    private fun readArchivedNotificationCursor(c: android.database.Cursor): ArchivedNotificationRecord {
        val type = runCatching { ImportantNotificationType.valueOf(c.getString(4)) }
            .getOrDefault(ImportantNotificationType.MESSAGE)
        val tier = runCatching { NotificationImportanceTier.valueOf(c.getString(8)) }
            .getOrDefault(NotificationImportanceTier.NORMAL)
        return ArchivedNotificationRecord(
            alertId = c.getString(0),
            notifKey = c.getString(1),
            packageName = c.getString(2),
            appLabel = c.getString(3),
            notifType = type,
            title = c.getString(5),
            body = c.getString(6),
            importanceScore = c.getInt(7),
            importanceTier = tier,
            flagReasons = c.getString(9).orEmpty(),
            receivedAt = c.getLong(10),
            isRead = c.getInt(11) == 1,
            readAt = c.getLong(12),
            remindedCount = c.getInt(13),
            lastRemindedAt = c.getLong(14)
        )
    }

    fun search(query: String, category: String = "all", limit: Int = 8): List<LocalIndexDocument> {
        val sanitizedTokens = query
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .distinct()

        if (sanitizedTokens.isEmpty()) {
            return getDocumentsByCategory(category, limit)
        }

        val db = readableDatabase
        if (activeFtsMode == "LIKE") {
            return searchWithLike(db, sanitizedTokens.first(), category, limit)
        }

        val ftsMatchExpr = sanitizedTokens.joinToString(" OR ") { "$it*" }
        val results = mutableListOf<LocalIndexDocument>()

        try {
            val sql = if (activeFtsMode == "FTS5") {
                if (category == "all") {
                    """
                    SELECT m.doc_id, m.category, m.title, m.body, m.uri_or_ref, bm25(nova_fts) AS rank_score
                    FROM nova_fts
                    JOIN local_docs_meta m ON m.doc_id = nova_fts.doc_id
                    WHERE nova_fts MATCH ?
                    ORDER BY rank_score ASC
                    LIMIT ?
                    """.trimIndent()
                } else {
                    """
                    SELECT m.doc_id, m.category, m.title, m.body, m.uri_or_ref, bm25(nova_fts) AS rank_score
                    FROM nova_fts
                    JOIN local_docs_meta m ON m.doc_id = nova_fts.doc_id
                    WHERE nova_fts MATCH ? AND m.category = ?
                    ORDER BY rank_score ASC
                    LIMIT ?
                    """.trimIndent()
                }
            } else {
                if (category == "all") {
                    """
                    SELECT m.doc_id, m.category, m.title, m.body, m.uri_or_ref, 0.0 AS rank_score
                    FROM nova_fts
                    JOIN local_docs_meta m ON m.doc_id = nova_fts.doc_id
                    WHERE nova_fts MATCH ?
                    LIMIT ?
                    """.trimIndent()
                } else {
                    """
                    SELECT m.doc_id, m.category, m.title, m.body, m.uri_or_ref, 0.0 AS rank_score
                    FROM nova_fts
                    JOIN local_docs_meta m ON m.doc_id = nova_fts.doc_id
                    WHERE nova_fts MATCH ? AND m.category = ?
                    LIMIT ?
                    """.trimIndent()
                }
            }

            val args = if (category == "all") {
                arrayOf(ftsMatchExpr, limit.toString())
            } else {
                arrayOf(ftsMatchExpr, category, limit.toString())
            }

            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    results.add(
                        LocalIndexDocument(
                            docId = cursor.getString(0),
                            category = cursor.getString(1),
                            title = cursor.getString(2),
                            body = cursor.getString(3),
                            uriOrRef = cursor.getString(4),
                            score = cursor.getDouble(5)
                        )
                    )
                }
            }
        } catch (ex: SQLiteException) {
            Log.w(TAG, "FTS MATCH fallback to LIKE scan", ex)
            return searchWithLike(db, sanitizedTokens.first(), category, limit)
        }
        return results
    }

    private fun searchWithLike(
        db: SQLiteDatabase,
        token: String,
        category: String,
        limit: Int
    ): List<LocalIndexDocument> {
        val likeArg = "%$token%"
        val results = mutableListOf<LocalIndexDocument>()
        val sql = if (category == "all") {
            "SELECT doc_id, category, title, body, uri_or_ref FROM local_docs_meta WHERE (title LIKE ? OR body LIKE ?) LIMIT ?"
        } else {
            "SELECT doc_id, category, title, body, uri_or_ref FROM local_docs_meta WHERE category = ? AND (title LIKE ? OR body LIKE ?) LIMIT ?"
        }
        val args = if (category == "all") {
            arrayOf(likeArg, likeArg, limit.toString())
        } else {
            arrayOf(category, likeArg, likeArg, limit.toString())
        }
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    LocalIndexDocument(
                        docId = cursor.getString(0),
                        category = cursor.getString(1),
                        title = cursor.getString(2),
                        body = cursor.getString(3),
                        uriOrRef = cursor.getString(4),
                        score = 0.5
                    )
                )
            }
        }
        return results
    }

    /**
     * Hybrid search combining FTS5 lexical matches with EmbeddingGemma 2 vector cosine similarity
     * when a query vector and stored document embeddings are available.
     */
    fun hybridSearch(
        query: String,
        queryEmbedding: FloatArray?,
        category: String = "all",
        limit: Int = 8
    ): List<LocalIndexDocument> {
        val lexical = search(query, category, limit * 2)
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            return lexical.take(limit)
        }
        val scoredMap = LinkedHashMap<String, LocalIndexDocument>()
        lexical.forEachIndexed { idx, doc ->
            val ftsNorm = 1.0 / (1.0 + idx * 0.25)
            scoredMap[doc.docId] = doc.copy(score = ftsNorm * 0.45)
        }

        val sql = if (category == "all") {
            "SELECT doc_id, category, title, body, uri_or_ref, embedding FROM local_docs_meta WHERE embedding IS NOT NULL"
        } else {
            "SELECT doc_id, category, title, body, uri_or_ref, embedding FROM local_docs_meta WHERE category = ? AND embedding IS NOT NULL"
        }
        val args = if (category == "all") null else arrayOf(category)
        readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val vec = bytesToFloats(cursor.getBlob(5))
                val sim = EmbeddingGemmaRuntime.cosineSimilarity(queryEmbedding, vec).toDouble().coerceAtLeast(0.0)
                if (sim > 0.25 || scoredMap.containsKey(id)) {
                    val existing = scoredMap[id]
                    val combined = (existing?.score ?: 0.0) + sim * 0.55
                    scoredMap[id] = LocalIndexDocument(
                        docId = id,
                        category = cursor.getString(1),
                        title = cursor.getString(2),
                        body = cursor.getString(3),
                        uriOrRef = cursor.getString(4),
                        score = combined
                    )
                }
            }
        }
        return scoredMap.values.sortedByDescending { it.score }.take(limit)
    }

    // -------------------------------------------------------------------------
    // Agentic Memory, Behavior Events & Learned Processes (DB v5)
    // -------------------------------------------------------------------------

    fun upsertAgenticMemory(
        memoryId: String,
        memoryType: AgenticMemoryType,
        topic: String,
        content: String,
        confidence: Float = 0.70f,
        timeBucket: TimeOfDayBucket = TimeOfDayBucket.ANY,
        embedding: FloatArray? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var existingCount = 0
            var existingConf = 0f
            var existingCreatedAt = nowMs
            db.rawQuery(
                "SELECT reinforcement_count, confidence, created_at FROM agentic_memories WHERE memory_id = ? LIMIT 1",
                arrayOf(memoryId)
            ).use { c ->
                if (c.moveToFirst()) {
                    existingCount = c.getInt(0)
                    existingConf = c.getFloat(1)
                    existingCreatedAt = c.getLong(2)
                }
            }

            val nextCount = if (existingCount > 0) existingCount + 1 else 1
            val boostedConf = if (existingCount > 0) {
                kotlin.math.min(0.99f, kotlin.math.max(confidence, existingConf + 0.08f))
            } else {
                confidence.coerceIn(0.10f, 0.99f)
            }

            val cv = ContentValues().apply {
                put("memory_id", memoryId)
                put("memory_type", memoryType.name)
                put("topic", topic)
                put("content", content)
                put("confidence", boostedConf)
                put("reinforcement_count", nextCount)
                put("time_bucket", timeBucket.name)
                put("created_at", existingCreatedAt)
                put("last_reinforced_at", nowMs)
                if (embedding != null && embedding.isNotEmpty()) {
                    put("embedding", floatsToBytes(embedding))
                }
            }
            db.insertWithOnConflict("agentic_memories", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

            // Trim older EPISODE rows beyond the most recent 24 to keep prompt memory fresh
            if (memoryType == AgenticMemoryType.EPISODE) {
                db.execSQL(
                    """
                    DELETE FROM agentic_memories
                    WHERE memory_type = 'EPISODE'
                      AND memory_id NOT IN (
                        SELECT memory_id FROM agentic_memories
                        WHERE memory_type = 'EPISODE'
                        ORDER BY last_reinforced_at DESC
                        LIMIT 24
                      )
                    """.trimIndent()
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAgenticMemoryByTopic(topic: String): AgenticMemoryEntry? {
        readableDatabase.rawQuery(
            """
            SELECT memory_id, memory_type, topic, content, confidence, reinforcement_count, time_bucket, created_at, last_reinforced_at
            FROM agentic_memories
            WHERE topic = ?
            ORDER BY confidence DESC, last_reinforced_at DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(topic)
        ).use { c ->
            if (c.moveToFirst()) return readAgenticMemoryRow(c)
        }
        return null
    }

    fun getAgenticMemories(
        types: Set<AgenticMemoryType>? = null,
        timeBucket: TimeOfDayBucket? = null,
        limit: Int = 25
    ): List<AgenticMemoryEntry> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!types.isNullOrEmpty()) {
            val placeholders = types.joinToString(",") { "?" }
            where.add("memory_type IN ($placeholders)")
            types.forEach { args.add(it.name) }
        }
        if (timeBucket != null && timeBucket != TimeOfDayBucket.ANY) {
            where.add("(time_bucket = ? OR time_bucket = 'ANY')")
            args.add(timeBucket.name)
        }
        val whereClause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")
        args.add(limit.toString())

        val out = mutableListOf<AgenticMemoryEntry>()
        readableDatabase.rawQuery(
            """
            SELECT memory_id, memory_type, topic, content, confidence, reinforcement_count, time_bucket, created_at, last_reinforced_at
            FROM agentic_memories
            $whereClause
            ORDER BY confidence DESC, last_reinforced_at DESC
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) out.add(readAgenticMemoryRow(c))
        }
        return out
    }

    fun deleteAgenticMemory(memoryId: String): Boolean {
        return writableDatabase.delete("agentic_memories", "memory_id = ?", arrayOf(memoryId)) > 0
    }

    fun deleteAgenticMemoryByKeyword(keyword: String): Int {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return 0
        val clean = "%$trimmed%"
        val db = writableDatabase
        db.beginTransaction()
        try {
            val memDel = db.delete(
                "agentic_memories",
                "topic LIKE ? OR content LIKE ? OR memory_id LIKE ?",
                arrayOf(clean, clean, clean)
            )
            val procDel = db.delete(
                "learned_processes",
                "name LIKE ? OR trigger_phrase LIKE ? OR steps_json LIKE ?",
                arrayOf(clean, clean, clean)
            )
            db.setTransactionSuccessful()
            return memDel + procDel
        } finally {
            db.endTransaction()
        }
    }

    fun recordBehaviorEvent(
        eventId: String,
        normalizedCommand: String,
        toolSequence: String,
        appName: String,
        topic: String,
        timeBucket: TimeOfDayBucket,
        dayOfWeek: Int,
        success: Boolean,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("event_id", eventId)
            put("normalized_command", normalizedCommand)
            put("tool_sequence", toolSequence)
            put("app_name", appName)
            put("topic", topic)
            put("time_bucket", timeBucket.name)
            put("day_of_week", dayOfWeek)
            put("success", if (success) 1 else 0)
            put("timestamp_ms", timestampMs)
        }
        db.insertWithOnConflict("behavior_events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

        // Retain 30 days / max 500 events
        val cutoff = timestampMs - 30L * DAY_MS
        runCatching {
            db.delete("behavior_events", "timestamp_ms < ?", arrayOf(cutoff.toString()))
            db.execSQL(
                """
                DELETE FROM behavior_events
                WHERE event_id NOT IN (
                    SELECT event_id FROM behavior_events ORDER BY timestamp_ms DESC LIMIT 500
                )
                """.trimIndent()
            )
        }
    }

    fun getRecentBehaviorEvents(
        sinceMs: Long = 0L,
        limit: Int = 40
    ): List<BehaviorEventRecord> {
        val out = mutableListOf<BehaviorEventRecord>()
        readableDatabase.rawQuery(
            """
            SELECT event_id, normalized_command, tool_sequence, app_name, topic, time_bucket, day_of_week, success, timestamp_ms
            FROM behavior_events
            WHERE timestamp_ms >= ?
            ORDER BY timestamp_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(sinceMs.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val bucket = runCatching { TimeOfDayBucket.valueOf(c.getString(5)) }
                    .getOrDefault(TimeOfDayBucket.ANY)
                out.add(
                    BehaviorEventRecord(
                        eventId = c.getString(0),
                        normalizedCommand = c.getString(1),
                        toolSequence = c.getString(2),
                        appName = c.getString(3),
                        topic = c.getString(4),
                        timeBucket = bucket,
                        dayOfWeek = c.getInt(6),
                        success = c.getInt(7) == 1,
                        timestampMs = c.getLong(8)
                    )
                )
            }
        }
        return out
    }

    fun upsertLearnedProcess(
        processId: String,
        name: String,
        triggerPhrase: String,
        steps: List<String>,
        preferredApp: String = "",
        timeBucket: TimeOfDayBucket = TimeOfDayBucket.ANY,
        baseConfidence: Float = 0.50f,
        autoSuggest: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): LearnedProcessRecord {
        val db = writableDatabase
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotEmpty() }.take(8)
        val stepsJson = JSONArray().apply { cleanSteps.forEach { put(it) } }.toString()
        db.beginTransaction()
        try {
            var prevCount = 0
            var prevConf = 0f
            var prevCreated = nowMs
            db.rawQuery(
                "SELECT execution_count, confidence, created_at FROM learned_processes WHERE process_id = ? LIMIT 1",
                arrayOf(processId)
            ).use { c ->
                if (c.moveToFirst()) {
                    prevCount = c.getInt(0)
                    prevConf = c.getFloat(1)
                    prevCreated = c.getLong(2)
                }
            }

            val nextCount = if (prevCount > 0) prevCount + 1 else 1
            val nextConf = if (prevCount > 0) {
                kotlin.math.min(0.99f, kotlin.math.max(baseConfidence, prevConf + 0.14f))
            } else {
                baseConfidence.coerceIn(0.25f, 0.99f)
            }

            val cv = ContentValues().apply {
                put("process_id", processId)
                put("name", name)
                put("trigger_phrase", triggerPhrase)
                put("steps_json", stepsJson)
                put("preferred_app", preferredApp)
                put("time_bucket", timeBucket.name)
                put("execution_count", nextCount)
                put("confidence", nextConf)
                put("auto_suggest", if (autoSuggest) 1 else 0)
                put("last_executed_at", nowMs)
                put("created_at", prevCreated)
            }
            db.insertWithOnConflict("learned_processes", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()

            return LearnedProcessRecord(
                processId = processId,
                name = name,
                triggerPhrase = triggerPhrase,
                steps = cleanSteps,
                preferredApp = preferredApp,
                timeBucket = timeBucket,
                executionCount = nextCount,
                confidence = nextConf,
                autoSuggest = autoSuggest,
                lastExecutedAtMs = nowMs,
                createdAtMs = prevCreated
            )
        } finally {
            db.endTransaction()
        }
    }

    fun recordLearnedProcessExecution(processId: String, nowMs: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """
                UPDATE learned_processes
                SET execution_count = execution_count + 1,
                    confidence = MIN(0.99, confidence + 0.08),
                    last_executed_at = ?
                WHERE process_id = ?
                """.trimIndent(),
                arrayOf(nowMs, processId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getLearnedProcesses(
        timeBucket: TimeOfDayBucket? = null,
        minConfidence: Float = 0f,
        limit: Int = 25
    ): List<LearnedProcessRecord> {
        val where = mutableListOf("confidence >= ?")
        val args = mutableListOf(minConfidence.toString())
        if (timeBucket != null && timeBucket != TimeOfDayBucket.ANY) {
            where.add("(time_bucket = ? OR time_bucket = 'ANY')")
            args.add(timeBucket.name)
        }
        args.add(limit.toString())
        val out = mutableListOf<LearnedProcessRecord>()
        readableDatabase.rawQuery(
            """
            SELECT process_id, name, trigger_phrase, steps_json, preferred_app, time_bucket, execution_count, confidence, auto_suggest, last_executed_at, created_at
            FROM learned_processes
            WHERE ${where.joinToString(" AND ")}
            ORDER BY confidence DESC, execution_count DESC, last_executed_at DESC
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) out.add(readLearnedProcessRow(c))
        }
        return out
    }

    fun findLearnedProcessByName(query: String): LearnedProcessRecord? {
        val norm = query.trim().lowercase()
            .replace(Regex("^(?:my|your|the|learned|usual)\\s+"), "")
            .replace(Regex("\\s+(?:process|workflow|habit)$"), "")
            .trim()
        val all = getLearnedProcesses(limit = 50)
        if (norm.isEmpty()) return all.firstOrNull()
        return all.firstOrNull { it.name.lowercase() == norm }
            ?: all.firstOrNull { it.name.lowercase().contains(norm) || norm.contains(it.name.lowercase()) }
            ?: all.firstOrNull { it.triggerPhrase.lowercase().contains(norm) }
            ?: all.firstOrNull { it.timeBucket != TimeOfDayBucket.ANY && norm.contains(it.timeBucket.label.lowercase()) }
    }

    fun deleteLearnedProcess(processId: String): Boolean {
        return writableDatabase.delete("learned_processes", "process_id = ?", arrayOf(processId)) > 0
    }

    fun deleteLearnedProcessStep(processId: String, stepIndex: Int): Boolean {
        val current = getLearnedProcesses(limit = 100).firstOrNull { it.processId == processId } ?: return false
        if (stepIndex !in current.steps.indices) return false
        val updatedSteps = current.steps.filterIndexed { idx, _ -> idx != stepIndex }
        if (updatedSteps.isEmpty()) {
            return deleteLearnedProcess(processId)
        }
        val arr = org.json.JSONArray()
        updatedSteps.forEach { arr.put(it) }
        val cv = android.content.ContentValues().apply {
            put("steps_json", arr.toString())
        }
        return writableDatabase.update("learned_processes", cv, "process_id = ?", arrayOf(processId)) > 0
    }

    fun clearAllAgenticLearning() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("agentic_memories", null, null)
            db.delete("behavior_events", null, null)
            db.delete("learned_processes", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAgenticMemoryCounts(): Triple<Int, Int, Int> {
        fun countTable(table: String): Int {
            return runCatching {
                readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }.getOrDefault(0)
        }
        return Triple(
            countTable("agentic_memories"),
            countTable("learned_processes"),
            countTable("behavior_events")
        )
    }

    private fun readAgenticMemoryRow(c: android.database.Cursor): AgenticMemoryEntry {
        val type = runCatching { AgenticMemoryType.valueOf(c.getString(1)) }
            .getOrDefault(AgenticMemoryType.FACT)
        val bucket = runCatching { TimeOfDayBucket.valueOf(c.getString(6)) }
            .getOrDefault(TimeOfDayBucket.ANY)
        return AgenticMemoryEntry(
            memoryId = c.getString(0),
            memoryType = type,
            topic = c.getString(2),
            content = c.getString(3),
            confidence = c.getFloat(4),
            reinforcementCount = c.getInt(5),
            timeBucket = bucket,
            createdAtMs = c.getLong(7),
            lastReinforcedAtMs = c.getLong(8)
        )
    }

    private fun readLearnedProcessRow(c: android.database.Cursor): LearnedProcessRecord {
        val bucket = runCatching { TimeOfDayBucket.valueOf(c.getString(5)) }
            .getOrDefault(TimeOfDayBucket.ANY)
        val stepsList = mutableListOf<String>()
        runCatching {
            val arr = JSONArray(c.getString(3))
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty()) stepsList.add(item)
            }
        }
        return LearnedProcessRecord(
            processId = c.getString(0),
            name = c.getString(1),
            triggerPhrase = c.getString(2),
            steps = stepsList,
            preferredApp = c.getString(4),
            timeBucket = bucket,
            executionCount = c.getInt(6),
            confidence = c.getFloat(7),
            autoSuggest = c.getInt(8) == 1,
            lastExecutedAtMs = c.getLong(9),
            createdAtMs = c.getLong(10)
        )
    }

    companion object {
        private const val TAG = "LocalFtsIndexHelper"
        private const val DB_NAME = "nova_local_fts5.db"
        private const val DB_VERSION = 5
        const val DAY_MS = 24L * 3600_000L
        const val WEEK_MS = 7L * 24L * 3600_000L
        const val NOTIFICATION_RETENTION_MS = 14L * 24L * 3600_000L
        private val STOP_WORDS = setOf(
            "the", "and", "for", "that", "this", "what", "where", "when", "find",
            "show", "from", "with", "into", "my", "is", "are", "was", "did", "can", "you"
        )

        fun floatsToBytes(floats: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            floats.forEach { buf.putFloat(it) }
            return buf.array()
        }

        fun bytesToFloats(bytes: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(bytes.size / 4)
            for (i in out.indices) out[i] = buf.getFloat()
            return out
        }
    }
}
