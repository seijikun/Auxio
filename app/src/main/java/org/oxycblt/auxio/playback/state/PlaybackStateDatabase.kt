/*
 * Copyright (c) 2021 Auxio Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback.state

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.oxycblt.auxio.music.Music
import org.oxycblt.auxio.music.MusicParent
import org.oxycblt.auxio.music.MusicStore
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.queryAll
import org.oxycblt.auxio.util.requireBackgroundThread

/**
 * A SQLite database for managing the persistent playback state and queue. Yes. I know Room exists.
 * But that would needlessly bloat my app and has crippling bugs.
 * @author OxygenCobalt
 */
class PlaybackStateDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createTable(db, TABLE_NAME_STATE)
        createTable(db, TABLE_NAME_QUEUE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = nuke(db)
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = nuke(db)

    private fun nuke(db: SQLiteDatabase) {
        logD("Nuking database")
        db.apply {
            execSQL("DROP TABLE IF EXISTS $TABLE_NAME_STATE")
            execSQL("DROP TABLE IF EXISTS $TABLE_NAME_QUEUE")
            onCreate(this)
        }
    }

    // --- DATABASE CONSTRUCTION FUNCTIONS ---

    /** Create a table for this database. */
    private fun createTable(database: SQLiteDatabase, tableName: String) {
        val command = StringBuilder()
        command.append("CREATE TABLE IF NOT EXISTS $tableName(")

        if (tableName == TABLE_NAME_STATE) {
            constructStateTable(command)
        } else if (tableName == TABLE_NAME_QUEUE) {
            constructQueueTable(command)
        }

        database.execSQL(command.toString())
    }

    /** Construct a [StateColumns] table */
    private fun constructStateTable(command: StringBuilder): StringBuilder {
        command
            .append("${StateColumns.COLUMN_ID} LONG PRIMARY KEY,")
            .append("${StateColumns.COLUMN_SONG_UID} STRING,")
            .append("${StateColumns.COLUMN_POSITION} LONG NOT NULL,")
            .append("${StateColumns.COLUMN_PARENT_UID} STRING,")
            .append("${StateColumns.COLUMN_INDEX} INTEGER NOT NULL,")
            .append("${StateColumns.COLUMN_IS_SHUFFLED} BOOLEAN NOT NULL,")
            .append("${StateColumns.COLUMN_REPEAT_MODE} INTEGER NOT NULL)")

        return command
    }

    /** Construct a [QueueColumns] table */
    private fun constructQueueTable(command: StringBuilder): StringBuilder {
        command
            .append("${QueueColumns.ID} LONG PRIMARY KEY,")
            .append("${QueueColumns.SONG_UID} STRING NOT NULL)")

        return command
    }

    // --- INTERFACE FUNCTIONS ---

    fun read(library: MusicStore.Library): SavedState? {
        requireBackgroundThread()

        val rawState = readRawState() ?: return null
        val queue = readQueue(library)

        // Correct the index to match up with a possibly shortened queue (file removals/changes)
        var actualIndex = rawState.index
        while (queue.getOrNull(actualIndex)?.uid != rawState.songUid && actualIndex > -1) {
            actualIndex--
        }

        val parent = rawState.parentUid?.let { library.find<MusicParent>(it) }

        return SavedState(
            index = actualIndex,
            parent = parent,
            queue = queue,
            positionMs = rawState.positionMs,
            repeatMode = rawState.repeatMode,
            isShuffled = rawState.isShuffled,
        )
    }

    private fun readRawState(): RawState? {
        return readableDatabase.queryAll(TABLE_NAME_STATE) { cursor ->
            if (cursor.count == 0) {
                return@queryAll null
            }

            val indexIndex = cursor.getColumnIndexOrThrow(StateColumns.COLUMN_INDEX)

            val posIndex = cursor.getColumnIndexOrThrow(StateColumns.COLUMN_POSITION)
            val repeatModeIndex = cursor.getColumnIndexOrThrow(StateColumns.COLUMN_REPEAT_MODE)
            val shuffleIndex = cursor.getColumnIndexOrThrow(StateColumns.COLUMN_IS_SHUFFLED)
            val songUidIndex = cursor.getColumnIndexOrThrow(StateColumns.COLUMN_SONG_UID)
            val parentUidIndex = cursor.getColumnIndexOrThrow(StateColumns.COLUMN_PARENT_UID)

            cursor.moveToFirst()

            RawState(
                index = cursor.getInt(indexIndex),
                positionMs = cursor.getLong(posIndex),
                repeatMode = RepeatMode.fromIntCode(cursor.getInt(repeatModeIndex))
                        ?: RepeatMode.NONE,
                isShuffled = cursor.getInt(shuffleIndex) == 1,
                songUid = Music.UID.fromString(cursor.getString(songUidIndex))
                        ?: return@queryAll null,
                parentUid = cursor.getString(parentUidIndex)?.let(Music.UID::fromString))
        }
    }

    private fun readQueue(library: MusicStore.Library): MutableList<Song> {
        requireBackgroundThread()

        val queue = mutableListOf<Song>()

        readableDatabase.queryAll(TABLE_NAME_QUEUE) { cursor ->
            if (cursor.count == 0) return@queryAll
            val songIndex = cursor.getColumnIndexOrThrow(QueueColumns.SONG_UID)
            while (cursor.moveToNext()) {
                val uid = Music.UID.fromString(cursor.getString(songIndex)) ?: continue
                val song = library.find<Song>(uid) ?: continue
                queue.add(song)
            }
        }

        logD("Successfully read queue of ${queue.size} songs")

        return queue
    }

    /** Clear the previously written [SavedState] and write a new one. */
    fun write(state: SavedState?) {
        requireBackgroundThread()

        if (state != null && state.index in state.queue.indices) {
            val rawState =
                RawState(
                    index = state.index,
                    positionMs = state.positionMs,
                    repeatMode = state.repeatMode,
                    isShuffled = state.isShuffled,
                    songUid = state.queue[state.index].uid,
                    parentUid = state.parent?.uid)

            writeRawState(rawState)
            writeQueue(state.queue)
        } else {
            writeRawState(null)
            writeQueue(null)
        }

        logD("Wrote state to database")
    }

    private fun writeRawState(rawState: RawState?) {
        writableDatabase.transaction {
            delete(TABLE_NAME_STATE, null, null)

            if (rawState != null) {
                val stateData =
                    ContentValues(10).apply {
                        put(StateColumns.COLUMN_ID, 0)
                        put(StateColumns.COLUMN_SONG_UID, rawState.songUid.toString())
                        put(StateColumns.COLUMN_POSITION, rawState.positionMs)
                        put(StateColumns.COLUMN_PARENT_UID, rawState.parentUid?.toString())
                        put(StateColumns.COLUMN_INDEX, rawState.index)
                        put(StateColumns.COLUMN_IS_SHUFFLED, rawState.isShuffled)
                        put(StateColumns.COLUMN_REPEAT_MODE, rawState.repeatMode.intCode)
                    }

                insert(TABLE_NAME_STATE, null, stateData)
            }
        }
    }

    /** Write a queue to the database. */
    private fun writeQueue(queue: List<Song>?) {
        val database = writableDatabase
        database.transaction { delete(TABLE_NAME_QUEUE, null, null) }

        logD("Wiped queue db")

        if (queue != null) {
            val idStart = queue.size
            logD("Beginning queue write [start: $idStart]")
            var position = 0

            while (position < queue.size) {
                var i = position

                database.transaction {
                    while (i < queue.size) {
                        val song = queue[i]
                        i++

                        val itemData =
                            ContentValues(4).apply {
                                put(QueueColumns.ID, idStart + i)
                                put(QueueColumns.SONG_UID, song.uid.toString())
                            }

                        insert(TABLE_NAME_QUEUE, null, itemData)
                    }
                }

                // Update the position at the end, if an insert failed at any point, then
                // the next iteration should skip it.
                position = i

                logD("Wrote batch of songs. Position is now at $position")
            }
        }
    }

    data class SavedState(
        val index: Int,
        val queue: List<Song>,
        val parent: MusicParent?,
        val positionMs: Long,
        val repeatMode: RepeatMode,
        val isShuffled: Boolean,
    )

    private data class RawState(
        val index: Int,
        val positionMs: Long,
        val repeatMode: RepeatMode,
        val isShuffled: Boolean,
        val songUid: Music.UID,
        val parentUid: Music.UID?
    )

    private object StateColumns {
        const val COLUMN_ID = "id"
        const val COLUMN_SONG_UID = "song_uid"
        const val COLUMN_POSITION = "position"
        const val COLUMN_PARENT_UID = "parent"
        const val COLUMN_INDEX = "queue_index"
        const val COLUMN_IS_SHUFFLED = "is_shuffling"
        const val COLUMN_REPEAT_MODE = "repeat_mode"
    }

    private object QueueColumns {
        const val ID = "id"
        const val SONG_UID = "song_uid"
    }

    companion object {
        const val DB_NAME = "auxio_state_database.db"
        const val DB_VERSION = 8

        const val TABLE_NAME_STATE = "playback_state_table"
        const val TABLE_NAME_QUEUE = "queue_table"

        @Volatile private var INSTANCE: PlaybackStateDatabase? = null

        /** Get/Instantiate the single instance of [PlaybackStateDatabase]. */
        fun getInstance(context: Context): PlaybackStateDatabase {
            val currentInstance = INSTANCE

            if (currentInstance != null) {
                return currentInstance
            }

            synchronized(this) {
                val newInstance = PlaybackStateDatabase(context.applicationContext)
                INSTANCE = newInstance
                return newInstance
            }
        }
    }
}
