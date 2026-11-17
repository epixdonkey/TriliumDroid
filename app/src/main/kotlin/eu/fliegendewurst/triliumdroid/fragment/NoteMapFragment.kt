package eu.fliegendewurst.triliumdroid.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import eu.fliegendewurst.triliumdroid.R
import eu.fliegendewurst.triliumdroid.data.Note
import eu.fliegendewurst.triliumdroid.data.NoteId
import eu.fliegendewurst.triliumdroid.data.Relation
import eu.fliegendewurst.triliumdroid.database.Cache
import eu.fliegendewurst.triliumdroid.database.Notes
import eu.fliegendewurst.triliumdroid.databinding.FragmentNoteMapBinding
import eu.fliegendewurst.triliumdroid.util.Graph
import eu.fliegendewurst.triliumdroid.util.Position
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class NoteMapFragment : Fragment(R.layout.fragment_note_map), NoteRelatedFragment {
	private var noteId: NoteId? = null

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val binding = FragmentNoteMapBinding.inflate(inflater, container, false)
		if (noteId != null) {
			// building the graph walks every note and relation and can take
			// seconds on large databases -- never do that on the UI thread.
			// GraphView keeps polling for nodes until the graph appears.
			val global = noteId!!.rawId() == "GLOBAL"
			val theNoteId = noteId
			Thread {
				val g = if (global) {
					createGraphGlobal()
				} else {
					runBlocking { createGraph(Notes.getNote(theNoteId!!)!!) }
				}
				binding.viewNoteMap.post {
					binding.viewNoteMap.g = g
					binding.viewNoteMap.invalidate()
				}
			}.start()
		}
		return binding.root
	}

	fun loadLater(id: NoteId) {
		noteId = id
	}

	fun loadLaterGlobal() {
		noteId = NoteId("GLOBAL")
	}

	/**
	 * Map of the neighbourhood of [note]: the note itself, everything related
	 * to it, and (one level further out) the relations of those notes. A
	 * full-vault graph is unreadable on a phone, so the walk is bounded by
	 * both depth and a node cap.
	 */
	private suspend fun createGraph(note: Note): Graph<Note, Relation> {
		val g = Graph<Note, Relation>()

		g.addNodeAtPosition(note, Position(0F, 0F))
		val visited = mutableSetOf(note.id)

		suspend fun walk(source: Note, depth: Int) {
			if (depth == 0 || g.nodes.size >= 400) {
				return
			}
			val relations = source.getRelations()
			for (relation in relations) {
				val targetId = relation.target?.id ?: continue
				if (!visited.add(targetId)) {
					continue
				}
				if (g.nodes.size >= 400) {
					return
				}
				// re-fetch: notes cached by the tree rebuild do not carry
				// their own relations, breaking the second hop
				val target = Notes.getNoteWithRelations(targetId) ?: continue
				// place new nodes on a circle around their source
				val angle = 2F * PI * g.nodes.size / 42F
				g.addNodeAtPosition(
					target,
					Position(
						200F * depth * sin(angle).toFloat(),
						200F * depth * cos(angle).toFloat()
					)
				)
				g.addEdge(source, target, relation)
				walk(target, depth - 1)
			}
		}

		walk(note, 2)

		Log.i("map", "built graph: ${g.nodes.size} nodes, ${g.edges.size} edges")
		return g
	}

	private fun createGraphGlobal(): Graph<Note, Relation> {
		val g = Graph<Note, Relation>()

		val data = runBlocking { Cache.getAllNotesWithRelations() }

		// start at a random note and keep going!
		val processed = mutableSetOf<Note>()
		while (processed.size != data.size) {
			val startNote = data[data.indices.random()]
			val relations = startNote.getRelationsBypassCache()
			if (processed.contains(startNote)) {
				continue
			}
			if (relations.isEmpty()) {
				processed.add(startNote)
				continue
			}
			g.addNodeAtPosition(startNote, Position(0F, 0F))
			rec(g, processed, startNote)
		}

		return g
	}

	private fun rec(g: Graph<Note, Relation>, processed: MutableSet<Note>, note: Note) {
		if (processed.contains(note)) {
			return
		}
		processed.add(note)
		val relations = note.getRelationsBypassCache()
		Log.d("map", "at note ${note.id} with ${relations.size} relations")
		for ((i, relation) in relations.withIndex()) {
			Log.d("map", "at relation ${relation.name} = ${relation.target}")
			if (relation.target == null) {
				continue
			}
			if (!g.nodes.contains(relation.target)) {
				g.addNodeAtPosition(relation.target, Position(0F, 0F))
			}
			g.addEdge(note, relation.target, relation)
			rec(g, processed, relation.target)
		}
	}

	override fun getNoteId() = noteId
}
