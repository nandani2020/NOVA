package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.processA.EmbeddingGemmaRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local persistent SQLite store for user-recorded & customizable conditional routines.
 * Supports per-app and global routines plus hybrid FTS5 / EmbeddingGemma indexing so the
 * assistant can discover and invoke any recorded routine cleanly.
 */
class RecordedRoutineStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val _routines = MutableStateFlow<List<RecordedRoutine>>(emptyList())
    val routines: StateFlow<List<RecordedRoutine>> = _routines.asStateFlow()

    init {
        refreshFlow()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS recorded_routines (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                scope TEXT NOT NULL,
                target_package TEXT,
                target_app_name TEXT,
                steps_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    fun getAll(): List<RecordedRoutine> {
        val list = mutableListOf<RecordedRoutine>()
        readableDatabase.rawQuery(
            """
            SELECT id, name, description, scope, target_package, target_app_name, steps_json, created_at, updated_at
            FROM recorded_routines
            ORDER BY updated_at DESC
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                val scope = runCatching { RoutineScope.valueOf(c.getString(3)) }
                    .getOrDefault(RoutineScope.GLOBAL)
                list.add(
                    RecordedRoutine(
                        id = c.getString(0),
                        name = c.getString(1),
                        description = c.getString(2),
                        scope = scope,
                        targetPackage = c.getString(4)?.takeIf { it.isNotBlank() },
                        targetAppName = c.getString(5)?.takeIf { it.isNotBlank() },
                        steps = RoutineJsonCodec.stepsFromJson(c.getString(6)),
                        createdAt = c.getLong(7),
                        updatedAt = c.getLong(8)
                    )
                )
            }
        }
        return list
    }

    fun findById(id: String): RecordedRoutine? =
        getAll().firstOrNull { it.id == id }

    fun findByName(rawQuery: String, appFilter: String? = null): RecordedRoutine? {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return null
        val all = getAll()
        val scoped = if (!appFilter.isNullOrBlank()) {
            all.filter {
                it.scope == RoutineScope.GLOBAL ||
                    it.targetAppName?.contains(appFilter, ignoreCase = true) == true ||
                    it.targetPackage?.contains(appFilter, ignoreCase = true) == true
            }.ifEmpty { all }
        } else {
            all
        }

        scoped.firstOrNull { it.name.equals(q, ignoreCase = true) }?.let { return it }
        scoped.firstOrNull { it.name.lowercase().contains(q) }?.let { return it }
        scoped.firstOrNull { q.contains(it.name.lowercase()) && it.name.length >= 2 }?.let { return it }

        val tokens = q.split(Regex("\\s+")).filter { it.length >= 2 }
        return scoped.mapNotNull { rt ->
            val hay = "${rt.name} ${rt.description} ${rt.targetAppName.orEmpty()}".lowercase()
            val hits = tokens.count { hay.contains(it) }
            if (hits > 0) rt to hits else null
        }.maxByOrNull { it.second }?.first
    }

    fun saveRoutine(routine: RecordedRoutine): RecordedRoutine {
        val now = System.currentTimeMillis()
        val cleanName = routine.name.trim().ifEmpty { "Custom Routine" }
        val normalized = routine.copy(
            name = cleanName,
            updatedAt = now,
            createdAt = if (routine.createdAt > 0L) routine.createdAt else now
        )
        val cv = ContentValues().apply {
            put("id", normalized.id)
            put("name", normalized.name)
            put("description", normalized.description.trim())
            put("scope", normalized.scope.name)
            put("target_package", normalized.targetPackage)
            put("target_app_name", normalized.targetAppName)
            put("steps_json", RoutineJsonCodec.stepsToJson(normalized.steps))
            put("created_at", normalized.createdAt)
            put("updated_at", normalized.updatedAt)
        }
        writableDatabase.insertWithOnConflict(
            "recorded_routines",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        refreshFlow()
        return normalized
    }

    fun deleteRoutine(id: String) {
        writableDatabase.delete("recorded_routines", "id = ?", arrayOf(id))
        refreshFlow()
    }

    suspend fun syncRoutineIntoFts(
        routine: RecordedRoutine,
        ftsHelper: LocalFtsIndexHelper,
        embeddingRuntime: EmbeddingGemmaRuntime? = null
    ) {
        val docId = "rt_${routine.id}"
        val scopeDesc = if (routine.scope == RoutineScope.PER_APP && !routine.targetAppName.isNullOrBlank()) {
            "Per-app routine for ${routine.targetAppName}"
        } else {
            "Global phone routine"
        }
        val stepSummary = routine.steps.joinToString("; ") { s ->
            if (s.condition != null) "${s.title} (${s.condition.describe()})" else s.title
        }
        val body = listOf(scopeDesc, routine.description, stepSummary)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .take(500)
        val vec = embeddingRuntime?.embedDocument(routine.name, body)
        ftsHelper.upsertDocument(
            docId = docId,
            category = "routines",
            title = routine.name,
            body = body,
            uriOrRef = "nova://routine/${routine.id}",
            embedding = vec
        )
    }

    fun reload() {
        refreshFlow()
    }

    private fun refreshFlow() {
        _routines.value = getAll()
    }

    companion object {
        private const val DB_NAME = "nova_recorded_routines.db"
        private const val DB_VERSION = 1
    }
}
