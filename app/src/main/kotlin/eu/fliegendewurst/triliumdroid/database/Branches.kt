package eu.fliegendewurst.triliumdroid.database

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.widget.Toast
import androidx.core.database.getStringOrNull
import eu.fliegendewurst.triliumdroid.R
import eu.fliegendewurst.triliumdroid.data.Branch
import eu.fliegendewurst.triliumdroid.data.BranchId
import eu.fliegendewurst.triliumdroid.data.Note
import eu.fliegendewurst.triliumdroid.data.NoteId
import eu.fliegendewurst.triliumdroid.database.Cache.utcDateModified
import eu.fliegendewurst.triliumdroid.database.Notes.notes
import eu.fliegendewurst.triliumdroid.service.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object Branches {
	private const val TAG = "Branches"

	/**
	 * The root branch.
	 */
	val NONE_ROOT = BranchId("none_root")

	/**
	 * Branches indexed by branch id.
	 */
	val branches: MutableMap<BranchId, Branch> = ConcurrentHashMap()

	suspend fun loadBranch(parentNoteId: NoteId, noteId: NoteId): Branch? =
		withContext(Dispatchers.IO) {
			val branchId = BranchId("${parentNoteId.rawId()}_${noteId.rawId()}")
			val existing = branches[branchId]
			if (existing != null) {
				return@withContext existing
			}
			DB.rawQuery(
				"SELECT isExpanded, notePosition, prefix FROM branches WHERE branchId = ? AND isDeleted = 0 LIMIT 1",
				arrayOf(branchId.rawId())
			).use {
				if (it.moveToNext()) {
					val expanded = it.getInt(0).intValueToBool()
					val notePosition = it.getInt(1)
					val prefix = it.getStringOrNull(2)
					val branch =
						Branch(branchId, noteId, parentNoteId, notePosition, prefix, expanded)
					branches[branchId] = branch
					return@withContext branch
				}
			}
			return@withContext null
		}

	/**
	 * Get one possible note path for the provided note.
	 * For deleted notes, this path is probably empty.
	 * The returned path starts with the innermost branch.
	 */
	suspend fun getNotePath(id: NoteId): List<Branch> = withContext(Dispatchers.IO) {
		val l = mutableListOf<Branch>()
		var lastId = id
		while (true) {
			val shouldBreak = DB.rawQuery(
				"SELECT branchId, parentNoteId, isExpanded, notePosition, prefix FROM branches WHERE noteId = ? AND isDeleted = 0 LIMIT 1",
				arrayOf(lastId.id)
			).use {
				if (it.moveToNext()) {
					val branchId = BranchId(it.getString(0))
					val parentId = NoteId(it.getString(1))
					val expanded = it.getInt(2) == 1
					val notePosition = it.getInt(3)
					val prefix = it.getStringOrNull(4)
					if (branches.containsKey(branchId)) {
						l.add(branches[branchId]!!)
					} else {
						val branch =
							Branch(branchId, lastId, parentId, notePosition, prefix, expanded)
						l.add(branch)
					}
					if (parentId == Notes.NONE) { // root reached
						return@use true
					}
					lastId = parentId
				} else {
					return@use true
				}
				return@use false
			}
			if (shouldBreak) {
				break
			}
		}
		return@withContext l
	}

	/**
	 * Return all note paths to a given note.
	 */
	suspend fun getNotePaths(noteId: NoteId): List<List<Branch>>? {
		val branches = Notes.getNote(noteId)?.branches ?: return null
		var possibleBranches = branches.map { x -> listOf(x) }.toMutableList()
		while (true) {
			val newPossibleBranches = mutableListOf<List<Branch>>()
			var progress = false
			for (path in possibleBranches) {
				if (path.last().note == Notes.ROOT) {
					newPossibleBranches.add(path)
					continue
				}
				// parent note may be missing (deleted on another device and already
				// synced away locally) -- drop the broken path instead of crashing
				val note = Notes.getNote(path.last().parentNote) ?: continue
				for (branch in note.branches) {
					val newPath = path.toMutableList()
					newPath.add(branch)
					newPossibleBranches.add(newPath)
					progress = true
				}
			}
			possibleBranches = newPossibleBranches
			if (!progress) {
				break
			}
		}
		return possibleBranches
	}

	suspend fun cloneNote(parentBranch: Branch, note: Note) {
		return cloneNote(parentBranch.note, note.id)
	}

	suspend fun cloneNote(parentNoteId: NoteId, noteId: NoteId) = withContext(Dispatchers.IO) {
		// first, make sure we aren't creating a cycle
		val paths = getNotePaths(parentNoteId) ?: return@withContext
		if (paths.any {
				it.slice(1 until it.size).any { otherBranch -> otherBranch.note == noteId }
			}) {
			Toast.makeText(
				DB.applicationContext,
				R.string.toast_clone_would_cycle,
				Toast.LENGTH_LONG
			).show()
			Log.w(TAG, "refused to create cycle @ parent = $parentNoteId note = $noteId")
			return@withContext
		}
		// create new branch
		val branchId = "${parentNoteId.rawId()}_${noteId.rawId()}"
		// check if it is used
		DB.rawQuery(
			"SELECT 1 FROM branches WHERE branchId = ? AND isDeleted = 0",
			arrayOf(branchId)
		)
			.use {
				if (it.moveToNext()) {
					return@withContext
				}
			}
		// TODO: proper position
		val utc = utcDateModified()
		DB.insertWithConflict(
			"branches",
			SQLiteDatabase.CONFLICT_REPLACE,
			Pair("branchId", branchId),
			Pair("noteId", noteId),
			Pair("parentNoteId", parentNoteId),
			Pair("notePosition", 0),
			Pair("prefix", null),
			Pair("isExpanded", false),
			Pair("isDeleted", false),
			Pair("deleteId", null),
			Pair("utcDateModified", utc)
		)
		registerEntityChangeBranch(Branch(BranchId(branchId), noteId, parentNoteId, 0, null, false))
	}

	suspend fun toggleBranch(branch: Branch) = withContext(Dispatchers.IO) {
		DB.update(branch.id, Pair("isExpanded", !branch.expanded))
		val newValue = !branch.expanded
		branch.expanded = newValue
		branches[branch.id]?.expanded = newValue
	}

	fun hasExpandedBranchesBelowRoot(): Boolean {
		return branches.values.any { it.id != NONE_ROOT && it.expanded }
	}

	suspend fun collapseAllBelowRoot() = withContext(Dispatchers.IO) {
		DB.internalGetDatabase()?.execSQL("UPDATE branches SET isExpanded = 0 WHERE branchId != 'none_root'")
		DB.internalGetDatabase()?.execSQL("UPDATE branches SET isExpanded = 1 WHERE branchId = 'none_root'")
		for ((id, b) in branches) {
			b.expanded = (id == NONE_ROOT)
		}
	}

	suspend fun moveBranch(branch: Branch, newParent: Branch, newPosition: Int) =
		withContext(Dispatchers.IO) {
			Log.i(
				TAG,
				"moving branch ${branch.id} to new parent ${newParent.note}, pos: ${branch.position} -> $newPosition"
			)
			if (branch.parentNote == newParent.note && branch.position == newPosition) {
				return@withContext branch // no action needed
			}
			val newId = BranchId("${newParent.note.rawId()}_${branch.note.rawId()}")
			val idChanged = branch.id != newId
			val utc = utcDateModified()
			val res = DB.insertWithConflict(
				newId.tableName(), SQLiteDatabase.CONFLICT_REPLACE,
				Pair("branchId", newId),
				Pair("noteId", branch.note),
				Pair("parentNoteId", newParent.note),
				Pair("notePosition", newPosition),
				Pair("prefix", branch.prefix),
				Pair("isExpanded", branch.expanded),
				Pair("isDeleted", false),
				Pair("deleteId", null),
				Pair("utcDateModified", utc)
			)
			if (res == -1L) {
				Log.e(TAG, "error moving branch!")
			}
			if (idChanged) {
				delete(branch)
				branch.id = newId
			}
			val oldParent = branch.parentNote
			branch.parentNote = newParent.note
			registerEntityChangeBranch(branch)
			notes[oldParent]?.children = null
			notes[newParent.note]?.children = null
			Tree.getTreeData("AND (branches.parentNoteId = '${oldParent.rawId()}' OR branches.parentNoteId = '${newParent.note.rawId()}')")
		}

	suspend fun delete(branch: Branch) = withContext(Dispatchers.IO) {
		Log.i(TAG, "deleting ${branch.id}")
		DB.update(branch.id, Pair("isDeleted", 1), Pair("deleteId", Util.newDeleteId()))
		registerEntityChangeBranch(branch)
		branches.remove(branch.id)
	}

	/**
	 * Mark branches as deleted whose parent or child note no longer exists locally.
	 * Such rows can be left behind when a branch deletion change was missed
	 * (e.g. erased entities on the server, or gaps after a server backup restore).
	 * The deletions are registered, so the next push converges with the server.
	 * Must only be called after a completed sync.
	 */
	suspend fun cleanupOrphanedBranches(): Int = withContext(Dispatchers.IO) {
		val orphans = mutableListOf<Branch>()
		DB.rawQuery(
			"SELECT branchId, noteId, parentNoteId, notePosition, prefix, isExpanded FROM branches " +
					"WHERE isDeleted = 0 AND parentNoteId != ? " +
					"AND (COALESCE((SELECT isDeleted FROM notes WHERE noteId = branches.parentNoteId), 1) != 0 " +
					"OR COALESCE((SELECT isDeleted FROM notes WHERE noteId = branches.noteId), 1) != 0)",
			arrayOf(Notes.NONE.id)
		).use {
			while (it.moveToNext()) {
				orphans.add(
					Branch(
						BranchId(it.getString(0)),
						NoteId(it.getString(1)),
						NoteId(it.getString(2)),
						it.getInt(3),
						it.getStringOrNull(4),
						it.getInt(5) == 1
					)
				)
			}
		}
		for (branch in orphans) {
			Log.w(TAG, "removing orphaned branch ${branch.id} (parent ${branch.parentNote}, note ${branch.note})")
			delete(branch)
		}
		orphans.size
	}
}

private suspend fun registerEntityChangeBranch(branch: Branch) {
	// hash ["branchId", "noteId", "parentNoteId", "prefix"]
	// source: https://github.com/TriliumNext/Notes/blob/develop/src/becca/entities/bbranch.ts
	Cache.registerEntityChange(
		"branches",
		branch.id.id,
		arrayOf(branch.id.id, branch.note.id, branch.parentNote.id, branch.prefix ?: "null")
	)
}
