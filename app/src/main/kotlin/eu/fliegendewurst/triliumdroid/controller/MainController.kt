package eu.fliegendewurst.triliumdroid.controller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.icu.util.Calendar
import android.os.Build
import android.os.StrictMode
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity.NOTIFICATION_SERVICE
import androidx.core.content.FileProvider
import androidx.core.text.parseAsHtml
import androidx.lifecycle.lifecycleScope
import com.wdullaer.materialdatetimepicker.date.DatePickerDialog
import eu.fliegendewurst.triliumdroid.AlarmReceiver
import eu.fliegendewurst.triliumdroid.R
import eu.fliegendewurst.triliumdroid.activity.AboutActivity
import eu.fliegendewurst.triliumdroid.activity.SetupActivity
import eu.fliegendewurst.triliumdroid.activity.WelcomeActivity
import eu.fliegendewurst.triliumdroid.activity.main.BlobItem
import eu.fliegendewurst.triliumdroid.activity.main.HistoryItem
import eu.fliegendewurst.triliumdroid.activity.main.HistoryList
import eu.fliegendewurst.triliumdroid.activity.main.MainActivity
import eu.fliegendewurst.triliumdroid.activity.main.NavigationItem
import eu.fliegendewurst.triliumdroid.activity.main.NoteEditItem
import eu.fliegendewurst.triliumdroid.activity.main.NoteItem
import eu.fliegendewurst.triliumdroid.activity.main.NoteMapItem
import eu.fliegendewurst.triliumdroid.activity.main.QuickNoteItem
import eu.fliegendewurst.triliumdroid.data.Branch
import eu.fliegendewurst.triliumdroid.data.Note
import eu.fliegendewurst.triliumdroid.data.NoteId
import eu.fliegendewurst.triliumdroid.data.NoteRevision
import eu.fliegendewurst.triliumdroid.data.Relation
import eu.fliegendewurst.triliumdroid.database.Attributes
import eu.fliegendewurst.triliumdroid.database.Branches
import eu.fliegendewurst.triliumdroid.database.DB
import eu.fliegendewurst.triliumdroid.database.NoteRevisions
import eu.fliegendewurst.triliumdroid.database.Notes
import eu.fliegendewurst.triliumdroid.database.Tree
import eu.fliegendewurst.triliumdroid.dialog.AskForNameDialog
import eu.fliegendewurst.triliumdroid.dialog.ConfigureFabsDialog
import eu.fliegendewurst.triliumdroid.dialog.ConfigureWidgetDialog
import eu.fliegendewurst.triliumdroid.dialog.CreateNewNoteDialog
import eu.fliegendewurst.triliumdroid.dialog.JumpToNoteDialog
import eu.fliegendewurst.triliumdroid.dialog.NoteIconDialog
import eu.fliegendewurst.triliumdroid.dialog.SelectNoteDialog
import eu.fliegendewurst.triliumdroid.dialog.YesNoDialog
import eu.fliegendewurst.triliumdroid.fragment.EmptyFragment
import eu.fliegendewurst.triliumdroid.fragment.NoteEditFragment
import eu.fliegendewurst.triliumdroid.fragment.NoteMapFragment
import eu.fliegendewurst.triliumdroid.fragment.SyncErrorFragment
import eu.fliegendewurst.triliumdroid.fragment.note.CanvasNoteFragment
import eu.fliegendewurst.triliumdroid.fragment.note.NoteFragment
import eu.fliegendewurst.triliumdroid.service.DateNotes
import eu.fliegendewurst.triliumdroid.service.Icon
import eu.fliegendewurst.triliumdroid.service.ProtectedSession
import eu.fliegendewurst.triliumdroid.sync.ConnectionUtil
import eu.fliegendewurst.triliumdroid.sync.Sync
import eu.fliegendewurst.triliumdroid.util.BiometricAuthManager
import eu.fliegendewurst.triliumdroid.util.CrashReport
import eu.fliegendewurst.triliumdroid.util.Preferences
import eu.fliegendewurst.triliumdroid.widget.NoteWidget
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes


/**
 * Controller for the main activity.
 * Receives user input events and notifies UI about changes.
 */
class MainController {
	companion object {
		private const val TAG = "MainController"
		const val ACTION_QUICK_NOTE = "eu.fliegendewurst.triliumdroid.action.QUICK_NOTE"
	}

	/**
	 * Navigation stack.
	 */
	private val noteHistory = HistoryList()

	/**
	 * First action to execute, used to implement widgets.
	 */
	private var firstAction: HistoryItem? = null

	/**
	 * Widget action that arrived while the app was locked, executed after authentication.
	 */
	private var actionAfterUnlock: HistoryItem? = null

	/**
	 * Actions available during a restricted quick note session without authentication.
	 */
	private val quickNoteAllowedActions = setOf(R.id.action_edit, R.id.action_sync)

	// initial note to show
	private var firstNote: NoteId? = null

	/**
	 * Whether to show the next sync error as a full [SyncErrorFragment]
	 */
	private var showSyncError: Boolean = false

	/**
	 * True iff the activity is currently running, i.e. after onResume but before onPause.
	 */
	private var active: Boolean = false

	/**
	 * When unloading the note on screen leave, the note ID that was loaded.
	 */
	private var loadedNoteId: NoteId? = null

	private var timeOnStart = 0L

	/**
	 * Tracks whether the editor was opened as part of a quick note session.
	 * Auto-sync is deferred until the user saves or finishes editing this note.
	 */
	private var isQuickNoteSession: Boolean = false

	/**
	 * The note created for the current quick note session, if any.
	 * If the user leaves the editor without modifying the note, this empty
	 * stub is deleted again (only ever the stub itself, never any ancestor).
	 */
	private var quickNotePendingId: NoteId? = null

	/**
	 * Called when the app is created, possibly in the background.
	 */
	fun onCreate(activity: MainActivity, intent: Intent) {
		oneTimeSetup(activity)

		Preferences.init(activity.applicationContext)
		DB.applicationContext = activity.applicationContext
		// parse the material icon table off the main thread; lookups load it
		// on demand if they happen to run before this finishes
		Thread { eu.fliegendewurst.triliumdroid.service.Icon.ensureMaterialLoaded(activity.applicationContext) }.start()
		ConfigureFabsDialog.init()

		if (DB.haveDatabase(activity)) {
			runBlocking { DB.initializeDatabase(activity.applicationContext) }
		}

		val firstNoteRaw = intent.extras?.getString("note")
		if (firstNoteRaw != null) {
			firstNote = NoteId(firstNoteRaw)
		}

		val appWidgetId = intent.extras?.getInt("appWidgetId")
		if (appWidgetId != null) {
			widgetUsed(activity, appWidgetId)
		}

		if (intent.action == ACTION_QUICK_NOTE) {
			firstAction = QuickNoteItem()
		}

		if (intent.action == Intent.ACTION_SEND) {
			if (intent.type?.startsWith("text/") == true) {
				runBlocking { textShared(activity, intent) }
			}
			activity.finish()
		}
	}

	fun onNewIntent(activity: MainActivity, intent: Intent) {
		if (intent.action == Intent.ACTION_SEND) {
			if (intent.type?.startsWith("text/") == true) {
				runBlocking { textShared(activity, intent) }
			}
		} else if (intent.action == ACTION_QUICK_NOTE) {
			firstAction = QuickNoteItem()
		}
	}

	/**
	 * Called when the app is started.
	 *
	 * Possible outcomes:
	 * - database present
	 * 	 1. sync not configured → SetupActivity
	 * 	 2. not synced yet → start sync, show initial note
	 * 	 3. already synced → show initial note
	 * - database not present → WelcomeActivity
	 */
	fun onStart(activity: MainActivity) {
		timeOnStart = System.currentTimeMillis()

		// cover the content right away, so no note is visible before authentication
		if (BiometricAuthManager.isLocked() && firstAction !is QuickNoteItem) {
			activity.setLockOverlayVisible(true)
		}

		CrashReport.showPendingReports(activity)

		if (firstAction != null) {
			if (DB.haveDatabase(activity)) {
				runBlocking { DB.initializeDatabase(activity.applicationContext) }
			}
			return
		}
		if (DB.haveDatabase(activity)) {
			if (!Preferences.databaseConfigured(activity)) {
				Log.i(TAG, "starting setup!")
				val intent = Intent(activity, SetupActivity::class.java)
				activity.startActivity(intent)
			} else if (DB.lastSync == null && noteHistory.isEmpty() && Preferences.syncConfigured()) {
				activity.lifecycleScope.launch {
					ConnectionUtil.setup(activity, {
						activity.handler.post {
							startSync(activity)
						}
					}, {
						activity.handler.post {
							handleError(activity, it)
						}
					})
					Tree.getTreeData("")
					showInitialNote(activity, true)
				}
			} else if (noteHistory.isEmpty()) {
				Log.d(TAG, "last sync is ${DB.lastSync}, showing initial note")
				activity.lifecycleScope.launch {
					Tree.getTreeData("")
					showInitialNote(activity, true)
				}
			}
		} else {
			Log.d(TAG, "no database, starting welcome activity")
			val intent = Intent(activity, WelcomeActivity::class.java)
			activity.startActivity(intent)
		}
	}

	fun onResume(activity: MainActivity) {
		active = true
		val theAction = firstAction
		firstAction = null
		if (theAction is QuickNoteItem) {
			BiometricAuthManager.enterQuickNoteRestrictedSession()
			activity.setLockOverlayVisible(false)
			activity.setDrawersLocked(BiometricAuthManager.isQuickNoteRestricted())
			createQuickNote(activity)
			return
		}
		if (BiometricAuthManager.isLocked() && !BiometricAuthManager.isQuickNoteRestricted()) {
			actionAfterUnlock = theAction ?: actionAfterUnlock
			activity.setLockOverlayVisible(true)
			activity.setDrawersLocked(true)
			requestUnlock(activity)
			return
		}
		activity.setDrawersLocked(BiometricAuthManager.isQuickNoteRestricted())
		resumeContent(activity, theAction)
	}

	/**
	 * Show the system authentication prompt; on success, the lock screen is removed.
	 * If the prompt is cancelled, the lock screen stays (with its unlock button).
	 */
	fun requestUnlock(activity: MainActivity) {
		BiometricAuthManager.authenticate(activity, onSuccess = {
			activity.setLockOverlayVisible(false)
			activity.setDrawersLocked(false)
			val pendingAction = actionAfterUnlock
			actionAfterUnlock = null
			resumeContent(activity, pendingAction)
		})
	}

	private fun resumeContent(activity: MainActivity, theAction: HistoryItem?) {
		if (theAction != null) {
			activity.lifecycleScope.launch {
				Tree.getTreeData("")
				activity.refreshTree()
				noteHistory.addAndRestore(theAction, activity)
			}
			return
		}
		// tree items may disappear if app gets unloaded too much
		// (obviously not needed if app just started)
		if (activity.treeIsEmpty() && (System.currentTimeMillis() - timeOnStart) > 1000) {
			activity.lifecycleScope.launch {
				Tree.getTreeData("")
				activity.refreshTree()
			}
		}
		// if the user deleted the database, nuke the history too
		if (!Preferences.hasSyncContext() || !DB.haveDatabase(activity)) {
			noteHistory.reset()
			loadedNoteId = null
			activity.tree?.submitList(emptyList())
			activity.refreshTitle(null)
			activity.showFragment(EmptyFragment(), true)
			return
		}
		if (loadedNoteId == null) {
			return
		}
		when (activity.getFragment()) {
			is EmptyFragment -> {
				activity.lifecycleScope.launch {
					noteHistory.restore(activity)
				}
			}
		}
		loadedNoteId = null
	}

	fun handleOnBackPressed(activity: MainActivity) {
		if (activity.isDrawerOpen()) {
			activity.closeDrawers()
			return
		}
		val frag = if (activity.hasFragment()) activity.getFragment() else null
		if (frag is NoteEditFragment) {
			finishEditing(activity, frag)
			return
		}
		if (noteHistory.goBack(activity)) {
			activity.finish()
		}
	}

	/**
	 * Save the note being edited and leave the editor.
	 * An empty quick note is deleted again (stub discard); the decision is based
	 * on the note's actual content, since the editor autosave may flush the text
	 * before the user leaves (making the `modified` flag unreliable).
	 * Leaving a quick note session never closes the app on its own: if there is
	 * nothing to go back to (cold start from the widget), the regular app home
	 * screen (note tree + initial note) is shown instead. At the end of a
	 * restricted quick note session, authentication is required to see anything.
	 */
	private fun finishEditing(activity: MainActivity, fragment: NoteEditFragment) {
		val wasQuickNoteSession = isQuickNoteSession || BiometricAuthManager.isQuickNoteRestricted()
		val pendingQuickNoteId = quickNotePendingId
		isQuickNoteSession = false
		quickNotePendingId = null
		fragment.saveAndFinish { modified ->
			if (wasQuickNoteSession && pendingQuickNoteId != null) {
				activity.lifecycleScope.launch {
					if (!modified || quickNoteStubIsEmpty(pendingQuickNoteId)) {
						// quick note left empty: discard the stub note
						discardQuickNote(activity, pendingQuickNoteId)
						// in a restricted session nothing may be shown without authentication;
						// otherwise return to what was open before the quick note
						if (BiometricAuthManager.isQuickNoteRestricted() || noteHistory.goBackTwice(activity)) {
							showAppHome(activity)
						}
					} else {
						// quick note with content (saved now or flushed by the editor
						// autosave earlier): refresh the tree and sync, regardless of
						// the modified flag
						Tree.getTreeData("")
						activity.refreshTree()
						triggerAutoSyncIfEnabled(activity)
						finishEditingNavigateBack(activity, wasQuickNoteSession)
					}
				}
				return@saveAndFinish
			}

			if (modified) {
				activity.lifecycleScope.launch {
					Tree.getTreeData("")
					activity.refreshTree()
					triggerAutoSyncIfEnabled(activity)
				}
			}

			finishEditingNavigateBack(activity, wasQuickNoteSession)
		}
	}

	/**
	 * Authentication (in a restricted quick note session) and history navigation
	 * after leaving the editor.
	 */
	private fun finishEditingNavigateBack(activity: MainActivity, wasQuickNoteSession: Boolean) {
		if (BiometricAuthManager.isQuickNoteRestricted()) {
			BiometricAuthManager.authenticate(activity, onSuccess = {
				activity.setDrawersLocked(false)
				if (noteHistory.goBack(activity)) {
					showAppHome(activity)
				}
			}, onCancelOrError = {
				activity.finish()
			})
			return
		}

		if (noteHistory.goBack(activity)) {
			if (wasQuickNoteSession) {
				showAppHome(activity)
			} else {
				activity.finish()
			}
		}
	}

	/**
	 * Whether the quick note stub still has no user-visible content.
	 * The CKEditor empty document ("<p><br></p>") counts as empty as well.
	 */
	private suspend fun quickNoteStubIsEmpty(noteId: NoteId): Boolean {
		val note = Notes.getNoteWithContent(noteId) ?: return true
		val content = note.content()?.decodeToString() ?: return true
		return content.isBlank() || content.replace(Regex("<[^>]*>"), "").isBlank()
	}

	/**
	 * Land on the regular app home screen (note tree + initial note), asking for
	 * authentication first if the app is locked. Used when leaving a quick note
	 * session that has nothing to go back to.
	 */
	private fun showAppHome(activity: MainActivity) {
		if (BiometricAuthManager.isLocked()) {
			activity.setLockOverlayVisible(true)
			activity.setDrawersLocked(true)
			BiometricAuthManager.authenticate(activity, onSuccess = {
				activity.setLockOverlayVisible(false)
				activity.setDrawersLocked(false)
				showAppHomeUnlocked(activity)
			}, onCancelOrError = {
				activity.finish()
			})
			return
		}
		showAppHomeUnlocked(activity)
	}

	private fun showAppHomeUnlocked(activity: MainActivity) {
		noteHistory.reset()
		activity.lifecycleScope.launch {
			Tree.getTreeData("")
			activity.refreshTree()
			showInitialNote(activity, true)
		}
	}

	/**
	 * Delete the empty stub note of a discarded quick note session.
	 * Only ever deletes the stub itself (its own branch), never any parent note.
	 */
	private suspend fun discardQuickNote(activity: MainActivity, noteId: NoteId) {
		val stub = Notes.getNote(noteId) ?: return
		val ownBranch = stub.branches.firstOrNull { it.note == noteId }
			?: Branches.getNotePath(noteId).firstOrNull { it.note == noteId }
		if (ownBranch == null) {
			Log.w(TAG, "quick note stub $noteId has no branch, not discarding")
			return
		}
		if (!Notes.deleteNote(ownBranch)) {
			Log.w(TAG, "failed to delete quick note stub $noteId")
			return
		}
		triggerAutoSyncIfEnabled(activity)
	}

	fun onPause(activity: MainActivity) {
		// TODO: maybe save the edited content somewhere
		when (val fragment = activity.getFragment()) {
			is NoteFragment -> {
				loadedNoteId = fragment.getNoteId()
				activity.showFragment(EmptyFragment(), true)
			}

			is CanvasNoteFragment -> {
				loadedNoteId = fragment.getNoteId()
				activity.showFragment(EmptyFragment(), true)
			}
		}
		active = false
	}

	fun doMenuAction(activity: MainActivity, actionId: Int): Boolean {
		if (BiometricAuthManager.isQuickNoteRestricted() && actionId !in quickNoteAllowedActions) {
			// any access beyond the quick note requires authentication
			BiometricAuthManager.authenticate(activity, onSuccess = {
				activity.setDrawersLocked(false)
				performMenuAction(activity, actionId)
			})
			return true
		}
		return performMenuAction(activity, actionId)
	}

	private fun performMenuAction(activity: MainActivity, actionId: Int): Boolean = when (actionId) {
		R.id.action_note_metadata -> {
			activity.openDrawerMetadata()
			true
		}

		R.id.action_enter_protected_session -> {
			val error = ProtectedSession.enter()
			if (error != null) {
				Toast.makeText(activity, error, Toast.LENGTH_SHORT).show()
			} else {
				reloadNote(activity)
			}
			true
		}

		R.id.action_leave_protected_session -> {
			ProtectedSession.leave()
			reloadNote(activity)
			true
		}

		R.id.action_show_note_tree -> {
			activity.openDrawerTree()
			true
		}

		R.id.action_jump_to_note -> {
			JumpToNoteDialog.showDialog(activity)
			true
		}

		R.id.action_note_navigation -> {
			activity.lifecycleScope.launch {
				var note = Notes.getNote(noteHistory.noteId()) ?: return@launch
				var branch = noteHistory.branch() ?: note.branches.getOrNull(0) ?: return@launch
				if (note.computeChildren().isEmpty()) {
					note = Notes.getNote(branch.parentNote)!!
					branch = note.branches.getOrNull(0) ?: return@launch
				}
				noteHistory.addAndRestore(NavigationItem(note, branch), activity)
			}
			true
		}

		R.id.action_edit -> run {
			if (Preferences.readOnlyMode()) {
				return@run true
			}
			when (val fragment = activity.getFragment()) {
				is NoteFragment -> {
					val id = fragment.getNoteId() ?: return true
					noteHistory.addAndRestore(
						NoteEditItem(runBlocking { Notes.getNote(id)!! }),
						activity
					)
				}

				is NoteEditFragment -> {
					finishEditing(activity, fragment)
				}

				else -> {
					Log.e(TAG, "failed to identify fragment " + fragment.javaClass)
				}
			}
			true
		}

		R.id.action_share -> run {
			val id = activity.getNoteFragment().getNoteId() ?: return true
			val note = runBlocking { Notes.getNoteWithContent(id)!! }
			val content = note.content() ?: return true
			val sendIntent: Intent = Intent().apply {
				action = Intent.ACTION_SEND
				if (note.type == "text" || note.type == "code") {
					if (note.mime == "text/html") {
						val html = String(content)
						putExtra(Intent.EXTRA_HTML_TEXT, html)
						putExtra(Intent.EXTRA_TEXT, html.parseAsHtml())
					} else {
						putExtra(Intent.EXTRA_TEXT, String(content))
					}
				} else if (note.type == "image") {
					var f = Path(
						activity.cacheDir.absolutePath,
						"images"
					)
					f = f.createDirectories().resolve("${note.id}.${note.mime.split('/')[1]}")
					f.writeBytes(content)
					val contentUri = FileProvider.getUriForFile(
						activity,
						activity.applicationContext.packageName + ".provider",
						f.toFile()
					)
					clipData = ClipData.newRawUri("", contentUri)
					putExtra(
						Intent.EXTRA_STREAM,
						contentUri
					)
					addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				} else {
					putExtra(Intent.EXTRA_TEXT, "(cannot share this note type)")
				}
				type = note.mime
			}

			val shareIntent = Intent.createChooser(sendIntent, note.title())
			BiometricAuthManager.allowInternalNavigation()
			activity.startActivity(shareIntent)
			true
		}

		R.id.action_execute -> run {
			Log.i(TAG, "executing code note")
			val noteFrag = activity.getNoteFragment()
			val noteId = noteFrag.getNoteId()
			if (noteId != null) {
				val note = runBlocking { Notes.getNoteWithContent(noteId)!! }
				val script = String(note.content() ?: return true)
				activity.runScript(noteFrag, script)
			}
			true
		}

		R.id.action_delete -> run {
			if (Preferences.readOnlyMode()) {
				return@run true
			}
			val note = activity.getNoteLoaded()
			if (note == null || note.id == Notes.ROOT) {
				Toast.makeText(
					activity, activity.getString(R.string.toast_cannot_delete_root),
					Toast.LENGTH_SHORT
				).show()
			} else {
				val isCalendarRoot = runBlocking { note.hasLabel("calendarRoot") }
				val isYearNote = runBlocking { note.hasLabel("yearNote") }
				val isMonthNote = runBlocking { note.hasLabel("monthNote") }
				val isDateNote = runBlocking { note.hasLabel("dateNote") }
				if (isCalendarRoot || isYearNote || isMonthNote || isDateNote) {
					Toast.makeText(
						activity, activity.getString(R.string.toast_cannot_delete_root),
						Toast.LENGTH_SHORT
					).show()
					return@run true
				}
				val path = noteHistory.branch()?.takeIf { it.note == note.id }
					?: runBlocking { Branches.getNotePath(note.id).firstOrNull { it.note == note.id } }
				if (path == null) {
					return@run true
				}
				AlertDialog.Builder(activity)
					.setTitle(R.string.title_dialog_delete_note)
					.setMessage(R.string.text_really_delete)
					.setPositiveButton(
						android.R.string.ok
					) { _, _ ->
						if (runBlocking { !Notes.deleteNote(path) }) {
							Toast.makeText(
								activity, activity.getString(R.string.toast_could_not_delete),
								Toast.LENGTH_SHORT
							).show()
						}
						navigateTo(activity, runBlocking { Notes.getNote(path.parentNote)!! })
						activity.refreshTree()
						triggerAutoSyncIfEnabled(activity)
					}
					.setNegativeButton(android.R.string.cancel, null).show()
			}
			true
		}

		R.id.action_sync -> {
			showSyncError = true
			startSync(activity, true)
			true
		}

		R.id.action_journal -> {
			journalSelect(activity)
			true
		}

		R.id.action_new_note -> {
			newNote(activity)
			true
		}

		R.id.action_new_note_sibling -> {
			newNoteSibling(activity)
			true
		}

		R.id.action_scroll_to_note -> {
			activity.openDrawerTree()
			scrollTreeToCurrentNote(activity)
			true
		}

		R.id.action_collapse_tree -> {
			activity.openDrawerTree()
			collapseOrExpandTree(activity)
			true
		}

		R.id.action_note_map -> {
			val frag = activity.getFragment()
			if (frag is NoteMapFragment) {
				noteHistory.goBack(activity)
			} else if (frag is NoteFragment) {
				val id = frag.getNoteId()
				if (id != null) {
					noteHistory.addAndRestore(
						NoteMapItem(runBlocking { Notes.getNote(id)!! }),
						activity
					)
				}
			}
			true
		}

		R.id.action_console -> {
			val dialog = AlertDialog.Builder(activity)
				.setTitle(R.string.action_console_log)
				.setView(R.layout.dialog_console)
				.create()
			val text = StringBuilder()
			for (entry in activity.getNoteFragment().console) {
				text.append(entry.message()).append("\n")
			}
			dialog.show()
			dialog.findViewById<TextView>(R.id.dialog_console_output)!!.text = text
			true
		}

		R.id.action_settings -> {
			BiometricAuthManager.allowInternalNavigation()
			activity.startActivity(Intent(activity, SetupActivity::class.java))
			true
		}

		R.id.action_help -> {
			activity.lifecycleScope.launch {
				val help = NoteId("_help")
				val helpNote = Notes.getNote(help) ?: return@launch
				val helpNoteBranch = Branches.getNotePath(help).firstOrNull() ?: return@launch
				noteHistory.addAndRestore(NavigationItem(helpNote, helpNoteBranch), activity)
			}
			true
		}

		R.id.action_about -> {
			BiometricAuthManager.allowInternalNavigation()
			activity.startActivity(Intent(activity, AboutActivity::class.java))
			true
		}

		else -> {
			// If we got here, the user's action was not recognized.
			// Invoke the superclass to handle it.
			false
		}
	}

	fun navigateTo(activity: MainActivity, note: Note, branch: Branch? = null) {
		noteHistory.addAndRestore(NoteItem(note, branch), activity)
	}

	fun revisionView(activity: MainActivity, revision: NoteRevision) {
		activity.lifecycleScope.launch {
			val content = revision.content()
			noteHistory.addAndRestore(BlobItem(revision.note, content), activity)
		}
	}

	fun revisionRestore(activity: MainActivity, revision: NoteRevision) {
		activity.lifecycleScope.launch {
			val contentToRestore = revision.content()
			revision.note.updateContent(contentToRestore.content, true)
			reloadNote(activity)
			triggerAutoSyncIfEnabled(activity)
		}
	}

	fun revisionDelete(activity: MainActivity, revision: NoteRevision, uiCallback: () -> Unit) {
		YesNoDialog.show(
			activity,
			R.string.title_delete_revision,
			R.string.text_delete_revision
		) {
			activity.lifecycleScope.launch {
				NoteRevisions.delete(revision)
				uiCallback.invoke()
				triggerAutoSyncIfEnabled(activity)
			}
		}
	}

	/**
	 * Widget with given ID was used to launch the app.
	 */
	private fun widgetUsed(activity: MainActivity, appWidgetId: Int) {
		val action = Preferences.widgetAction(appWidgetId)
		if (action == null) {
			// user must configure this widget before use
			ConfigureWidgetDialog.showDialog(activity, appWidgetId) {
				val intent = Intent(activity, NoteWidget::class.java)
				intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
				val ids = intArrayOf(appWidgetId)
				intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
				activity.sendBroadcast(intent)
			}
		} else {
			firstAction = action
		}
	}

	/**
	 * Quick note widget: create a child note of today's journal note (or the inbox),
	 * titled with the current time, and open it in the editor.
	 */
	private fun createQuickNote(activity: MainActivity) {
		isQuickNoteSession = true
		activity.lifecycleScope.launch {
			val parentNote = DateNotes.getTodayNote() ?: DateNotes.getInboxNote()
			val title = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
			val note = Notes.createChildNote(parentNote, title)
			quickNotePendingId = note.id
			// Put NoteItem as the back/parent entry in the navigation stack WITHOUT calling
			// restore(activity), which would trigger an asynchronous NoteFragment.load()
			// that races with and overwrites the editor below.
			// No tree work here: the editor must open instantly. The tree is loaded
			// when the session ends (see finishEditing / showAppHome).
			noteHistory.add(NoteItem(note, null))
			noteHistory.addAndRestore(NoteEditItem(note), activity)
		}
	}

	/**
	 * App received text from another app (share target).
	 */
	suspend fun textShared(activity: MainActivity, intent: Intent) {
		val text = intent.getStringExtra(Intent.EXTRA_TEXT)
			?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
			?: return
		if (DB.haveDatabase(activity)) {
			DB.initializeDatabase(activity.applicationContext)
			Tree.getTreeData("")
			val parentNote = DateNotes.getTodayNote() ?: DateNotes.getInboxNote()
			val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
			val title = subject
				?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
				?: "Shared note"
			val htmlContent = if (intent.type == "text/html") {
				intent.getStringExtra(Intent.EXTRA_HTML_TEXT) ?: text
			} else {
				text.trim().lines().joinToString("") { line ->
					val escaped = line
						.replace("&", "&amp;")
						.replace("<", "&lt;")
						.replace(">", "&gt;")
					if (escaped.startsWith("http://") || escaped.startsWith("https://")) {
						"<p><a href=\"$escaped\">$escaped</a></p>"
					} else {
						"<p>$escaped</p>"
					}
				}
			}
			val note = Notes.createChildNote(
				parentNote,
				title
			)
			Notes.setNoteContent(note.id, htmlContent)
			Tree.getTreeData("")
			activity.refreshTree()
			Toast.makeText(activity, activity.getString(R.string.toast_note_shared, title), Toast.LENGTH_SHORT).show()
			if (Preferences.autoSync() && Preferences.syncConfigured()) {
				if (active) {
					triggerAutoSyncIfEnabled(activity)
				} else {
					ConnectionUtil.setup(activity, {
						runBlocking {
							try {
								Sync.syncPush()
							} catch (e: Exception) {
								Log.w(TAG, "auto-sync push after share failed", e)
							}
						}
					}, {})
				}
			}
		}
	}

	/**
	 * User clicked on the note icon in the toolbar.
	 */
	fun titleIconClicked(activity: MainActivity) {
		if (Preferences.readOnlyMode()) {
			return
		}
		val frag = if (activity.hasFragment()) activity.getFragment() else null
		if (frag !is NoteEditFragment) {
			return
		}
		val note = activity.getNoteLoaded() ?: return
		NoteIconDialog.showDialogReturningIcon(activity) {
			activity.lifecycleScope.launch {
				val cssClass = Icon.getCssClass(it) ?: it
				Attributes.updateLabel(note, "iconClass", cssClass, false)
				frag.markExternalModified()
				activity.refreshTitle(Notes.getNote(note.id))
				activity.refreshTree()
			}
		}
	}

	/**
	 * User clicked on the note title in the action bar.
	 */
	fun titleClicked(activity: MainActivity) {
		if (Preferences.readOnlyMode()) {
			return
		}
		val frag = if (activity.hasFragment()) activity.getFragment() else null
		if (frag !is NoteEditFragment) {
			return
		}
		val note = activity.getNoteLoaded() ?: return
		if (note.isProtected && !ProtectedSession.isActive()) {
			return
		}
		AskForNameDialog.showDialog(activity, R.string.dialog_rename_note, note.title()) {
			activity.lifecycleScope.launch {
				Notes.renameNote(note, it)
				frag.markExternalModified()
				activity.refreshTree()
				activity.refreshTitle(note)
			}
		}
	}

	/**
	 * User clicked on new (child) note button.
	 */
	fun newNote(activity: MainActivity) {
		val note = activity.getNoteLoaded()
		if (note == null) {
			Toast.makeText(
				activity, activity.getString(R.string.toast_no_note),
				Toast.LENGTH_LONG
			).show()
			return
		}
		CreateNewNoteDialog.showDialog(activity, true, note)
	}

	/**
	 * User clicked on new (sibling) note button.
	 */
	fun newNoteSibling(activity: MainActivity) {
		val note = activity.getNoteLoaded()
		if (note == null) {
			Toast.makeText(activity, activity.getString(R.string.toast_no_note), Toast.LENGTH_LONG)
				.show()
			return
		}
		CreateNewNoteDialog.showDialog(activity, false, note)
	}

	/**
	 * User clicked on journal navigation button.
	 */
	fun journalSelect(activity: MainActivity) {
		activity.lifecycleScope.launch {
			if (DateNotes.getCalendarRoot() == null) {
				Toast.makeText(activity, R.string.toast_no_journal_found, Toast.LENGTH_LONG)
					.show()
				return@launch
			}
			// get all existing journal dates to highlight them
			val dates = DateNotes.getCalendarDates()
			val now = Calendar.getInstance()
			val dpd = DatePickerDialog.newInstance(
				null,
				now.get(Calendar.YEAR),  // Initial year selection
				now.get(Calendar.MONTH),  // Initial month selection
				now.get(Calendar.DAY_OF_MONTH) // Initial day selection
			)
			if (dates.isNotEmpty()) {
				dpd.highlightedDays = dates.toTypedArray()
			}
			dpd.onDateSetListener = object : DatePickerDialog.OnDateSetListener {
				override fun onDateSet(
					view: DatePickerDialog?,
					year: Int,
					month: Int,
					day: Int
				) {
					val isoDate = "$year-${(month + 1).toString().padStart(2, '0')}-${
						day.toString().padStart(2, '0')
					}"
					activity.lifecycleScope.launch {
						val dn = DateNotes.getDayNote(isoDate)
						if (dn != null) {
							Tree.getTreeData("")
							navigateTo(activity, dn)
							activity.refreshTree()
							triggerAutoSyncIfEnabled(activity)
						}
					}
				}
			}
			dpd.show(activity.supportFragmentManager, "Datepickerdialog")
		}
	}

	fun globalNoteMap(activity: MainActivity) {
		noteHistory.addAndRestore(NoteMapItem(null), activity)
	}

	fun noteTreeClicked(activity: MainActivity, branch: Branch) {
		activity.lifecycleScope.launch {
			val note = Notes.getNoteWithContent(branch.note)
			if (note == null) {
				Log.e(TAG, "clicked on branch ${branch.id} with invalid note id ${branch.note}")
				Toast.makeText(activity, R.string.toast_note_not_in_database, Toast.LENGTH_LONG)
					.show()
				return@launch
			}
			navigateTo(activity, note, branch)
		}
	}

	fun noteTreeClickedLong(activity: MainActivity, branch: Branch) {
		runBlocking {
			Branches.toggleBranch(branch)
		}
		activity.refreshTree()
	}

	fun scrollTreeToCurrentNote(activity: MainActivity) {
		val note = activity.getNoteLoaded() ?: return
		activity.lifecycleScope.launch {
			val paths = Branches.getNotePaths(note.id)
			val path = paths?.firstOrNull() ?: Branches.getNotePath(note.id)
			ensurePathIsExpanded(path)
			val items = Tree.getTreeList(Branches.NONE_ROOT, 0)
			activity.tree?.submitList(items) {
				activity.scrollTreeToNote(note.id)
			}
		}
	}

	fun collapseOrExpandTree(activity: MainActivity) {
		activity.lifecycleScope.launch {
			val currentVisible = Tree.getTreeList(Branches.NONE_ROOT, 0)
			val anyFolderExpanded = currentVisible.any { (branch, _) ->
				branch.id != Branches.NONE_ROOT &&
					branch.expanded &&
					!Notes.notes[branch.note]?.children.isNullOrEmpty()
			}
			if (anyFolderExpanded) {
				Branches.collapseAllBelowRoot()
				val items = Tree.getTreeList(Branches.NONE_ROOT, 0)
				activity.tree?.submitList(items) {
					activity.tree?.notifyDataSetChanged()
				}
			} else {
				val note = activity.getNoteLoaded()
				if (note != null && note.id != Notes.ROOT) {
					val paths = Branches.getNotePaths(note.id)
					val path = paths?.firstOrNull() ?: Branches.getNotePath(note.id)
					ensurePathIsExpanded(path)
					val items = Tree.getTreeList(Branches.NONE_ROOT, 0)
					activity.tree?.submitList(items) {
						activity.scrollTreeToNote(note.id)
					}
				} else {
					val rootNote = Notes.getNote(Notes.ROOT)
					rootNote?.children?.forEach { branch ->
						if (!branch.expanded) {
							Branches.toggleBranch(branch)
						}
					}
					val items = Tree.getTreeList(Branches.NONE_ROOT, 0)
					activity.tree?.submitList(items) {
						activity.tree?.notifyDataSetChanged()
					}
				}
			}
		}
	}

	fun triggerAutoSyncIfEnabled(activity: MainActivity) {
		if (Preferences.autoSync() && Preferences.syncConfigured()) {
			startSync(activity, false)
		}
	}

	fun addNotePath(activity: MainActivity) {
		val loaded = activity.getNoteLoaded() ?: return
		JumpToNoteDialog.showDialogReturningNote(activity, R.string.dialog_select_note) {
			runBlocking {
				Branches.cloneNote(it, loaded)
				Tree.getTreeData("")
				activity.refreshTree()
				triggerAutoSyncIfEnabled(activity)
			}
		}
	}

	/**
	 * Change the specified note path.
	 */
	fun notePathEdit(activity: MainActivity, pathBranch: Branch, note: Note) {
		JumpToNoteDialog.showDialogReturningNote(
			activity,
			R.string.dialog_select_parent_note
		) { newParent ->
			activity.lifecycleScope.launch {
				val newParentNote =
					Notes.getNote(newParent.note)
						?: return@launch
				val children = (listOf(newParent) + newParentNote.computeChildren()
					.toList()).toMutableList()
				children.remove(pathBranch)
				if (children.size == 1) {
					Branches.moveBranch(pathBranch, newParent, 0)
					val newBranch = Branches.loadBranch(newParentNote.id, note.id)
					noteHistory.setBranch(newBranch!!)
					activity.refreshTree()
					activity.refreshWidgets(note)
				} else {
					SelectNoteDialog.showDialogReturningNote(
						activity,
						children
					) { siblingNote ->
						activity.lifecycleScope.launch {
							val siblingIndex = children.indexOf(siblingNote)
							if (siblingIndex == 0) {
								// insert as first element
								Branches.moveBranch(
									pathBranch,
									newParent,
									children[1].position - 10
								)
							} else if (siblingIndex < children.size - 1) {
								val insertBefore = children[siblingIndex + 1]
								val positionDelta =
									insertBefore.position - siblingNote.position
								if (positionDelta <= 1) {
									// re-order entire list
									val ourIndex = siblingIndex + 1
									for (i in 1 until children.size) {
										if (i < ourIndex) {
											Branches.moveBranch(
												children[i],
												newParent,
												(i - 1) * 10
											)
										} else {
											Branches.moveBranch(
												children[i],
												newParent,
												i * 10
											)

										}
									}
									Branches.moveBranch(
										pathBranch,
										newParent,
										ourIndex * 10
									)
								} else {
									val ourPosition =
										siblingNote.position + positionDelta / 2
									Branches.moveBranch(
										pathBranch,
										newParent,
										ourPosition
									)
								}
							} else {
								Branches.moveBranch(
									pathBranch,
									newParent,
									siblingNote.position + 10
								)
							}
							val newBranch = Branches.loadBranch(newParentNote.id, note.id)
							noteHistory.setBranch(newBranch!!)
							note.clearAttributeCache()
							activity.refreshTree()
							activity.refreshWidgets(note)
						}
					}
				}
			}
		}
	}

	fun notePathClicked(activity: MainActivity, pathSelected: List<Branch>, noteContent: Note) {
		if (runBlocking { ensurePathIsExpanded(pathSelected) }) {
			activity.refreshTree()
		}
		if (pathSelected.first().cachedTreeIndex != null) {
			noteHistory.setBranch(pathSelected.first())
			activity.lifecycleScope.launch {
				activity.refreshWidgets(noteContent)
			}
			activity.scrollTreeToBranch(pathSelected.first())
			activity.openDrawerTree()
		}
	}

	suspend fun ensurePathIsExpanded(path: List<Branch>): Boolean {
		var expandedAny = false
		for (n in path) {
			// expanded = the children of this note are visible
			if (!n.expanded) {
				Branches.toggleBranch(n)
				expandedAny = true
			}
		}
		return expandedAny
	}

	fun relationTargetClicked(activity: MainActivity, attribute: Relation) {
		navigateTo(activity, attribute.target ?: return)
	}

	fun reloadNote(activity: MainActivity, skipEditors: Boolean = false) {
		if (!active) {
			return
		}
		if (skipEditors && noteHistory.last() == NoteEditItem::class) {
			return
		}
		noteHistory.restore(activity)
	}

	fun currentPath() = noteHistory.branch()

	private fun showInitialNote(activity: MainActivity, resetView: Boolean) =
		activity.lifecycleScope.launch {
			activity.refreshTree()
			if (resetView) {
				val lastNote = Preferences.lastNote()
				// first use: open the drawer
				if (lastNote == null && DB.lastSync != null) {
					activity.openDrawerTree()
					return@launch
				}
				var n = firstNote ?: lastNote
				if (n == null || Notes.getNote(n) == null) {
					n = Notes.ROOT // may happen in case of new database or note deleted
				}
				val note = Notes.getNote(n) ?: return@launch
				navigateTo(activity, note, note.branches.firstOrNull())
			} else {
				reloadNote(activity, true)
			}
		}

	private fun handleError(activity: MainActivity, it: Exception) {
		if (!active) {
			// too bad, I guess. At least we can log the error
			Log.e(TAG, "error received in background ", it)
			return
		}
		var toastText: String? = null
		val cause = it.cause
		val cause2 = cause?.cause
		var certException: CertPathValidatorException? = null
		if (cause is CertPathValidatorException) {
			certException = cause
		}
		if (cause2 is CertPathValidatorException) {
			certException = cause2
		}
		val cert = certException?.certPath?.certificates[0]
		if (it is SSLHandshakeException && cert != null) {
			AlertDialog.Builder(activity)
				.setTitle(R.string.title_trust_sync_server_certificate)
				.setMessage(cert.toString())
				.setPositiveButton(
					android.R.string.ok
				) { dialog, _ ->
					val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
						load(null)
					}
					ks.setEntry("syncServer", KeyStore.TrustedCertificateEntry(cert), null)
					activity.lifecycleScope.launch {
						ConnectionUtil.resetClient(activity) {
							startSync(activity)
						}
						dialog.dismiss()
					}
				}
				.setNegativeButton(android.R.string.cancel, null).show()
		}
		// this exception may occur if the user previously configured a trusted cert
		// https://github.com/FliegendeWurst/TriliumDroid/issues/118
		if (it is SSLPeerUnverifiedException) {
			// clear previous value
			val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
				load(null)
			}
			ks.deleteEntry("syncServer")
			toastText = activity.getString(R.string.toast_ssl_certificate)
		}

		if (it is SSLException && it.message == "Unable to parse TLS packet header") {
			toastText = "Invalid TLS configuration"
		}
		if (it is IllegalStateException) {
			toastText = it.message ?: "IllegalStateException"
		}
		var errno: ErrnoException? = null
		if (cause is ErrnoException) {
			errno = cause
		}
		if (cause2 is ErrnoException) {
			errno = cause2
		}
		if (errno != null) {
			when (errno.errno) {
				OsConstants.ECONNREFUSED -> {
					toastText = "Sync server refused connection"
				}

				OsConstants.EHOSTUNREACH -> {
					toastText = "Sync server unreachable"
				}
			}
		}
		if (toastText == null) {
			toastText = it.toString()
		}
		Log.e(TAG, "error ", it)
		Toast.makeText(
			activity, toastText,
			Toast.LENGTH_LONG
		).show()
		if (activity.hasFragment() && (activity.getFragment() is EmptyFragment || activity.getFragment() is SyncErrorFragment || showSyncError)) {
			val frag = SyncErrorFragment()
			frag.showError(it)
			activity.showFragment(frag, true)
		}
		showSyncError = false
	}

	private fun startSync(activity: MainActivity, resetView: Boolean = true) {
		if (!Preferences.syncConfigured()) {
			Toast.makeText(activity, R.string.toast_sync_unavailable, Toast.LENGTH_SHORT).show()
			return
		}
		activity.indicateSyncStart()
		activity.lifecycleScope.launch {
			DB.initializeDatabase(activity.applicationContext)
			ConnectionUtil.setup(activity, {
				activity.lifecycleScope.launch {
					Sync.syncStart({
						activity.handler.post {
							activity.indicateSyncProgress(it)
						}
					}, {
						activity.handler.post {
							handleError(activity, it)
						}
						// ensure the snackbar doesn't stay visible
						activity.handler.post {
							activity.indicateSyncError()
						}
					}, {
						activity.lifecycleScope.launch {
							activity.indicateSyncDone(it.first, it.second)
							handleSyncDone()
							val orphanedBranches = Branches.cleanupOrphanedBranches()
							if (orphanedBranches > 0) {
								Log.w(TAG, "cleaned up $orphanedBranches orphaned branches after sync")
							}
							// rebuild the tree only if the sync actually pulled
							// changes (or cleaned something up) -- a push-only sync
							// does not modify local data
							if (it.first > 0 || orphanedBranches > 0) {
								Tree.getTreeData("")
							}
							showInitialNote(activity, resetView)
							showSyncError = false
						}
					})
				}
			}, {
				activity.lifecycleScope.launch {
					Tree.getTreeData("")
					val showInitial = !showSyncError
					handleError(activity, it)
					if (showInitial) {
						showInitialNote(activity, true)
					}
					activity.indicateSyncError()
				}
			})
		}
	}

	private fun handleSyncDone() {
		// Branches and notes: taken care of by Tree.getTreeData, which clears the
		// note cache inside its lock when rebuilding (the former Notes.clearCache()
		// call here raced with in-flight tree rebuilds and gutted the tree).
		// When nothing was pulled, no rebuild happens and no invalidation is needed.
	}

	fun onDestroy(isChangingConfigurations: Boolean = false) {
		if (!isChangingConfigurations) {
			DB.closeDatabase()
		}
		// TODO: check if the activity is about to be re-created
		noteHistory.reset()
		firstAction = null
		firstNote = null
		showSyncError = false
		active = false
		loadedNoteId = null
	}
}

/**
 * Set up:
 * - [StrictMode] policy
 * - [CrashReport] saving
 * - notification channel
 */
private fun oneTimeSetup(context: Context) {
	StrictMode.setVmPolicy(
		StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
			.detectLeakedClosableObjects()
			.penaltyLog()
			.build()
	)

	val oldHandler = Thread.getDefaultUncaughtExceptionHandler()

	Thread.setDefaultUncaughtExceptionHandler { paramThread, paramThrowable ->
		CrashReport.saveReport(context, paramThread, paramThrowable)
		oldHandler?.uncaughtException(
			paramThread,
			paramThrowable
		)
	}

	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		// Create the NotificationChannel.
		val name = context.getString(R.string.channel_name)
		val importance = NotificationManager.IMPORTANCE_DEFAULT
		val mChannel = NotificationChannel(AlarmReceiver.CHANNEL_ID, name, importance)
		// Register the channel with the system. You can't change the importance
		// or other notification behaviors after this.
		val notificationManager =
			context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
		notificationManager.createNotificationChannel(mChannel)
	}
}
