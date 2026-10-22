package com.techcity.techcitysuite

import com.google.firebase.Timestamp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device cache of End of Day documents (daily_summaries) for the Overview module.
 *
 * Holds a trimmed copy of every document the device has fetched, plus a watermark: the greatest
 * server `timestamp` seen so far. The Overview uses the watermark to fetch only documents saved or
 * re-saved since the last visit instead of re-reading the whole year on every open.
 *
 * All disk access is plain java.io; callers must run load/save/clear on Dispatchers.IO.
 */
class OverviewSummaryCache(private val file: File) {

    /** Document id -> trimmed document map (same shape the Firestore fetchers return). */
    val docs: MutableMap<String, Map<String, Any?>> = mutableMapOf()

    /** Greatest server timestamp seen; null means no cache (first visit). */
    var watermark: Timestamp? = null
        private set

    /** Earliest date (yyyy-MM-dd) the full or gap loads have covered; empty when no cache. */
    var cacheStart: String = ""

    /** Documents dated before this (yyyy-MM-dd) are dropped on save. Empty = no pruning. */
    var pruneBefore: String = ""

    val isEmpty: Boolean
        get() = watermark == null

    /**
     * Read the cache file. On a missing file, version mismatch or any parse error the state is
     * cleared, the file deleted and false returned. Never throws.
     */
    fun load(): Boolean {
        resetState()
        if (!file.exists()) return false
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt(KEY_VERSION, -1) != VERSION) throw IllegalStateException("Unsupported cache version")
            if (!root.has(KEY_WATERMARK_SECONDS)) throw IllegalStateException("Missing watermark")
            watermark = Timestamp(root.getLong(KEY_WATERMARK_SECONDS), root.optInt(KEY_WATERMARK_NANOS, 0))
            cacheStart = root.optString(KEY_CACHE_START, "")
            val array = root.getJSONArray(KEY_DOCS)
            for (i in 0 until array.length()) {
                val doc = docFromJson(array.getJSONObject(i))
                val id = doc[FIELD_ID] as? String ?: continue
                docs[id] = doc
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            resetState()
            if (file.exists()) file.delete()
            false
        }
    }

    /**
     * Prune and write the cache. Writes to a temp file then renames over the target so a crash
     * mid-write never leaves a half file. Failures are logged; in-memory state stays valid.
     */
    fun save() {
        val mark = watermark ?: return
        try {
            if (pruneBefore.isNotEmpty()) {
                val stale = docs.filterValues { (it[FIELD_DATE] as? String ?: "") < pruneBefore }.keys
                stale.forEach { docs.remove(it) }
            }
            val root = JSONObject()
            root.put(KEY_VERSION, VERSION)
            root.put(KEY_CACHE_START, cacheStart)
            root.put(KEY_WATERMARK_SECONDS, mark.seconds)
            root.put(KEY_WATERMARK_NANOS, mark.nanoseconds)
            val array = JSONArray()
            for (doc in docs.values) array.put(docToJson(doc))
            root.put(KEY_DOCS, array)

            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                // renameTo does not replace an existing target on every filesystem
                file.delete()
                if (!temp.renameTo(file)) throw IllegalStateException("Could not replace cache file")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Add or replace documents by id and advance the watermark. */
    fun merge(fetched: List<Map<String, Any?>>) {
        for (doc in fetched) {
            val id = doc[FIELD_ID] as? String ?: continue
            docs[id] = trim(doc)
            val ts = doc[FIELD_TIMESTAMP] as? Timestamp ?: continue
            val current = watermark
            if (current == null || ts > current) watermark = ts
        }
    }

    /** Cached documents with date in [startDate, endDate]. */
    fun inRange(startDate: String, endDate: String): List<Map<String, Any?>> {
        return docs.values.filter {
            val date = it[FIELD_DATE] as? String ?: return@filter false
            date >= startDate && date <= endDate
        }
    }

    /** True when the cache has been loaded from the given date onward. */
    fun covers(startDate: String): Boolean {
        return !isEmpty && cacheStart.isNotEmpty() && startDate >= cacheStart
    }

    /** Forget everything and delete the file. */
    fun clear() {
        resetState()
        try {
            if (file.exists()) file.delete()
            val temp = File(file.parentFile, file.name + ".tmp")
            if (temp.exists()) temp.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resetState() {
        docs.clear()
        watermark = null
        cacheStart = ""
    }

    /** Keep only the fields the Overview reads. */
    private fun trim(doc: Map<String, Any?>): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        for (field in KEPT_FIELDS) {
            if (doc.containsKey(field)) out[field] = doc[field]
        }
        return out
    }

    private fun docToJson(doc: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in doc) {
            if (key == FIELD_TIMESTAMP) {
                val ts = value as? Timestamp ?: continue
                json.put(KEY_DOC_TIMESTAMP_SECONDS, ts.seconds)
                json.put(KEY_DOC_TIMESTAMP_NANOS, ts.nanoseconds)
            } else {
                json.put(key, toJson(value))
            }
        }
        return json
    }

    private fun docFromJson(json: JSONObject): Map<String, Any?> {
        val doc = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (key) {
                KEY_DOC_TIMESTAMP_SECONDS, KEY_DOC_TIMESTAMP_NANOS -> Unit
                else -> doc[key] = fromJson(json.get(key))
            }
        }
        if (json.has(KEY_DOC_TIMESTAMP_SECONDS)) {
            doc[FIELD_TIMESTAMP] = Timestamp(
                json.getLong(KEY_DOC_TIMESTAMP_SECONDS),
                json.optInt(KEY_DOC_TIMESTAMP_NANOS, 0)
            )
        }
        return doc
    }

    private fun toJson(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) obj.put(k.toString(), toJson(v))
                obj
            }
            is List<*> -> {
                val arr = JSONArray()
                for (v in value) arr.put(toJson(v))
                arr
            }
            is Number, is String, is Boolean -> value
            else -> value.toString()
        }
    }

    private fun fromJson(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val map = mutableMapOf<String, Any?>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = fromJson(value.get(k))
                }
                map
            }
            is JSONArray -> (0 until value.length()).map { fromJson(value.opt(it)) }
            else -> value
        }
    }

    companion object {
        private const val VERSION = 1

        private const val KEY_VERSION = "version"
        private const val KEY_CACHE_START = "cacheStart"
        private const val KEY_WATERMARK_SECONDS = "watermarkSeconds"
        private const val KEY_WATERMARK_NANOS = "watermarkNanos"
        private const val KEY_DOCS = "docs"
        private const val KEY_DOC_TIMESTAMP_SECONDS = "timestampSeconds"
        private const val KEY_DOC_TIMESTAMP_NANOS = "timestampNanos"

        private const val FIELD_ID = "_id"
        private const val FIELD_DATE = "date"
        private const val FIELD_TIMESTAMP = "timestamp"

        private val KEPT_FIELDS = listOf(
            FIELD_ID,
            FIELD_DATE,
            "storeLocation",
            "salesSummary",
            "cashFlowSummary",
            FIELD_TIMESTAMP
        )
    }
}
