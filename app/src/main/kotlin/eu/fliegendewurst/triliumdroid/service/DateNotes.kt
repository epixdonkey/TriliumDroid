package eu.fliegendewurst.triliumdroid.service

import eu.fliegendewurst.triliumdroid.data.Note
import eu.fliegendewurst.triliumdroid.data.NoteId
import eu.fliegendewurst.triliumdroid.database.Attributes
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
	private val YEAR: DateTimeFormatter = DateTimeFormatterBuilder()
		.appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
		.toFormatter()
	private val YEAR_MONTH: DateTimeFormatter = DateTimeFormatterBuilder()
		.appendValue(ChronoField.YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
		.appendLiteral('-')
		.appendValue(ChronoField.MONTH_OF_YEAR, 2)
		.toFormatter()

	val MONTH: DateTimeFormatter
		get() = DateTimeFormatterBuilder()
			.appendValue(ChronoField.MONTH_OF_YEAR, 2, 2, SignStyle.NEVER)
			.appendLiteral(" - ")
			.appendPattern("MMMM")
			.toFormatter(Locale.getDefault())

	val DAY: DateTimeFormatter
		get() = DateTimeFormatterBuilder()
			.appendValue(ChronoField.DAY_OF_MONTH, 2, 2, SignStyle.NEVER)
			.appendLiteral(" - ")
			.appendPattern("EEEE")
			.toFormatter(Locale.getDefault())

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
	 * Format expected: YYYY-MM-DD
	 */
	suspend fun getDayNote(isoDate: String): Note? = withContext(Dispatchers.IO) {
		val todayNote = Cache.getNotesWithAttribute("dateNote", isoDate)
		return@withContext if (todayNote.isNotEmpty()) {
			val existing = todayNote[0]
			if (!hasTemplateRelation(existing.id)) {
				val parsedDate = dateFromIso(isoDate)
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
		val monthNotes = Cache.getNotesWithAttribute("monthNote", yearMonth)
		return@withContext if (monthNotes.isNotEmpty()) {
			val existing = monthNotes[0]
			if (!hasTemplateRelation(existing.id)) {
				val parsedDate = dateFromIso("$yearMonth-01")
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
		val yearNotes = Cache.getNotesWithAttribute("yearNote", year)
		return@withContext if (yearNotes.isNotEmpty()) {
			applyCalendarTemplate(yearNotes[0], "yearTemplate")
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
