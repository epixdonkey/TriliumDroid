package eu.fliegendewurst.triliumdroid.dialog

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import eu.fliegendewurst.triliumdroid.R
import eu.fliegendewurst.triliumdroid.database.Notes
import eu.fliegendewurst.triliumdroid.util.Preferences
import kotlinx.coroutines.launch

object ConfigureWidgetDialog {
	private const val TAG = "ConfigureWidgetDialog"

	/**
	 * Index of the quick note entry in R.array.widget_actions_array.
	 */
	private const val ACTION_INDEX_QUICK_NOTE = 2
	const val ACTION_QUICK_NOTE = "quickNote"

	@SuppressLint("SetTextI18n")
	fun showDialog(activity: AppCompatActivity, appWidgetId: Int, callback: () -> Unit) {
		Log.d(TAG, "configuring for appWidgetId = $appWidgetId")
		var actionSpinner: Spinner? = null
		var noteSelected: TextView? = null
		val dialog = AlertDialog.Builder(activity)
			.setTitle(R.string.title_configure_widget)
			.setView(R.layout.dialog_configure_widget)
			.setPositiveButton(android.R.string.ok) { dialog, _ ->
				done(
					appWidgetId,
					actionSpinner!!.selectedItemPosition,
					noteSelected!!.text.toString()
				)
				dialog.dismiss()
				callback.invoke()
			}
			.create()
		dialog.show()

		actionSpinner = dialog.findViewById(R.id.settings_action_spinner)!!
		ArrayAdapter.createFromResource(
			activity.applicationContext,
			R.array.widget_actions_array,
			android.R.layout.simple_spinner_item
		).also { adapter ->
			adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
			actionSpinner.adapter = adapter
		}

		noteSelected = dialog.findViewById(R.id.settings_action_note_selected)!!
		val buttonSelectNote = dialog.findViewById<Button>(R.id.button_select_note)!!
		buttonSelectNote.setOnClickListener {
			JumpToNoteDialog.showDialogReturningNote(activity, R.string.dialog_select_note) {
				activity.lifecycleScope.launch {
					val note = Notes.getNote(it.note)!!
					noteSelected.text = "${note.id.rawId()}: ${note.title()}"
				}
			}
		}

		// the quick note action always targets today's journal note, no note selection needed
		actionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
			override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
				val needsNote = position != ACTION_INDEX_QUICK_NOTE
				buttonSelectNote.isEnabled = needsNote
				noteSelected.isEnabled = needsNote
			}

			override fun onNothingSelected(parent: AdapterView<*>?) {}
		}
	}

	private fun done(
		appWidgetId: Int,
		actionIndex: Int,
		note: String
	) {
		if (actionIndex == ACTION_INDEX_QUICK_NOTE) {
			Preferences.setWidgetAction(appWidgetId, ACTION_QUICK_NOTE)
			return
		}
		val noteId = if (note.contains(':')) {
			note.split(':')[0]
		} else {
			"root"
		}
		val action = when (actionIndex) {
			0 -> {
				"edit"
			}

			1 -> {
				"open"
			}

			else -> "open"
		}
		Preferences.setWidgetAction(appWidgetId, "${action};$noteId")
	}
}
