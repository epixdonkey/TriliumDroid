package eu.fliegendewurst.triliumdroid.dialog

import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import eu.fliegendewurst.triliumdroid.R
import eu.fliegendewurst.triliumdroid.activity.SetupActivity
import eu.fliegendewurst.triliumdroid.util.ListAdapter
import eu.fliegendewurst.triliumdroid.util.Preferences

object ConfigureFabsDialog {
	const val SHOW_NOTE_TREE: String = "showNoteTree"
	const val JUMP_TO_NOTE: String = "jumpToNote"
	const val NOTE_NAVIGATION: String = "noteNavigation"
	const val EDIT_NOTE: String = "editNote"
	const val SHARE_NOTE: String = "shareNote"
	const val DELETE_NOTE: String = "deleteNote"
	const val NOTE_MAP: String = "noteMap"
	const val NOTE_METADATA: String = "noteMetadata"
	const val SYNC: String = "sync"
	const val JOURNAL: String = "journal"
	const val NEW_NOTE: String = "newNote"
	const val NEW_NOTE_SIBLING: String = "newNoteSibling"
	const val SCROLL_TO_NOTE: String = "scrollToNote"
	const val COLLAPSE_TREE: String = "collapseTree"

	/**
	 * See [eu.fliegendewurst.triliumdroid.activity.main.MainActivity.performAction],
	 * and string array "fabs"
	 */
	val actions = mapOf(
		Pair(SHOW_NOTE_TREE, R.id.action_show_note_tree),
		Pair(JUMP_TO_NOTE, R.id.action_jump_to_note),
		Pair(NOTE_NAVIGATION, R.id.action_note_navigation),
		Pair(EDIT_NOTE, R.id.action_edit),
		Pair(SHARE_NOTE, R.id.action_share),
		Pair(DELETE_NOTE, R.id.action_delete),
		Pair(NOTE_MAP, R.id.action_note_map),
		Pair(NOTE_METADATA, R.id.action_note_metadata),
		Pair(SYNC, R.id.action_sync),
		Pair(JOURNAL, R.id.action_journal),
		Pair(NEW_NOTE, R.id.action_new_note),
		Pair(NEW_NOTE_SIBLING, R.id.action_new_note_sibling),
		Pair(SCROLL_TO_NOTE, R.id.action_scroll_to_note),
		Pair(COLLAPSE_TREE, R.id.action_collapse_tree)
	)

	fun init() {
		val haveLeftAction = getLeftAction() != null
		val haveRightAction = getRightAction() != null
		for (action in actions.keys) {
			if (getPref(action) == null) {
				when (action) {
					"showNoteTree" -> {
						setPref(action, left = !haveLeftAction, right = false, top = false, tree = false, show = false)
					}

					"jumpToNote" -> {
						setPref(action, left = false, right = false, top = false, tree = false, show = true)
					}

					NOTE_NAVIGATION -> {
						setPref(action, left = false, right = !haveRightAction, top = false, tree = false, show = false)
					}

					EDIT_NOTE, SHARE_NOTE -> {
						setPref(action, left = false, right = false, top = true, tree = false, show = true)
					}

					NEW_NOTE, NEW_NOTE_SIBLING, SCROLL_TO_NOTE, COLLAPSE_TREE -> {
						setPref(action, left = false, right = false, top = false, tree = true, show = false)
					}

					else -> {
						setPref(action, left = false, right = false, top = false, tree = false, show = true)
					}
				}
			}
		}
	}

	fun showDialog(
		activity: SetupActivity,
		callback: () -> Unit,
	) {
		val dialog = AlertDialog.Builder(activity)
			.setTitle(R.string.dialog_configure_fabs)
			.setView(R.layout.dialog_configure_fabs)
			.create()
		dialog.show()

		dialog.findViewById<Button>(R.id.button_setup_fabs)!!.setOnClickListener {
			dialog.dismiss()
		}
		dialog.setOnDismissListener {
			callback.invoke()
		}

		val list = dialog.findViewById<ListView>(R.id.list_fab_actions)!!
		val labels = activity.resources.getStringArray(R.array.fabs)
		list.adapter = ListAdapter(actions.keys.toList()) { action, convertView: View? ->
			var vi = convertView
			if (vi == null) {
				vi = dialog.layoutInflater.inflate(
					R.layout.item_fab_action,
					list,
					false
				)
			}
			vi!!.findViewById<TextView>(R.id.label_fab_action).text =
				labels[actions.keys.indexOf(action)]
			val settingsInitial = getPref(action)!!
			val left = vi.findViewById<CheckBox>(R.id.button_fab_left)
			val right = vi.findViewById<CheckBox>(R.id.button_fab_right)
			val top = vi.findViewById<CheckBox>(R.id.checkbox_fab_top)
			val tree = vi.findViewById<CheckBox>(R.id.checkbox_fab_tree)
			val show = vi.findViewById<CheckBox>(R.id.checkbox_fab_show)
			left.setOnCheckedChangeListener(null)
			right.setOnCheckedChangeListener(null)
			top.setOnCheckedChangeListener(null)
			tree.setOnCheckedChangeListener(null)
			show.setOnCheckedChangeListener(null)
			left.isChecked = settingsInitial.left
			right.isChecked = settingsInitial.right
			top.isChecked = settingsInitial.top
			tree.isChecked = settingsInitial.tree
			show.isChecked = settingsInitial.show
			left.setOnCheckedChangeListener { _, isChecked ->
				val settings = getPref(action)!!
				setPref(action, isChecked, settings.right, settings.top, settings.tree, settings.show)
			}
			right.setOnCheckedChangeListener { _, isChecked ->
				val settings = getPref(action)!!
				setPref(action, settings.left, isChecked, settings.top, settings.tree, settings.show)
			}
			top.setOnCheckedChangeListener { _, isChecked ->
				val settings = getPref(action)!!
				setPref(action, settings.left, settings.right, isChecked, settings.tree, settings.show)
			}
			tree.setOnCheckedChangeListener { _, isChecked ->
				val settings = getPref(action)!!
				setPref(action, settings.left, settings.right, settings.top, isChecked, settings.show)
			}
			show.setOnCheckedChangeListener { _, isChecked ->
				val settings = getPref(action)!!
				setPref(action, settings.left, settings.right, settings.top, settings.tree, isChecked)
			}
			return@ListAdapter vi
		}
	}

	fun getPref(
		action: String
	): ActionPref? {
		val prefs = Preferences.prefs
		val left = if (prefs.contains("fab_${action}_left")) {
			prefs.getBoolean("fab_${action}_left", false)
		} else {
			null
		}
		val right = if (prefs.contains("fab_${action}_right")) {
			prefs.getBoolean("fab_${action}_right", false)
		} else {
			null
		}
		val defaultTop = action == EDIT_NOTE || action == SHARE_NOTE
		val top = prefs.getBoolean("fab_${action}_top", defaultTop)
		val defaultTree = action == NEW_NOTE || action == NEW_NOTE_SIBLING || action == SCROLL_TO_NOTE || action == COLLAPSE_TREE
		val tree = prefs.getBoolean("fab_${action}_tree", defaultTree)
		val show = if (prefs.contains("fab_${action}_show")) {
			prefs.getBoolean("fab_${action}_show", false)
		} else {
			null
		}
		return if (left != null) {
			ActionPref(left, right!!, top, tree, show!!, actions[action]!!)
		} else {
			null
		}
	}

	data class ActionPref(
		val left: Boolean,
		val right: Boolean,
		val top: Boolean,
		val tree: Boolean,
		val show: Boolean,
		val id: Int,
	)

	private fun setPref(
		action: String,
		left: Boolean,
		right: Boolean,
		top: Boolean,
		tree: Boolean,
		show: Boolean
	) {
		Preferences.prefs.edit()
			.putBoolean("fab_${action}_left", left)
			.putBoolean("fab_${action}_right", right)
			.putBoolean("fab_${action}_top", top)
			.putBoolean("fab_${action}_tree", tree)
			.putBoolean("fab_${action}_show", show)
			.apply()
	}

	fun getLeftActions(): List<String> {
		return actions.keys.filter { Preferences.isLeftAction(it) }
	}

	fun getRightActions(): List<String> {
		return actions.keys.filter { Preferences.isRightAction(it) }
	}

	fun getTreeActions(): List<String> {
		return actions.keys.filter { getPref(it)?.tree == true }
	}

	fun getLeftAction(): String? {
		return getLeftActions().firstOrNull()
	}

	fun getRightAction(): String? {
		return getRightActions().firstOrNull()
	}

	fun getIcon(action: String?): Int {
		if (action == null) {
			return R.drawable.bx_play
		}
		when (action) {
			"showNoteTree" -> {
				return R.drawable.bx_align_left
			}

			"jumpToNote" -> {
				return R.drawable.bx_send
			}

			NOTE_NAVIGATION -> {
				return R.drawable.bx_globe_alt
			}

			"editNote" -> {
				return R.drawable.bx_edit_alt
			}

			"shareNote" -> {
				return R.drawable.bx_share_alt
			}

			"deleteNote" -> {
				return R.drawable.bx_trash
			}

			"noteMap" -> {
				return R.drawable.bx_graphql
			}

			"noteMetadata" -> {
				return R.drawable.bx_info
			}

			"sync" -> {
				return R.drawable.bx_refresh
			}

			JOURNAL -> {
				return R.drawable.bx_calendar
			}

			NEW_NOTE -> {
				return R.drawable.bx_file_blank_child
			}

			NEW_NOTE_SIBLING -> {
				return R.drawable.bx_file_blank_next
			}

			SCROLL_TO_NOTE -> {
				return R.drawable.bx_target_lock
			}

			COLLAPSE_TREE -> {
				return R.drawable.bx_collapse_vertical
			}

			else -> {
				return R.drawable.bx_play
			}
		}
	}
}
