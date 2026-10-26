package com.nefeon.akzsa.files

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** App-private store of a bounded set of 512-number crop vectors per image file. Nothing is uploaded. */
class ClipVectorStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "akzsa_clip_vectors.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clip_vec (file_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT NOT NULL, vec BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun fingerprint(fileId: String): String? =
        readableDatabase.rawQuery("SELECT fingerprint FROM clip_vec WHERE file_id=?", arrayOf(fileId)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun put(fileId: String, fingerprint: String, vector: FloatArray) = putCrops(fileId, fingerprint, listOf(vector))

    /** Stores several crop vectors together so a photo remains one indexed database row. */
    fun putCrops(fileId: String, fingerprint: String, vectors: List<FloatArray>) {
        require(vectors.isNotEmpty() && vectors.all { it.size == ClipRanking.DIM }) { "Invalid photo embedding dimensions." }
        val bytes = ByteBuffer.allocate(vectors.size * ClipRanking.DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
        vectors.forEach { bytes.asFloatBuffer().put(it); bytes.position(bytes.position() + it.size * 4) }
        val values = ContentValues().apply {
            put("file_id", fileId)
            put("fingerprint", fingerprint)
            put("vec", bytes.array())
        }
        writableDatabase.insertWithOnConflict("clip_vec", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allIds(): Set<String> =
        readableDatabase.rawQuery("SELECT file_id FROM clip_vec", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM clip_vec", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun deleteIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("clip_vec", "file_id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.delete("clip_vec", null, null)
    }


    data class VectorMatch(val fileId: String, val similarity: Float)

    /** Streams each stored crop row from SQLite; does not allocate a giant N x 512 heap matrix. */
    fun searchByVector(query: FloatArray, minimum: Float = ClipRanking.MIN_SIMILARITY,
                       band: Float = ClipRanking.BAND, limit: Int = ClipRanking.MAX_RESULTS): List<VectorMatch> {
        require(query.size == ClipRanking.DIM) { "Unexpected query embedding dimension." }
        val perFile = HashMap<String, Float>()
        readableDatabase.rawQuery("SELECT file_id, vec FROM clip_vec", null).use { cursor ->
            while (cursor.moveToNext()) {
                val fileId = cursor.getString(0)
                val blob = cursor.getBlob(1)
                val bytesPerVector = ClipRanking.DIM * 4
                if (blob.isEmpty() || blob.size % bytesPerVector != 0) continue
                val floats = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var best = Float.NEGATIVE_INFINITY
                while (floats.remaining() >= ClipRanking.DIM) {
                    var dot = 0f
                    repeat(ClipRanking.DIM) { dot += floats.get() * query[it] }
                    if (dot > best) best = dot
                }
                if (best.isFinite() && best > (perFile[fileId] ?: Float.NEGATIVE_INFINITY)) perFile[fileId] = best
            }
        }
        val bestOverall = perFile.values.maxOrNull() ?: return emptyList()
        if (bestOverall < minimum) return emptyList()
        val floor = maxOf(minimum, bestOverall - band)
        return perFile.entries.asSequence()
            .filter { it.value >= floor }
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
            .take(limit.coerceIn(1, 50))
            .map { VectorMatch(it.key, it.value) }
            .toList()
    }

    /** Legacy diagnostic API. Production search uses [searchByVector] to keep heap use bounded. */
    fun loadAll(dim: Int): Pair<Array<String>, FloatArray> {
        val ids = ArrayList<String>()
        val rows = ArrayList<FloatArray>()
        readableDatabase.rawQuery("SELECT file_id, vec FROM clip_vec", null).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(1)
                val bytesPerVector = dim * 4
                if (blob.isEmpty() || blob.size % bytesPerVector != 0) continue
                val count = blob.size / bytesPerVector
                val view = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                repeat(count) {
                    val floats = FloatArray(dim)
                    view.get(floats)
                    ids.add(c.getString(0)) // repeated IDs are grouped into one photo result during ranking
                    rows.add(floats)
                }
            }
        }
        val matrix = FloatArray(rows.size * dim)
        rows.forEachIndexed { i, row -> System.arraycopy(row, 0, matrix, i * dim, dim) }
        return ids.toTypedArray() to matrix
    }

    companion object {
        const val PREF_VERSION = "clip_vec_version"

        /** Tells the search cache that vectors changed. */
        fun bumpVersion(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE)
            prefs.edit().putLong(PREF_VERSION, System.nanoTime()).apply()
        }

        fun version(context: Context): Long =
            context.applicationContext.getSharedPreferences("akzsa_file_ai", Context.MODE_PRIVATE).getLong(PREF_VERSION, 0L)
    }
}
