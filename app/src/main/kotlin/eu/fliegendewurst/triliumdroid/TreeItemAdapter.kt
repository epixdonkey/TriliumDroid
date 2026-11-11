package eu.fliegendewurst.triliumdroid

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import eu.fliegendewurst.triliumdroid.activity.main.MainActivity
import eu.fliegendewurst.triliumdroid.data.Branch
import eu.fliegendewurst.triliumdroid.data.NoteId
import eu.fliegendewurst.triliumdroid.database.Notes
import eu.fliegendewurst.triliumdroid.databinding.ItemTreeNoteBinding
import eu.fliegendewurst.triliumdroid.service.Icon
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap


/**
 * Adapter for the note tree view.
 */
class TreeItemAdapter(
	private val onClick: (Branch) -> Unit,
	private val onLongClick: (Branch) -> Unit,
	private val showExpandToggle: Boolean = false
) :
	ListAdapter<Pair<Branch, Int>, TreeItemAdapter.TreeItemViewHolder>(TreeItemDiffCallback) {
	companion object {
		private const val TAG = "TreeItemAdapter"
	}

	private var selectedNote: NoteId? = null

	private val branchPosition: MutableMap<NoteId, MutableSet<Int>> = ConcurrentHashMap()

	private var scrollToNote: NoteId? = null
	private var scrollToBranch: Branch? = null
	private var scrollCallback: ((Int) -> Unit)? = null

	fun getBranchPosition(noteId: NoteId): Set<Int>? = branchPosition[noteId]

	override fun submitList(list: List<Pair<Branch, Int>>?) {
		submitList(list, null)
	}

	override fun submitList(list: List<Pair<Branch, Int>>?, commitCallback: Runnable?) {
		branchPosition.clear()
		for ((i, pair) in list.orEmpty().withIndex()) {
			val it = branchPosition[pair.first.note]
			if (it != null) {
				it.add(i)
			} else {
				branchPosition[pair.first.note] = mutableSetOf<Int>(i)
			}
			pair.first.cachedTreeIndex = i
		}
		super.submitList(list) {
			commitCallback?.run()
			if (scrollToNote != null) {
				val pos = getBranchPosition(scrollToNote!!)?.firstOrNull()
				scrollToNote = null
				Log.d(TAG, "(delayed) scrolling to $pos")
				if (pos != null) {
					scrollCallback?.invoke(pos)
				}
				scrollCallback = null
			} else if (scrollToBranch != null) {
				val pos = scrollToBranch!!.cachedTreeIndex
				scrollToBranch = null
				Log.d(TAG, "(delayed) scrolling to $pos")
				if (pos != null) {
					scrollCallback?.invoke(pos)
				}
				scrollCallback = null
			}
		}
	}

	fun select(noteId: NoteId, scroll: Boolean = false, callbackPosition: ((Int) -> Unit)? = null) {
		val prev = selectedNote
		selectedNote = noteId
		if (prev != null) {
			val idx = branchPosition[prev]
			idx?.forEach { notifyItemChanged(it) }
		}
		branchPosition[noteId]?.forEach { notifyItemChanged(it) }

		if (scroll) {
			val pos = getBranchPosition(noteId)?.firstOrNull()
			Log.d(TAG, "scrolling to $pos")
			if (pos == null) {
				Log.e(TAG, "trying to scroll to note with null cachedTreeIndex: $noteId")
				scrollToNote = noteId
				scrollToBranch = null
				scrollCallback = callbackPosition
				return
			}
			callbackPosition?.invoke(pos)
		}
	}

	fun scrollTo(branch: Branch, callbackPosition: (Int) -> Unit) {
		val pos = branch.cachedTreeIndex
		Log.d(TAG, "scrolling to $pos")
		if (pos == null) {
			Log.e(TAG, "trying to scroll to branch with null cachedTreeIndex: ${branch.id}")
			scrollToNote = null
			scrollToBranch = branch
			scrollCallback = callbackPosition
			return
		}
		callbackPosition.invoke(pos)
	}

	class TreeItemViewHolder(
		private val binding: ItemTreeNoteBinding,
		itemView: View,
		val onClick: (Branch) -> Unit,
		val onLongClick: (Branch) -> Unit,
		val showExpandToggle: Boolean
	) :
		RecyclerView.ViewHolder(itemView) {
		fun bind(item: Pair<Branch, Int>) {
			val note = runBlocking { Notes.getNote(item.first.note) }
			val hasChildren = showExpandToggle && !note?.children.isNullOrEmpty()
			val noteIcon = note?.icon()
			var setNoteIcon = false
			if (noteIcon != null && noteIcon != "bx bx-file-blank") {
				val isMat = Icon.isMaterialIcon(noteIcon)
				binding.noteIcon.typeface = if (isMat) Icon.getMaterialTypeface(itemView.context) else Icon.getBoxiconsTypeface(itemView.context)
				val unicode = Icon.getUnicodeCharacter(noteIcon, itemView.context)
				if (unicode != null) {
					binding.noteIcon.text = unicode
					setNoteIcon = true
				} else {
					binding.noteIcon.text = ""
				}
			} else {
				binding.noteIcon.text = ""
			}
			binding.label.text = note?.title() ?: item.first.note.rawId()
			val params = binding.label.layoutParams
			val params2 = binding.noteIcon.layoutParams
			val paramsToggle = binding.treeExpandToggle.layoutParams
			val density = itemView.resources.displayMetrics.density
			val extraToggleOffset = if (showExpandToggle) (32 * density).toInt() else 0
			if (hasChildren) {
				binding.treeExpandToggle.visibility = View.VISIBLE
				binding.treeExpandToggle.text = if (item.first.expanded) "▾" else "▸"
				binding.treeExpandToggle.setOnClickListener {
					onLongClick(item.first)
					binding.treeExpandToggle.text = if (item.first.expanded) "▾" else "▸"
				}
			} else {
				binding.treeExpandToggle.visibility = View.GONE
				binding.treeExpandToggle.setOnClickListener(null)
			}
			if (params is ViewGroup.MarginLayoutParams && params2 is ViewGroup.MarginLayoutParams && paramsToggle is ViewGroup.MarginLayoutParams) {
				val indent = (item.second * 12 * density).toInt()
				paramsToggle.leftMargin = indent
				binding.treeExpandToggle.layoutParams = paramsToggle
				params.leftMargin = indent + extraToggleOffset
				binding.label.layoutParams = params
				val padLeft = if (setNoteIcon) {
					itemView.resources.getDimensionPixelOffset(R.dimen.section_margin)
				} else {
					itemView.resources.getDimensionPixelOffset(R.dimen.normal_margin)
				}
				binding.label.setPadding(padLeft, 0, (10 * density).toInt(), 0)
				params2.leftMargin = indent + extraToggleOffset
				binding.noteIcon.layoutParams = params2
			}
			binding.label.isLongClickable = true
			binding.label.setOnClickListener { onClick(item.first) }
			binding.label.setOnLongClickListener {
				onLongClick(item.first)
				return@setOnLongClickListener true
			}
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TreeItemViewHolder {
		val binding =
			ItemTreeNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return TreeItemViewHolder(binding, binding.root, onClick, onLongClick, showExpandToggle)
	}

	override fun onBindViewHolder(holder: TreeItemViewHolder, position: Int) {
		val item = getItem(position)
		val button = holder.itemView.findViewById<Button>(R.id.label)
		val icon = holder.itemView.findViewById<TextView>(R.id.note_icon)
		val toggle = holder.itemView.findViewById<TextView>(R.id.tree_expand_toggle)
		val normalColor = holder.itemView.resources.getColor(R.color.tree_normal_text)
		toggle.setTextColor(normalColor)
		button.backgroundTintList = null
		if (item.first.note == selectedNote) {
			button.background = eu.fliegendewurst.triliumdroid.util.ThemeManager.createTreeItemDrawable(
				holder.itemView.context,
				true
			)
			val c = eu.fliegendewurst.triliumdroid.util.ThemeManager.onAccentColor()
			button.setTextColor(c)
			icon.setTextColor(c)
		} else {
			button.background = eu.fliegendewurst.triliumdroid.util.ThemeManager.createTreeItemDrawable(
				holder.itemView.context,
				false
			)
			button.setTextColor(normalColor)
			icon.setTextColor(normalColor)
		}
		holder.bind(item)
	}

	object TreeItemDiffCallback : DiffUtil.ItemCallback<Pair<Branch, Int>>() {
		override fun areItemsTheSame(
			oldItem: Pair<Branch, Int>,
			newItem: Pair<Branch, Int>
		): Boolean {
			return oldItem.first.id == newItem.first.id && oldItem.second == newItem.second
		}

		override fun areContentsTheSame(
			oldItem: Pair<Branch, Int>,
			newItem: Pair<Branch, Int>
		): Boolean {
			return false
		}
	}
}
