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
import com.example.processA.EmbeddingGemmaRuntime
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
        try {
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
            activeFtsMode = "FTS5"
        } catch (ex: SQLiteException) {
            Log.w(TAG, "FTS5 module unavailable on device SQLite, falling back to FTS4", ex)
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
            activeFtsMode = "FTS4"
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            runCatching { db.execSQL("ALTER TABLE local_docs_meta ADD COLUMN embedding BLOB") }
                .onFailure {
                    db.execSQL("DROP TABLE IF EXISTS nova_fts")
                    db.execSQL("DROP TABLE IF EXISTS local_docs_meta")
                    onCreate(db)
                }
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            db.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='nova_fts'",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val sql = cursor.getString(0).orEmpty()
                    activeFtsMode = if (sql.contains("fts5", ignoreCase = true)) "FTS5" else "FTS4"
                }
            }
        } catch (_: Exception) {
        }
    }

    fun upsertDocument(
        docId: String,
        category: String,
        title: String,
        body: String,
        uriOrRef: String,
        embedding: FloatArray? = null
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
                put("updated_at", System.currentTimeMillis())
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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteDocument(docId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("local_docs_meta", "doc_id = ?", arrayOf(docId))
            db.delete("nova_fts", "doc_id = ?", arrayOf(docId))
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
    fun syncContacts(context: Context): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return -1
        }
        var count = 0
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
                while (cursor.moveToNext() && count < 500) {
                    val cid = cursor.getString(idCol).orEmpty()
                    val name = cursor.getString(nameCol).orEmpty().trim()
                    val num = cursor.getString(numCol).orEmpty().trim()
                    if (name.isNotEmpty() && num.isNotEmpty()) {
                        upsertDocument(
                            docId = "contact_${cid}_${count}",
                            category = "contacts",
                            title = name,
                            body = "Phone: $num",
                            uriOrRef = "tel:$num"
                        )
                        count++
                    }
                }
            }
        }.onFailure { Log.w(TAG, "syncContacts failed: ${it.message}") }
        return count
    }

    /**
     * Indexes real upcoming Android Calendar events when READ_CALENDAR permission is granted.
     */
    fun syncCalendarEvents(context: Context): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return -1
        }
        var count = 0
        val now = System.currentTimeMillis()
        val windowStart = now - 24L * 3600_000L
        val windowEnd = now + 14L * 24L * 3600_000L
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
                    upsertDocument(
                        docId = "cal_$eid",
                        category = "calendar",
                        title = title,
                        body = body,
                        uriOrRef = "content://com.android.calendar/events/$eid"
                    )
                    count++
                }
            }
        }.onFailure { Log.w(TAG, "syncCalendarEvents failed: ${it.message}") }
        return count
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

        val ftsMatchExpr = sanitizedTokens.joinToString(" OR ") { "$it*" }
        val results = mutableListOf<LocalIndexDocument>()
        val db = readableDatabase

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
            val likeArg = "%${sanitizedTokens.first()}%"
            db.rawQuery(
                "SELECT doc_id, category, title, body, uri_or_ref FROM local_docs_meta WHERE (title LIKE ? OR body LIKE ?) LIMIT ?",
                arrayOf(likeArg, likeArg, limit.toString())
            ).use { cursor ->
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

    companion object {
        private const val TAG = "LocalFtsIndexHelper"
        private const val DB_NAME = "nova_local_fts5.db"
        private const val DB_VERSION = 3
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
