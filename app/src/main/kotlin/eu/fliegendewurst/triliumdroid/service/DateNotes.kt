package eu.fliegendewurst.triliumdroid.service

import android.util.Log
import eu.fliegendewurst.triliumdroid.data.Note
import eu.fliegendewurst.triliumdroid.data.NoteId
import eu.fliegendewurst.triliumdroid.database.Attributes
import eu.fliegendewurst.triliumdroid.database.Branches
import eu.fliegendewurst.triliumdroid.database.Cache
import eu.fliegendewurst.triliumdroid.database.DB
import eu.fliegendewurst.triliumdroid.database.Notes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.*

object DateNotes {
	private const val TAG = "DateNotes"
	private val YEAR: DateTimeFormatter = DateTimeFormatterBuilder()
		.appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
		.toFormatter()
	private val YEAR_MONTH: DateTimeFormatter = DateTimeFormatterBuilder()
		.appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
		.appendLiteral('-')
		.appendValue(ChronoField.MONTH_OF_YEAR, 2)
		.toFormatter()

	@Volatile
	private var cachedVaultLocale: Locale? = null

	/**
	 * Locale of the vault (synced `formattingLocale`/`locale` options), NOT
	 * the phone's. Day and month titles must match what the desktop creates:
	 * an offline phone formatting with the system locale mints notes like
	 * "11 - wtorek" next to the desktop's "11 - Tuesday" -- two notes for
	 * the same day.
	 */
	private suspend fun resolveVaultLocale(): Locale {
		cachedVaultLocale?.let { return it }
		val tag = try {
			val formattingLocale =
				DB.rawQuery("SELECT value FROM options WHERE name = 'formattingLocale'", arrayOf())
					.use { if (it.moveToFirst()) it.getString(0) else null }
			val locale =
				DB.rawQuery("SELECT value FROM options WHERE name = 'locale'", arrayOf())
					.use { if (it.moveToFirst()) it.getString(0) else null }
			formattingLocale?.takeIf { it.isNotBlank() }
				?: locale?.takeIf { it.isNotBlank() && it != "none" }
		} catch (e: Exception) {
			null
		}
		val locale = if (tag == null) {
			Locale.getDefault()
		} else {
			try {
				Locale.forLanguageTag(tag)
			} catch (e: Exception) {
				Locale.getDefault()
			}
		}
		cachedVaultLocale = locale
		return locale
	}

	private fun vaultLocale(): Locale = cachedVaultLocale ?: Locale.getDefault()

	val MONTH: DateTimeFormatter
		get() = DateTimeFormatterBuilder()
			.appendValue(ChronoField.MONTH_OF_YEAR, 2, 2, SignStyle.NEVER)
			.appendLiteral(" - ")
			.appendPattern("MMMM")
			.toFormatter(vaultLocale())

	val DAY: DateTimeFormatter
		get() = DateTimeFormatterBuilder()
			.appendValue(ChronoField.DAY_OF_MONTH, 2, 2, SignStyle.NEVER)
			.appendLiteral(" - ")
			.appendPattern("EEEE")
			.toFormatter(vaultLocale())

	private fun dateFromIso(isoDate: String): LocalDate {
		val parts = isoDate.split("-")
		return LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
	}

	private var calendarRoot: Note? = null

	suspend fun getCalendarRoot(): Note? {
		if (calendarRoot != null && !calendarRoot!!.invalid()) {
			return calendarRoot
		}
		val calendarRoots = Cache.getNotesWithAttribute("calendarRoot", null)
		return if (calendarRoots.isNotEmpty()) {
			calendarRoot = Notes.getNoteWithContent(calendarRoots[0].id) ?: calendarRoots[0]
			calendarRoot
		} else {
			null
		}
	}

	private suspend fun hasTemplateRelation(noteId: NoteId): Boolean = withContext(Dispatchers.IO) {
		var found = false
		DB.rawQuery(
			"SELECT 1 FROM attributes WHERE noteId = ? AND type = 'relation' AND name = 'template' AND isDeleted = 0 LIMIT 1",
			arrayOf(noteId.rawId())
		).use {
			if (it.moveToNext()) {
				found = true
			}
		}
		return@withContext found
	}

	private suspend fun applyCalendarTemplate(note: Note, vararg templateRelationNames: String): Note =
		withContext(Dispatchers.IO) {
			if (hasTemplateRelation(note.id)) {
				val loaded = Notes.getNoteWithContent(note.id) ?: note
				loaded.getLabels()
				return@withContext loaded
			}
			val root = getCalendarRoot() ?: return@withContext note
			var templateNoteId: String? = null
			for (relationName in templateRelationNames) {
				DB.rawQuery(
					"SELECT value FROM attributes WHERE noteId = ? AND type = 'relation' AND name = ? AND isDeleted = 0 LIMIT 1",
					arrayOf(root.id.rawId(), relationName)
				).use {
					if (it.moveToNext()) {
						templateNoteId = it.getString(0)?.takeIf { s -> s.isNotBlank() }
					}
				}
				if (templateNoteId != null) break
			}
			if (templateNoteId == null) {
				for (relationName in templateRelationNames) {
					DB.rawQuery(
						"SELECT attributes.value FROM attributes INNER JOIN notes USING (noteId) WHERE notes.isDeleted = 0 AND attributes.isDeleted = 0 AND attributes.type = 'relation' AND attributes.name = ? LIMIT 1",
						arrayOf(relationName)
					).use {
						if (it.moveToNext()) {
							templateNoteId = it.getString(0)?.takeIf { s -> s.isNotBlank() }
						}
					}
					if (templateNoteId != null) break
				}
			}
			if (templateNoteId == null && templateRelationNames.any { it == "dateTemplate" || it == "dayTemplate" }) {
				DB.rawQuery(
					"SELECT noteId FROM notes WHERE isDeleted = 0 AND (title = 'Day Note Template' OR title = 'Day template') LIMIT 1",
					arrayOf()
				).use {
					if (it.moveToNext()) {
						templateNoteId = it.getString(0)?.takeIf { s -> s.isNotBlank() }
					}
				}
			}
			val targetId = templateNoteId?.let { NoteId(it) }
				?: templateRelationNames.firstNotNullOfOrNull { root.getRelation(it)?.id }
				?: return@withContext note
			val templateNote = Notes.getNoteWithContent(targetId)
				?: Notes.getNote(targetId)
				?: Notes.invalidNote(targetId)
			val updated = Attributes.updateRelation(note, null, "template", templateNote, false) ?: note
			updated.getLabels()
			return@withContext updated
		}

	suspend fun ensureCalendarTemplateIfNeeded(note: Note): Note {
		val loaded = Notes.getNoteWithContent(note.id) ?: note
		val labels = loaded.getLabels()
		return when {
			labels.any { !it.inherited && !it.templated && it.name == "dateNote" } ->
				applyCalendarTemplate(loaded, "dateTemplate", "dayTemplate")
			labels.any { !it.inherited && !it.templated && it.name == "monthNote" } ->
				applyCalendarTemplate(loaded, "monthTemplate")
			labels.any { !it.inherited && !it.templated && it.name == "yearNote" } ->
				applyCalendarTemplate(loaded, "yearTemplate")
			else -> loaded
		}
	}

	suspend fun getCalendarDates(): List<Calendar> = withContext(Dispatchers.IO) {
		val dates = Cache.getDateNotes()
		return@withContext dates
	}

	suspend fun getInboxNote(): Note = withContext(Dispatchers.IO) {
		val inboxNotes = Cache.getNotesWithAttribute("inbox", null)
		return@withContext if (inboxNotes.isNotEmpty()) {
			inboxNotes[0]
		} else {
			getTodayNote() ?: Notes.getNote(Notes.ROOT)!!
		}
	}

	suspend fun getTodayNote(): Note? {
		val date = DateTimeFormatter.ISO_LOCAL_DATE.format(OffsetDateTime.now())
		return getDayNote(date)
	}

	/**
	 * Two notes with the same #dateNote/#monthNote/#yearNote label can exist
	 * after an offline phone and the desktop both created the note for the
	 * same date ("11 - Tuesday" vs "11 - wtorek"). Fold the duplicates into
	 * one note: children are moved to the survivor, then the empty duplicate
	 * is deleted (and the deletion synced). The survivor is the note whose
	 * title matches the vault locale, so the desktop's naming wins.
	 */
	private suspend fun mergeDateNoteDuplicates(duplicates: List<Note>, expectedTitle: String?): Note =
		withContext(Dispatchers.IO) {
			if (duplicates.size < 2) {
				return@withContext duplicates[0]
			}
			// deterministic on every device; prefer the correctly-titled note
			val sorted = duplicates.sortedBy { it.id.rawId() }
			val primary = expectedTitle?.let { title -> sorted.firstOrNull { it.title() == title } }
				?: sorted.first()
			Log.w(TAG, "${duplicates.size} duplicate date notes, merging into ${primary.id.rawId()}")
			for (duplicate in sorted) {
				if (duplicate.id == primary.id) {
					continue
				}
				try {
					// load branches from the database -- freshly loaded notes
					// carry no cached branches
					val primaryBranch = Branches.getNotePath(primary.id).firstOrNull()
					if (primaryBranch == null) {
						Log.e(TAG, "primary ${primary.id.rawId()} has no branch, aborting merge")
						return@withContext primary
					}
					// move the duplicate's CHILD notes to the primary
					val dup = Notes.getNoteWithContent(duplicate.id) ?: continue
					for (child in dup.children.orEmpty().toList()) {
						Branches.moveBranch(child, primaryBranch, Int.MAX_VALUE / 2)
					}
					// reload: only delete the duplicate when its children are gone
					val fresh = Notes.getNoteWithContent(duplicate.id) ?: continue
					if (!fresh.children.isNullOrEmpty()) {
						Log.e(TAG, "duplicate ${duplicate.id.rawId()} still has children, keeping it")
						continue
					}
					val branch = Branches.getNotePath(duplicate.id).firstOrNull()
					if (branch != null) {
						if (Notes.deleteNote(branch)) {
							Log.i(TAG, "merged duplicate ${duplicate.id.rawId()} into ${primary.id.rawId()}")
						}
					} else {
						// no live branch left but the note row survived
						Notes.markNoteDeleted(duplicate.id)
						Log.i(TAG, "merged branchless duplicate ${duplicate.id.rawId()} into ${primary.id.rawId()}")
					}
				} catch (e: Exception) {
					Log.e(TAG, "failed to merge duplicate ${duplicate.id.rawId()}, keeping it", e)
				}
			}
			return@withContext primary
		}

	/**
	 * Post-sync tidy-up of the calendar: merges ALL duplicate date/month/year
	 * notes and renames machine-generated titles to the vault locale, so the
	 * calendar converges to exactly one deterministic form -- the one the
	 * desktop produces. Returns how many labels were touched.
	 */
	suspend fun tidyUpDateNotes(): Int = withContext(Dispatchers.IO) {
		resolveVaultLocale()
		var changed = 0
		for (kind in listOf("dateNote", "monthNote", "yearNote")) {
			val duplicateLabels = mutableListOf<String>()
			DB.rawQuery(
				"SELECT attributes.value FROM attributes INNER JOIN notes USING (noteId)" +
					" WHERE attributes.name = ? AND attributes.isDeleted = 0 AND notes.isDeleted = 0" +
					" GROUP BY attributes.value HAVING COUNT(*) > 1",
				arrayOf(kind)
			).use {
				while (it.moveToNext()) {
					duplicateLabels.add(it.getString(0))
				}
			}
			for (label in duplicateLabels) {
				val group = Cache.getNotesWithAttribute(kind, label)
				if (group.size > 1) {
					mergeDateNoteDuplicates(group, expectedTitleFor(kind, label))
					changed++
				}
			}
		}
		changed += normalizeDateNoteTitles()
		return@withContext changed
	}

	private fun expectedTitleFor(kind: String, label: String): String? = try {
		when (kind) {
			"dateNote" -> DAY.format(dateFromIso(label))
			"monthNote" -> MONTH.format(dateFromIso("$label-01"))
			else -> label
		}
	} catch (e: Exception) {
		null
	}

	/**
	 * Rename day/month notes whose title is a bare localized date
	 * ("17 - wtorek", "11 - November", "17 - WTOREK"...) to the vault-locale
	 * form. Titles carrying any extra content are left untouched.
	 */
	private suspend fun normalizeDateNoteTitles(): Int = withContext(Dispatchers.IO) {
		var renamed = 0
		val bareTitle = Regex("^\\d{1,2} - \\p{L}+$")
		for (kind in listOf("dateNote", "monthNote")) {
			val rows = mutableListOf<Triple<NoteId, String, String>>()
			DB.rawQuery(
				"SELECT notes.noteId, notes.title, attributes.value FROM notes" +
					" INNER JOIN attributes USING (noteId)" +
					" WHERE attributes.name = ? AND attributes.isDeleted = 0 AND notes.isDeleted = 0",
				arrayOf(kind)
			).use {
				while (it.moveToNext()) {
					rows.add(Triple(NoteId(it.getString(0)), it.getString(1), it.getString(2)))
				}
			}
			for ((id, title, value) in rows) {
				val expected = expectedTitleFor(kind, value) ?: continue
				if (title == expected || !bareTitle.matches(title)) {
					continue
				}
				val note = Notes.getNote(id) ?: continue
				Notes.renameNote(note, expected)
				renamed++
			}
		}
		if (renamed > 0) {
			Log.i(TAG, "normalized $renamed date note titles to the vault locale")
		}
		return@withContext renamed
	}

	/**
	 * Format expected: YYYY-MM-DD
	 */
	suspend fun getDayNote(isoDate: String): Note? = withContext(Dispatchers.IO) {
		resolveVaultLocale()
		val todayNote = Cache.getNotesWithAttribute("dateNote", isoDate)
		return@withContext if (todayNote.isNotEmpty()) {
			val parsedDate = dateFromIso(isoDate)
			val existing = mergeDateNoteDuplicates(todayNote, DAY.format(parsedDate))
			if (!hasTemplateRelation(existing.id)) {
				getMonthNote(YEAR_MONTH.format(parsedDate))
			}
			applyCalendarTemplate(existing, "dateTemplate", "dayTemplate")
		} else {
			// create the new date note
			val parsedDate = dateFromIso(isoDate)
			val month = YEAR_MONTH.format(parsedDate)
			val monthNote = getMonthNote(month) ?: return@withContext null
			val dayLabel = DAY.format(parsedDate)

			var dayNote = Notes.createChildNote(monthNote, dayLabel)
			dayNote = Attributes.updateLabel(dayNote, "dateNote", isoDate, false) ?: dayNote
			dayNote = applyCalendarTemplate(dayNote, "dateTemplate", "dayTemplate")
			return@withContext dayNote
		}
	}

	/**
	 * Format expected: YYYY-MM-DD
	 */
	fun getWeekNote(isoDate: String): Note? {
		TODO("xxx")
	}

	/**
	 * Format expected: YYYY-MM
	 */
	suspend fun getMonthNote(yearMonth: String): Note? = withContext(Dispatchers.IO) {
		resolveVaultLocale()
		val monthNotes = Cache.getNotesWithAttribute("monthNote", yearMonth)
		return@withContext if (monthNotes.isNotEmpty()) {
			val parsedDate = dateFromIso("$yearMonth-01")
			val existing = mergeDateNoteDuplicates(monthNotes, MONTH.format(parsedDate))
			if (!hasTemplateRelation(existing.id)) {
				getYearNote(YEAR.format(parsedDate))
			}
			applyCalendarTemplate(existing, "monthTemplate")
		} else {
			// create the new month note
			val parsedDate = dateFromIso("$yearMonth-01")
			val year = YEAR.format(parsedDate)
			val yearNote = getYearNote(year) ?: return@withContext null
			val monthLabel = MONTH.format(parsedDate)

			var monthNote = Notes.createChildNote(yearNote, monthLabel)
			monthNote = Attributes.updateLabel(monthNote, "monthNote", yearMonth, false) ?: monthNote
			monthNote = applyCalendarTemplate(monthNote, "monthTemplate")
			return@withContext monthNote
		}
	}

	suspend fun getYearNote(year: String): Note? = withContext(Dispatchers.IO) {
		resolveVaultLocale()
		val yearNotes = Cache.getNotesWithAttribute("yearNote", year)
		return@withContext if (yearNotes.isNotEmpty()) {
			val existing = mergeDateNoteDuplicates(yearNotes, year)
			applyCalendarTemplate(existing, "yearTemplate")
		} else {
			// create the new year note
			val root = getCalendarRoot() ?: return@withContext null

			var yearNote = Notes.createChildNote(root, year)
			yearNote = Attributes.updateLabel(yearNote, "yearNote", year, false) ?: yearNote
			yearNote = applyCalendarTemplate(yearNote, "yearTemplate")
			return@withContext yearNote
		}
	}

	// createSqlConsole
	// createSearchNote
}
