package eu.fliegendewurst.triliumdroid.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import eu.fliegendewurst.triliumdroid.database.Cache.Versions
import eu.fliegendewurst.triliumdroid.database.Cache.utcDateModified
import eu.fliegendewurst.triliumdroid.database.DB
import eu.fliegendewurst.triliumdroid.util.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

@OptIn(ExperimentalEncodingApi::class)
object Sync {
	private const val TAG = "Sync"

	suspend fun syncPush(): Int = withContext(Dispatchers.IO) {
		val instanceId = Preferences.instanceId()

		var lastSyncedPush = 0
		DB.rawQuery("SELECT value FROM options WHERE name = ?", arrayOf("lastSyncedPush"))
			.use {
				if (it.moveToFirst()) {
					lastSyncedPush = it.getInt(0)
				}
			}
		Log.i(TAG, "last synced push: $lastSyncedPush")
		val logMarkerId = "trilium-droid"
		val changesUri = "/api/sync/update?logMarkerId=$logMarkerId"

		val data = JSONObject()
		data.put("instanceId", instanceId)
		val entities = JSONArray()
		var largestId = lastSyncedPush
		DB.rawQuery(
			"SELECT * FROM entity_changes WHERE isSynced = 1 AND id > ?",
			arrayOf(lastSyncedPush.toString())
		).use {

			while (it.moveToNext()) {
				val instanceIdSaved = it.getString(7)
				if (instanceIdSaved != instanceId) {
					continue
				}

				val id = it.getInt(0)
				largestId = StrictMath.max(largestId, id)
				val entityName = it.getString(1)
				val entityId = it.getString(2)
				val hash = it.getString(3)
				val isErased = it.getInt(4)
				val changeId = it.getString(5)
				val componentId = it.getString(6)
				val instanceIdHere = it.getString(7)
				val isSynced = it.getString(8)
				val utc = it.getString(9)

				val row = JSONObject()

				val entityChange = JSONObject()
				entityChange.put("id", id)
				entityChange.put("entityName", entityName)
				entityChange.put("entityId", entityId)
				entityChange.put("hash", hash)
				entityChange.put("isErased", isErased)
				entityChange.put("changeId", changeId)
				entityChange.put("componentId", componentId)
				entityChange.put("instanceId", instanceIdHere)
				entityChange.put("isSynced", isSynced)
				entityChange.put("utcDateChanged", utc)
				row.put("entityChange", entityChange)

				if (isErased == 0) {
					val entity = fetchEntity(entityName, entityId)
					row.put("entity", entity)
				}

				entities.put(row)
			}
		}
		data.put("entities", entities)

		if (entities.length() > 0) {
			ConnectionUtil.doSyncPushRequest(changesUri, data)
			Log.i(TAG, "synced up to $largestId")
			val utc =
				DateTimeFormatter.ISO_INSTANT.format(OffsetDateTime.now(ZoneOffset.UTC))
					.replace('T', ' ')
			// read-only mode: sync must still work
			DB.internalGetDatabase()!!.execSQL(
				"INSERT OR REPLACE INTO options (name, value, isSynced, utcDateModified) VALUES (?, ?, 0, ?)",
				arrayOf("lastSyncedPush", largestId.toString(), utc)
			)
		}
		return@withContext entities.length()
	}

	/**
	 * Get row from database table [entityName] with ID [entityId]
	 */
	private suspend fun fetchEntity(entityName: String, entityId: String): JSONObject =
		withContext(Dispatchers.IO) {
			val primaryKey = primaryKeyForTable(entityName)
			val obj = JSONObject()
			DB.rawQuery("SELECT * FROM $entityName WHERE $primaryKey = ?", arrayOf(entityId))
				.use {
					if (it.moveToNext()) {
						for (i in (0 until it.columnCount)) {
							var x: Any? = null
							when (it.getType(i)) {
								Cursor.FIELD_TYPE_NULL -> {
									x = null
								}

								Cursor.FIELD_TYPE_BLOB -> {
									x = it.getBlob(i)
								}

								Cursor.FIELD_TYPE_FLOAT -> {
									x = it.getDouble(i)
								}

								Cursor.FIELD_TYPE_INTEGER -> {
									x = it.getLong(i)
								}

								Cursor.FIELD_TYPE_STRING -> {
									x = it.getString(i)
								}
							}
							val column = it.getColumnName(i)
							if (x == null) {
								obj.put(column, null)
								continue
							}
							if (column == "content") {
								val data = when (x) {
									is String -> {
										x.encodeToByteArray()
									}

									is ByteArray -> {
										x
									}

									else -> {
										// impossible / null
										ByteArray(0)
									}
								}
								obj.put(column, Base64.encode(data))
							} else {
								obj.put(column, x)
							}
						}
					}
				}
			return@withContext obj
		}

	fun syncStart(
		callbackOutstanding: (Int) -> Unit,
		callbackError: (Exception) -> Unit,
		callbackDone: (Pair<Int, Int>) -> Unit
	) {
		DB.lastSync = System.currentTimeMillis()
		// first, verify correct sync version
		ConnectionUtil.getAppInfo {
			Log.d(TAG, "app info: $it")
			if (it != null) {
				Preferences.setDatabaseVersion(it.dbVersion)
				Preferences.setSyncVersion(it.syncVersion)
				if (Versions.SUPPORTED_SYNC_VERSIONS.contains(it.syncVersion) &&
					Versions.SUPPORTED_DATABASE_VERSIONS.contains(it.dbVersion)
				) {
					CoroutineScope(Dispatchers.IO).launch {
						sync(0, callbackOutstanding, callbackError, callbackDone)
					}
				} else {
					Log.e(
						TAG,
						"mismatched sync / database version (remote sync=${it.syncVersion}, remote db=${it.dbVersion}, supported sync=${Versions.SUPPORTED_SYNC_VERSIONS}, supported db=${Versions.SUPPORTED_DATABASE_VERSIONS})"
					)
					callbackError(Exception("mismatched sync / database version"))
				}
			} else {
				callbackError(IllegalStateException("did not receive app info"))
			}
		}
	}

	private suspend fun sync(
		alreadySynced: Int,
		callbackOutstanding: (Int) -> Unit,
		callbackError: (Exception) -> Unit,
		callbackDone: (Pair<Int, Int>) -> Unit
	): Unit = withContext(Dispatchers.IO) {
		try {
			// pull FIRST, push afterwards: applying remote deletions before
			// pushing prevents resurrecting notes that were deleted on another
			// device while this client still had unpushed local changes for
			// them (the stale row was pushed and re-created the note)
			var totalSynced = alreadySynced
			var outstandingPullCount = 1

			while (outstandingPullCount > 0 && coroutineContext.isActive) {
				var lastSyncedPull = 0
				DB.rawQuery("SELECT value FROM options WHERE name = ?", arrayOf("lastSyncedPull"))
					.use {
						if (it.moveToFirst()) {
							// NOTE: TriliumDroid will always save this as an int,
							// but the user may have imported a TriliumNext database where this is a float
							lastSyncedPull = it.getFloat(0).roundToInt()
						}
					}
				val logMarkerId = "trilium-droid"
				val changesUri =
					"/api/sync/changed?instanceId=${Preferences.instanceId()}&lastEntityChangeId=${lastSyncedPull}&logMarkerId=${logMarkerId}"

				var resp: JSONObject? = null
				var lastException: Exception? = null
				for (attempt in 1..5) {
					if (!coroutineContext.isActive) break
					try {
						resp = ConnectionUtil.doSyncRequestSuspend(changesUri)
						break
					} catch (e: IOException) {
						lastException = e
						Log.w(TAG, "Sync network error on attempt $attempt/5: ${e.message}. Retrying in 1.5s...")
						if (attempt < 5) {
							delay(1500)
						}
					} catch (e: Exception) {
						lastException = e
						break
					}
				}

				if (resp == null) {
					throw lastException ?: IOException("Failed to fetch sync changes after retries")
				}

				outstandingPullCount = resp.getInt("outstandingPullCount")
				val entityChangeId = resp.getInt("lastEntityChangeId")
				Log.i(TAG, "sync outstanding $outstandingPullCount, lastEntityChangeId $entityChangeId")

				val changes = resp.getJSONArray("entityChanges")
				totalSynced += changes.length()

				// Apply the whole chunk in a single transaction, so readers never see a
				// half-applied batch (e.g. a deleted parent note with its branch still
				// alive). NOTE: between begin and end no suspending DB helpers may be
				// used -- Android transactions are confined to the thread that opened
				// them and `DB.*` helpers may resume on another thread.
				val db = DB.internalGetDatabase()
				if (db == null || !db.isOpen) {
					throw IllegalStateException("database closed during sync")
				}
				val readOnly = Preferences.readOnlyMode()
				var cancelled = false
				db.beginTransactionNonExclusive()
				try {
					for (i in 0 until changes.length()) {
						if (!coroutineContext.isActive) {
							cancelled = true
							break
						}
						val change = changes.get(i) as JSONObject
						val entityChange = change.optJSONObject("entityChange")!!
						val entityName = entityChange.getString("entityName")
						val changeId = entityChange.getString("changeId")
						val entity = change.optJSONObject("entity")
						if (entity == null) {
							// deleted (erased) entity
							if (!readOnly) {
								val primaryKey = primaryKeyForTable(entityName)
								val entityId = entityChange.getString("entityId")
								db.delete(entityName, "$primaryKey = ?", arrayOf(entityId))
							}
							continue
						}
						val alreadyHere = db.rawQuery(
							"SELECT 1 FROM entity_changes WHERE changeId = ?",
							arrayOf(changeId)
						).use {
							it.count != 0
						}
						if (alreadyHere) {
							continue
						}

						if (entityName == "note_reordering") {
							for (key in entity.keys()) {
								val cvReordering = ContentValues(1)
								when (val positionValue = entity[key]) {
									is Int -> cvReordering.put("notePosition", positionValue)
									is Long -> cvReordering.put("notePosition", positionValue)
									is Double -> cvReordering.put("notePosition", positionValue)
									is String -> cvReordering.put("notePosition", positionValue)
									else -> cvReordering.put("notePosition", positionValue.toString())
								}
								db.update("branches", cvReordering, "branchId = ?", arrayOf(key))
							}
							continue
						}
						val keys = entity.keys().asSequence().toList()

						val cv = ContentValues(entity.length())
						keys.map { fieldName ->
							var x = entity.get(fieldName)
							if (x == JSONObject.NULL) {
								cv.putNull(fieldName)
							} else {
								if (fieldName.equals("content") && x is String) {
									val decoded = x.decodeBase64()
									if (decoded == null) {
										throw IllegalStateException("failed to base64-decode content column in sync response")
									}
									x = decoded.toByteArray()
								}
								when (x) {
									is String -> cv.put(fieldName, x)
									is Int -> cv.put(fieldName, x)
									is Double -> cv.put(fieldName, x)
									is ByteArray -> cv.put(fieldName, x)
									else -> Log.e(TAG, "failed to recognize sync entity value $x of type ${x.javaClass}")
								}
							}
						}
						// read-only mode: should still receive database updates
						db.insertWithOnConflict(
							entityName,
							null,
							cv,
							SQLiteDatabase.CONFLICT_REPLACE
						)
					}
					if (!cancelled) {
						// advance the pull cursor only after the chunk was applied completely
						val utc = utcDateModified()
						val cvOption = ContentValues(3)
						cvOption.put("name", "lastSyncedPull")
						cvOption.put("value", entityChangeId)
						cvOption.put("utcDateModified", utc)
						db.insertWithOnConflict("options", null, cvOption, SQLiteDatabase.CONFLICT_REPLACE)
						db.setTransactionSuccessful()
					}
				} finally {
					db.endTransaction()
				}

				if (cancelled) {
					break
				}

				if (outstandingPullCount > 0) {
					callbackOutstanding(outstandingPullCount)
					delay(50)
				}
			}

			val totalPushed = syncPush()
			callbackDone(Pair(totalSynced, totalPushed))
		} catch (e: Exception) {
			Log.e(TAG, "sync error", e)
			callbackError(e)
		}
	}

	private fun primaryKeyForTable(table: String) = when (table) {
		"attachments" -> "attachmentId"
		"attributes" -> "attributeId"
		"blobs" -> "blobId"
		"branches" -> "branchId"
		"entity_changes" -> "changeId"
		"etapi_tokens" -> "" // TODO
		"notes" -> "noteId"
		"note_contents" -> "noteId" // is this still relevant?
		"options" -> "name"
		"recent_notes" -> "" // TODO
		"revisions" -> "revisionId"
		"note_embeddings" -> "embedId"
		"embedding_queue" -> "noteId" // unclear if this is synchronized
		"embedding_providers" -> "providerId" // unclear if this is synchronized
		else -> ""
	}
}
