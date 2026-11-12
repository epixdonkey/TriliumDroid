package eu.fliegendewurst.triliumdroid.database

import android.util.Log
import eu.fliegendewurst.triliumdroid.data.BlobId
import eu.fliegendewurst.triliumdroid.data.Branch
import eu.fliegendewurst.triliumdroid.data.BranchId
import eu.fliegendewurst.triliumdroid.data.Note
import eu.fliegendewurst.triliumdroid.data.NoteId
import eu.fliegendewurst.triliumdroid.database.Branches.branches
import eu.fliegendewurst.triliumdroid.database.Notes.notes
import eu.fliegendewurst.triliumdroid.service.Icon
import eu.fliegendewurst.triliumdroid.service.Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Helper functions for inspecting the note tree.
 */
object Tree {
	private const val TAG: String = "Tree"
	private val LOCK = Mutex()

	/**
	 * Set when a local change requires a full tree rebuild but the caller
	 * chose not to rebuild immediately (e.g. because an upcoming auto-sync
	 * completion will do it). Cleared by every successful full rebuild.
	 */
	@Volatile
	var fullRebuildPending: Boolean = false
		private set

	/**
	 * True while a full rebuild has been requested but has not finished yet
	 * (including time spent waiting for the lock).
	 */
	@Volatile
	var fullRebuildInFlight: Boolean = false
		private set

	fun markFullRebuildPending() {
		fullRebuildPending = true
	}

	/**
	 * Populate the tree data cache.
	 */
	suspend fun getTreeData(filter: String) {
		val fullRebuild = filter == ""
		if (fullRebuild) {
			fullRebuildInFlight = true
		}
		try {
			LOCK.withLock {
				if (!DB.isOpen()) {
					Log.w(TAG, "getTreeData called but database is not open")
					return@withLock
				}
				var attempts = 0
				while (true) {
					attempts++
					try {
						if (getTreeDataInternal(filter)) {
							if (fullRebuild) {
								fullRebuildPending = false
							}
							return@withLock
						}
					} catch (e: CancellationException) {
						throw e
					} catch (e: IllegalStateException) {
						Log.w(TAG, "getTreeData aborted: database pool closed", e)
					} catch (e: Exception) {
						Log.e(TAG, "getTreeData failed", e)
						return@withLock
					}
					// the branch walk aborted part-way (e.g. a CursorWindow failure
					// while another thread writes to the database) and left a
					// cleared, partial cache -- retry the full rebuild once
					if (attempts >= 2 || !DB.isOpen()) {
						Log.e(TAG, "getTreeData: rebuild aborted twice, giving up (partial tree until the next rebuild)")
						return@withLock
					}
					Log.w(TAG, "getTreeData: branch walk aborted, retrying full rebuild")
				}
			}
		} finally {
			if (fullRebuild) {
				fullRebuildInFlight = false
			}
		}
	}

	/**
	 * Get the note tree starting at the id and level.
	 */
	suspend fun getTreeList(branchId: BranchId, lvl: Int): MutableList<Pair<Branch, Int>> {
		return withContext(Dispatchers.IO) {
			LOCK.withLock {
				getTreeListInternal(branchId, lvl)
			}
		}
	}

	private fun getTreeListInternal(branchId: BranchId, lvl: Int): MutableList<Pair<Branch, Int>> {
		val list = ArrayList<Pair<Branch, Int>>()
		val current = branches[branchId] ?: return list
		list.add(Pair(current, lvl))
		if (!current.expanded) {
			return list
		}
		// the note may be missing from the cache while a deletion is in progress:
		// Notes.deleteNote removes the note before Branches.delete removes the branch.
		// Skip the subtree instead of crashing; the next full rebuild shows the consistent state.
		val note = notes[current.note] ?: return list
		for (children in note.children.orEmpty()) {
			list.addAll(getTreeListInternal(children.id, lvl + 1))
		}
		return list
	}

	/**
	 * @return false if the branch walk aborted part-way and the cache is left
	 * in a partial state that warrants a full retry; true otherwise.
	 */
	private suspend fun getTreeDataInternal(filter: String): Boolean = withContext(Dispatchers.IO) {
		if (!currentCoroutineContext().isActive || !DB.isOpen()) {
			return@withContext true
		}
		val startTime = System.currentTimeMillis()
		if (filter == "") {
			// regenerate all! Clearing the map here (inside LOCK) invalidates cached
			// note contents and titles atomically; concurrent readers simply re-fetch
			// from the database. This used to be done by Notes.clearCache() called
			// after sync WITHOUT the lock, which raced with in-flight rebuilds and
			// gutted the tree (notes missing -> "failed to find root", 1-item tree).
			notes.clear()
		}
		try {
			DB.rawQuery(
			"SELECT branchId, " +
					"branches.noteId, " +
					"parentNoteId, " +
					"notePosition, " +
					"prefix, " +
					"isExpanded, " +
					"mime, " +
					"title, " +
					"notes.type, " +
					"notes.dateCreated, " +
					"notes.dateModified, " +
					"notes.isProtected, " +
					"notes.utcDateCreated, " +
					"notes.utcDateModified, " +
					"notes.blobId " +
					"FROM branches INNER JOIN notes USING (noteId) WHERE notes.isDeleted = 0 AND branches.isDeleted = 0 $filter",
			arrayOf()
		).use {
			val clones = mutableListOf<Pair<Pair<NoteId, NoteId>, BranchId>>()
			while (currentCoroutineContext().isActive && DB.isOpen() && it.moveToNext()) {
				val branchId = BranchId(it.getString(0))
				val noteId = NoteId(it.getString(1))
				val parentNoteId = NoteId(it.getString(2))
				val notePosition = it.getInt(3)
				val prefix = if (!it.isNull(4)) {
					it.getString(4)
				} else {
					null
				}
				val isExpanded = it.getInt(5) == 1
				val mime = it.getString(6)
				val title = it.getString(7)
				val type = it.getString(8)
				val dateCreated = it.getString(9)
				val dateModified = it.getString(10)
				val isProtected = it.getInt(11) != 0
				val utcDateCreated = it.getString(12)
				val utcDateModified = it.getString(13)
				val blobId = BlobId(it.getString(14))
				val b = Branch(
					branchId,
					noteId,
					parentNoteId,
					notePosition,
					prefix,
					isExpanded
				)
				branches[branchId] = b
				val n = notes.computeIfAbsent(noteId) {
					Note(
						noteId,
						mime,
						title,
						type,
						false,
						null,
						dateCreated,
						dateModified,
						utcDateCreated,
						utcDateModified,
						isProtected,
						blobId
					)
				}
				n.branches.removeIf { branch -> branch.id == branchId }
				n.branches.add(b)
				n.branches.sortBy { br -> br.position } // TODO: sort by date instead?
				clones.add(Pair(Pair(parentNoteId, noteId), branchId))
			}
			for (p in clones) {
				val parentNoteId = p.first.first
				val b = branches[p.second]
				if (parentNoteId == Notes.NONE || b == null) {
					continue
				}
				val parentNote = notes[parentNoteId]
				if (parentNote != null) {
					if (parentNote.children == null) {
						parentNote.children = TreeSet()
					}
					parentNote.children!!.removeIf { it.id == b.id }
					parentNote.children!!.add(b)
				} else {
					// branch points to a deleted (or not yet pulled) parent;
					// harmless: the post-sync orphan cleanup removes such branches
					Log.i(TAG, "branch of $parentNoteId dangles, ignoring (cleaned up after sync)")
				}
			}
		}
		} catch (e: IllegalStateException) {
			Log.w(TAG, "getTreeDataInternal: branch walk aborted (cursor window / pool closed)", e)
			return@withContext false
		}

		if (!currentCoroutineContext().isActive || !DB.isOpen()) {
			return@withContext true
		}

		val query1 = System.currentTimeMillis() - startTime
		val query2Start = System.currentTimeMillis()
		val stats = if (filter == "") {
			mutableMapOf<String, Int>()
		} else {
			null
		}
		try {
			DB.rawQuery(
				"SELECT notes.noteId, tpl.tplIcon " +
						"FROM attributes " +
						"INNER JOIN notes USING (noteId) " +
						"INNER JOIN branches USING (noteId) " +
						"INNER JOIN (SELECT noteId AS tplNoteId, value AS tplIcon FROM attributes WHERE isDeleted = 0 AND name = 'iconClass' AND type = 'label') tpl ON tpl.tplNoteId = attributes.value " +
						"WHERE notes.isDeleted = 0 AND attributes.isDeleted = 0 " +
						"AND attributes.name = 'template' AND attributes.type = 'relation' $filter",
				arrayOf()
			).use {
				while (currentCoroutineContext().isActive && DB.isOpen() && it.moveToNext()) {
					val noteId = NoteId(it.getString(0))
					val noteIcon = it.getString(1)
					notes[noteId]?.icon = noteIcon
				}
			}
		} catch (e: IllegalStateException) {
			Log.w(TAG, "getTreeDataInternal: database pool closed while loading templates", e)
			return@withContext true
		}

		if (!currentCoroutineContext().isActive || !DB.isOpen()) {
			return@withContext true
		}

		try {
			DB.rawQuery(
				"SELECT notes.noteId, attributes.value " +
						"FROM attributes " +
						"INNER JOIN notes USING (noteId) " +
						"INNER JOIN branches USING (noteId) " +
						"WHERE notes.isDeleted = 0 AND attributes.isDeleted = 0 " +
						"AND attributes.name = 'iconClass' AND attributes.type = 'label' $filter",
				arrayOf()
			).use {
				while (currentCoroutineContext().isActive && DB.isOpen() && it.moveToNext()) {
					val noteId = NoteId(it.getString(0))
					val noteIcon = it.getString(1)
					val note = notes[noteId]
					if (note == null) {
						// note has an icon but no live branch; expected after
						// deletions on other clients until the cleanup pass runs
						Log.i(TAG, "note $noteId has no live branch, skipping icon")
						continue
					}
					note.icon = noteIcon
					// gather statistics on note icons of user notes
					if (stats != null && Util.isRegularId(noteId)) {
						stats[noteIcon] = stats.getOrDefault(noteIcon, 0) + 1
					}
				}
			}
		} catch (e: IllegalStateException) {
			Log.w(TAG, "getTreeDataInternal: database pool closed while loading icons", e)
			return@withContext true
		}
		if (stats != null) {
			Icon.iconStatistics =
				stats.entries.sortedWith(Comparator.comparingInt<MutableMap.MutableEntry<String, Int>?> { -it.value }
					.thenComparing { it -> it.key })
					.map { Pair(it.key, it.value) }
		}
		val query2 = System.currentTimeMillis() - query2Start
		if (query1 + query2 > 50) {
			Log.w(TAG, "slow getTreeData() $query1 ms + $query2 ms")
		}
		true
	}
}
