package eu.fliegendewurst.triliumdroid.dialog

import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.fliegendewurst.triliumdroid.R
import eu.fliegendewurst.triliumdroid.databinding.ItemNoteIconBinding
import eu.fliegendewurst.triliumdroid.service.Icon
import eu.fliegendewurst.triliumdroid.util.ListRecyclerAdapter
import kotlin.math.nextDown


object NoteIconDialog {
	private const val TAG = "NoteIconDialog"

	fun showDialogReturningIcon(
		activity: AppCompatActivity,
		callback: (String) -> Unit
	) {
		Log.d(TAG, "showing note icon dialog")
		val dialog = AlertDialog.Builder(activity)
			.setTitle(R.string.title_select_note_icon)
			.setView(R.layout.dialog_note_icon)
			.create()
		dialog.show()

		val displayMetrics: DisplayMetrics = activity.resources.displayMetrics
		val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
		val cols = ((screenWidthDp * .8F) / 48F).nextDown().toInt()

		val list = dialog.findViewById<RecyclerView>(R.id.list_all_icons)!!
		val adapter = ListRecyclerAdapter({ view, x: String ->
			view.icon.typeface = Icon.getBoxiconsTypeface(view.root.context)
			view.icon.text = x
			view.icon.setOnClickListener {
				val css = Icon.getCssClass(x) ?: "bx bx-file-blank"
				dialog.dismiss()
				callback.invoke(css)
			}
		}) { parent ->
			return@ListRecyclerAdapter ItemNoteIconBinding.inflate(
				LayoutInflater.from(
					parent.context
				), parent, false
			)
		}
		adapter.submitList(Icon.getAllUnicodeCharacters())
		list.setLayoutManager(GridLayoutManager(activity, cols))
		list.adapter = adapter

		val list2 = dialog.findViewById<RecyclerView>(R.id.list_frequent_icons)!!
		val adapter2 = ListRecyclerAdapter({ view, iconClass: String ->
			val isMat = Icon.isMaterialIcon(iconClass)
			view.icon.typeface = if (isMat) Icon.getMaterialTypeface(view.root.context) else Icon.getBoxiconsTypeface(view.root.context)
			view.icon.text = Icon.getUnicodeCharacter(iconClass, view.root.context) ?: ""
			view.icon.setOnClickListener {
				val css = Icon.getCssClass(iconClass) ?: iconClass
				dialog.dismiss()
				callback.invoke(css)
			}
		}) { parent ->
			return@ListRecyclerAdapter ItemNoteIconBinding.inflate(
				LayoutInflater.from(
					parent.context
				), parent, false
			)
		}
		adapter2.submitList(Icon.iconStatistics.orEmpty().mapNotNull { it.first })
		list2.setLayoutManager(GridLayoutManager(activity, cols))
		list2.adapter = adapter2
	}
}
