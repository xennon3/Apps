package app.murmur.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Messages waiting to go out: ones inside the undo window and ones scheduled for later.
 * Kept on disk so a scheduled message survives the app being closed or the phone restarting.
 */
class PendingStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "pending.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pending (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, " +
                "address TEXT, body TEXT, at INTEGER, scheduled INTEGER, attachment TEXT, sub_id INTEGER DEFAULT -1)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE pending ADD COLUMN attachment TEXT")
            db.execSQL("ALTER TABLE pending ADD COLUMN sub_id INTEGER DEFAULT -1")
        }
    }

    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Pending>> = _all

    private fun load(): List<Pending> =
        readableDatabase.query(
            "pending", arrayOf("_id", "thread_id", "address", "body", "at", "scheduled", "attachment", "sub_id"),
            null, null, null, null, "at ASC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Pending(
                        id = c.getLong(0), threadId = c.getLong(1), address = c.getString(2),
                        body = c.getString(3), at = c.getLong(4), scheduled = c.getInt(5) == 1,
                        attachment = c.getString(6), subId = c.getInt(7),
                    ),
                )
            }
        }

    @Synchronized
    fun add(
        threadId: Long, address: String, body: String, at: Long, scheduled: Boolean,
        attachment: String? = null, subId: Int = -1,
    ): Pending {
        val id = writableDatabase.insert("pending", null, ContentValues().apply {
            put("thread_id", threadId); put("address", address); put("body", body)
            put("at", at); put("scheduled", if (scheduled) 1 else 0)
            put("attachment", attachment); put("sub_id", subId)
        })
        _all.value = load()
        return Pending(id, threadId, address, body, at, scheduled, attachment, subId)
    }

    /** Removes the row and returns it, or null if someone else (undo, the alarm) got there first. */
    @Synchronized
    fun take(id: Long): Pending? {
        val p = _all.value.firstOrNull { it.id == id } ?: return null
        val n = writableDatabase.delete("pending", "_id = ?", arrayOf(id.toString()))
        _all.value = load()
        return if (n == 1) p else null
    }

    @Synchronized
    fun reschedule(id: Long, at: Long) {
        writableDatabase.update("pending", ContentValues().apply { put("at", at); put("scheduled", 1) },
            "_id = ?", arrayOf(id.toString()))
        _all.value = load()
    }

    companion object {
        @Volatile private var instance: PendingStore? = null
        fun get(context: Context): PendingStore =
            instance ?: synchronized(this) {
                instance ?: PendingStore(context.applicationContext).also { instance = it }
            }

        /** Where pictures wait before they're sent. */
        fun outgoingDir(context: Context) = File(context.filesDir, "outgoing").apply { mkdirs() }
    }
}
