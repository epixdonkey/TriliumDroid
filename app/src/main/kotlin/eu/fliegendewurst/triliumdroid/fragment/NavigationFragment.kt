package eu.fliegendewurst.triliumdroid.fragment

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.VERTICAL
import eu.fliegendewurst.triliumdroid.R
import eu.fliegendewurst.triliumdroid.activity.main.MainActivity
import eu.fliegendewurst.triliumdroid.data.Branch
import eu.fliegendewurst.triliumdroid.data.Note
import eu.fliegendewurst.triliumdroid.database.Branches
import eu.fliegendewurst.triliumdroid.database.Notes
import eu.fliegendewurst.triliumdroid.databinding.FragmentNavigationBinding
import eu.fliegendewurst.triliumdroid.databinding.ItemNavigationButtonBinding
import eu.fliegendewurst.triliumdroid.service.Icon
import eu.fliegendewurst.triliumdroid.util.ListRecyclerAdapter
import eu.fliegendewurst.triliumdroid.util.ThemeManager
import kotlinx.coroutines.runBlocking


/**
 * After creating the fragment, call [NavigationFragment.showFor]
 */
class NavigationFragment : Fragment(R.layout.fragment_navigation) {
	companion object {
		private const val TAG: String = "NavigationFragment"

		private const val FLAG_LAST: Int = 1
	}

	private lateinit var binding: FragmentNavigationBinding
	private lateinit var adapter: ListRecyclerAdapter<Entry, ItemNavigationButtonBinding>
	private lateinit var adapter2: ListRecyclerAdapter<Entry, ItemNavigationButtonBinding>

	private var note: Note? = null
	private var branch: Branch? = null

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		binding = FragmentNavigationBinding.inflate(inflater, container, false)

		val rootWidth = container!!.width - binding.root.paddingStart - binding.root.paddingEnd
		Log.d(TAG, "measured root width = $rootWidth")

		val layoutManager = LinearLayoutManager(context)
		layoutManager.stackFromEnd = true
		layoutManager.orientation = VERTICAL
		binding.navigationList.setLayoutManager(layoutManager)
		binding.navigationList.setHasFixedSize(true)
		binding.navigationList.isNestedScrollingEnabled = false
		binding.navigationList.itemAnimator = null

		val density = resources.displayMetrics.density
		val onAccent = ThemeManager.onAccentColor()
		adapter = ListRecyclerAdapter({ view, x ->
			view.clickArea.background = ThemeManager.createGradientDrawable(14f * density)
			view.navigationButtonIcon.setTextColor(onAccent)
			view.navigationButtonLabel.setTextColor(onAccent)
			val isMat = Icon.isMaterialIcon(x.icon)
			view.navigationButtonIcon.typeface = if (isMat) Icon.getMaterialTypeface(view.root.context) else Icon.getBoxiconsTypeface(view.root.context)
			view.navigationButtonIcon.text = Icon.getUnicodeCharacter(x.icon, view.root.context)
			view.navigationButtonLabel.text = x.title

			view.clickArea.setOnClickListener {
				showForInternal(x.note, x.branch)
			}
		}) { parent ->
			return@ListRecyclerAdapter ItemNavigationButtonBinding.inflate(
				LayoutInflater.from(
					parent.context
				), parent, false
			)
		}
		binding.navigationList.adapter = adapter


		val layoutManager2 = LinearLayoutManager(context)
		layoutManager2.stackFromEnd = true
		layoutManager2.orientation = RecyclerView.HORIZONTAL
		binding.navigationListBottom.setLayoutManager(layoutManager2)
		binding.navigationListBottom.setHasFixedSize(true)
		binding.navigationListBottom.isNestedScrollingEnabled = false
		binding.navigationListBottom.itemAnimator = null
		val bg = resources.getColor(R.color.background2, null)
		val fg = resources.getColor(R.color.foreground, null)
		adapter2 = ListRecyclerAdapter({ view, x ->
			view.root.layoutParams.width = LinearLayout.LayoutParams.WRAP_CONTENT

			view.navigationButtonFiller.layoutParams.width = LinearLayout.LayoutParams.WRAP_CONTENT
			if (x.note.id != Notes.ROOT) {
				view.navigationButtonFiller.text = ">"
			}
			(view.navigationButtonFiller.layoutParams as LinearLayout.LayoutParams).weight = 0f
			// current note: accent colored pill, parent notes: neutral pills
			val isLast = x.flags == FLAG_LAST
			view.clickArea.background = if (isLast) {
				ThemeManager.createGradientDrawable(10f * density)
			} else {
				GradientDrawable().apply {
					cornerRadius = 10f * density
					setColor(bg)
				}
			}
			val textColor = if (isLast) onAccent else fg
			view.navigationButtonIcon.setTextColor(textColor)
			view.navigationButtonLabel.setTextColor(textColor)

			val isMat = Icon.isMaterialIcon(x.icon)
			view.navigationButtonIcon.typeface = if (isMat) Icon.getMaterialTypeface(view.root.context) else Icon.getBoxiconsTypeface(view.root.context)
			view.navigationButtonIcon.text = Icon.getUnicodeCharacter(x.icon, view.root.context)
			view.navigationButtonLabel.text = x.title

			view.clickArea.setOnClickListener {
				if (x.flags == FLAG_LAST) {
					(this.activity as MainActivity).navigateTo(x.note, x.branch)
					return@setOnClickListener
				}
				showForInternal(x.note, x.branch)
			}
		}) { parent ->
			return@ListRecyclerAdapter ItemNavigationButtonBinding.inflate(
				LayoutInflater.from(
					parent.context
				), parent, false
			)
		}
		binding.navigationListBottom.adapter = adapter2

		if (note != null) {
			showForInternal(note!!, branch)
		}

		return binding.root
	}

	fun showFor(note: Note, branch: Branch?) {
		this.note = note
		this.branch = branch
	}

	private fun showForInternal(note: Note, branch: Branch?) {
		this.note = note
		this.branch = branch

		val main = this.activity as MainActivity
		runBlocking {
			val notes = note.computeChildren()
			if (notes.isEmpty()) {
				main.navigateTo(note, branch)
				return@runBlocking
			}

			val entries2 = Branches.getNotePath(note.id).map {
				val note2 = Notes.getNote(it.note)!!
				Entry(note2.icon(), note2.title(), note2, it, 0)
			}
			entries2.first().flags = FLAG_LAST
			adapter2.submitList(entries2.asReversed())
			binding.navigationListBottom.stopScroll()
			binding.navigationListBottom.smoothScrollBy(5000, 0)

			val entries = notes.map {
				val noteHere = Notes.getNote(it.note) ?: return@map null
				Entry(noteHere.icon(), noteHere.title(), noteHere, it, 0)
			}.filterNotNull().asReversed()
			adapter.submitList(entries)
		}
	}

	data class Entry(
		val icon: String,
		val title: String,
		val note: Note,
		val branch: Branch,
		var flags: Int
	)
}
