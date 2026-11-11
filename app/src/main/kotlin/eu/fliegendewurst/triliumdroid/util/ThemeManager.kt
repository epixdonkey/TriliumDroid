package eu.fliegendewurst.triliumdroid.util

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat

object ThemeManager {

	data class AccentPreset(
		val id: String,
		val name: String,
		val color1: Int,
		val color2: Int,
		val isGradient: Boolean
	)

	val presets: List<AccentPreset> = listOf(
		AccentPreset(
			id = "obsidian_indigo",
			name = "Obsidian Indigo (Gradient)",
			color1 = Color.parseColor("#4F46E5"),
			color2 = Color.parseColor("#7C3AED"),
			isGradient = true
		),
		AccentPreset(
			id = "cyber_aurora",
			name = "Cyber Aurora (Gradient)",
			color1 = Color.parseColor("#0891B2"),
			color2 = Color.parseColor("#6366F1"),
			isGradient = true
		),
		AccentPreset(
			id = "ocean_blue",
			name = "Ocean Blue (Gradient)",
			color1 = Color.parseColor("#0284C7"),
			color2 = Color.parseColor("#2563EB"),
			isGradient = true
		),
		AccentPreset(
			id = "emerald_forest",
			name = "Emerald Forest (Gradient)",
			color1 = Color.parseColor("#059669"),
			color2 = Color.parseColor("#0D9488"),
			isGradient = true
		),
		AccentPreset(
			id = "sunset_glow",
			name = "Sunset Glow (Gradient)",
			color1 = Color.parseColor("#EA580C"),
			color2 = Color.parseColor("#DB2777"),
			isGradient = true
		),
		AccentPreset(
			id = "royal_amethyst",
			name = "Royal Amethyst (Gradient)",
			color1 = Color.parseColor("#7E22CE"),
			color2 = Color.parseColor("#C026D3"),
			isGradient = true
		),
		AccentPreset(
			id = "indigo_solid",
			name = "Indigo (Solid)",
			color1 = Color.parseColor("#4F46E5"),
			color2 = Color.parseColor("#4F46E5"),
			isGradient = false
		),
		AccentPreset(
			id = "teal_solid",
			name = "Teal (Solid)",
			color1 = Color.parseColor("#0D9488"),
			color2 = Color.parseColor("#0D9488"),
			isGradient = false
		),
		AccentPreset(
			id = "rose_solid",
			name = "Crimson Rose (Solid)",
			color1 = Color.parseColor("#E11D48"),
			color2 = Color.parseColor("#E11D48"),
			isGradient = false
		),
		AccentPreset(
			id = "slate_minimal",
			name = "Slate Minimal (Solid)",
			color1 = Color.parseColor("#334155"),
			color2 = Color.parseColor("#334155"),
			isGradient = false
		),
		AccentPreset(
			id = "trilium_classic",
			name = "Amber Gold / Classic",
			color1 = Color.parseColor("#F59E0B"),
			color2 = Color.parseColor("#D97706"),
			isGradient = true
		)
	)

	fun currentPresetId(): String = Preferences.accentPreset()

	fun getColors(): Pair<Int, Int> {
		val c1 = Preferences.accentColor1()
		val c2 = if (Preferences.accentIsGradient()) Preferences.accentColor2() else c1
		return Pair(c1, c2)
	}

	fun primaryColor(): Int = Preferences.accentColor1()

	fun secondaryColor(): Int =
		if (Preferences.accentIsGradient()) Preferences.accentColor2() else Preferences.accentColor1()

	fun primaryHex(): String = String.format("#%06X", 0xFFFFFF and primaryColor())

	fun contrastColor(color1: Int, color2: Int = color1): Int {
		val avg = ColorUtils.blendARGB(color1, color2, 0.5f)
		val luminance = ColorUtils.calculateLuminance(avg)
		return if (luminance > 0.45) Color.parseColor("#14161B") else Color.WHITE
	}

	fun onAccentColor(): Int {
		val (c1, c2) = getColors()
		return contrastColor(c1, c2)
	}

	fun currentThemeDisplayName(): String {
		val id = currentPresetId()
		if (id == "custom") {
			val (c1, c2) = getColors()
			val h1 = String.format("#%06X", 0xFFFFFF and c1)
			val h2 = String.format("#%06X", 0xFFFFFF and c2)
			return if (Preferences.accentIsGradient() && c1 != c2) {
				"Custom ($h1 → $h2)"
			} else {
				"Custom ($h1)"
			}
		}
		return presets.firstOrNull { it.id == id }?.name ?: presets.first().name
	}

	fun createGradientDrawable(
		cornerRadiusPx: Float = 0f,
		color1: Int = primaryColor(),
		color2: Int = secondaryColor()
	): GradientDrawable {
		return GradientDrawable(
			GradientDrawable.Orientation.LEFT_RIGHT,
			intArrayOf(color1, color2)
		).apply {
			cornerRadius = cornerRadiusPx
		}
	}

	fun createTreeItemDrawable(context: Context, selected: Boolean): android.graphics.drawable.Drawable {
		val density = context.resources.displayMetrics.density
		val radius = 10f * density
		return if (selected) {
			val (c1, c2) = getColors()
			createGradientDrawable(radius, c1, c2)
		} else {
			GradientDrawable().apply {
				cornerRadius = radius
				setColor(Color.TRANSPARENT)
			}
		}
	}

	fun applyToToolbar(
		activity: Activity,
		toolbar: Toolbar,
		titleView: TextView? = null,
		iconView: TextView? = null
	) {
		val (c1, c2) = getColors()
		val onColor = contrastColor(c1, c2)
		toolbar.background = createGradientDrawable(0f, c1, c2)
		toolbar.setTitleTextColor(onColor)
		titleView?.setTextColor(onColor)
		iconView?.setTextColor(onColor)
		toolbar.navigationIcon?.setTint(onColor)
		toolbar.overflowIcon?.setTint(onColor)
		val menu = toolbar.menu
		for (i in 0 until menu.size()) {
			menu.getItem(i)?.icon?.setTint(onColor)
		}

		activity.window.statusBarColor = ColorUtils.blendARGB(c1, Color.BLACK, 0.12f)
		val isLightStatus = ColorUtils.calculateLuminance(c1) > 0.45
		WindowInsetsControllerCompat(activity.window, activity.window.decorView)
			.isAppearanceLightStatusBars = isLightStatus
	}

	fun tintToolbarMenu(toolbar: Toolbar) {
		val onColor = onAccentColor()
		toolbar.navigationIcon?.setTint(onColor)
		toolbar.overflowIcon?.setTint(onColor)
		val menu = toolbar.menu
		for (i in 0 until menu.size()) {
			menu.getItem(i)?.icon?.setTint(onColor)
		}
	}

	fun showAccentDialog(activity: Activity, onChanged: () -> Unit) {
		val density = activity.resources.displayMetrics.density
		val pad = (16 * density).toInt()
		val smallPad = (10 * density).toInt()

		val scroll = ScrollView(activity)
		val container = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(pad, smallPad, pad, smallPad)
		}
		scroll.addView(container)

		lateinit var dialog: AlertDialog

		fun addRow(
			title: String,
			c1: Int,
			c2: Int,
			isSelected: Boolean,
			onClick: () -> Unit
		) {
			val row = LinearLayout(activity).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				setPadding(smallPad, smallPad, smallPad, smallPad)
				background = GradientDrawable().apply {
					cornerRadius = 12f * density
					if (isSelected) {
						setStroke((2 * density).toInt(), c1)
						setColor(ColorUtils.setAlphaComponent(c1, 28))
					} else {
						setColor(Color.TRANSPARENT)
					}
				}
				setOnClickListener { onClick() }
			}

			val swatch = View(activity).apply {
				layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (28 * density).toInt()).apply {
					marginEnd = (14 * density).toInt()
				}
				background = createGradientDrawable(8f * density, c1, c2)
			}

			val label = TextView(activity).apply {
				layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
				text = title
				textSize = 15f
				if (isSelected) {
					setTypeface(typeface, Typeface.BOLD)
				}
			}

			val check = TextView(activity).apply {
				text = if (isSelected) "✓" else ""
				textSize = 16f
				setTypeface(typeface, Typeface.BOLD)
			}

			row.addView(swatch)
			row.addView(label)
			row.addView(check)
			container.addView(row)
		}

		val currentId = currentPresetId()
		for (preset in presets) {
			val c2 = if (preset.isGradient) preset.color2 else preset.color1
			addRow(preset.name, preset.color1, c2, currentId == preset.id) {
				Preferences.setAccentTheme(preset.id, preset.color1, preset.color2, preset.isGradient)
				dialog.dismiss()
				onChanged()
			}
		}

		val (customC1, customC2) = getColors()
		addRow(
			"Custom color / gradient…",
			customC1,
			customC2,
			currentId == "custom"
		) {
			dialog.dismiss()
			showCustomGradientDialog(activity, onChanged)
		}

		dialog = AlertDialog.Builder(activity)
			.setTitle("Accent color & gradient")
			.setView(scroll)
			.setNegativeButton(android.R.string.cancel, null)
			.create()
		dialog.show()
	}

	private fun showCustomGradientDialog(activity: Activity, onChanged: () -> Unit) {
		val density = activity.resources.displayMetrics.density
		val pad = (18 * density).toInt()

		var color1 = Preferences.accentColor1()
		var color2 = Preferences.accentColor2()
		var useGradient = Preferences.accentIsGradient()

		val scroll = ScrollView(activity)
		val root = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(pad, pad / 2, pad, pad / 2)
		}
		scroll.addView(root)

		val preview = TextView(activity).apply {
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				(52 * density).toInt()
			).apply {
				bottomMargin = (14 * density).toInt()
			}
			gravity = Gravity.CENTER
			text = "Trilium Notes — Preview"
			textSize = 16f
			setTypeface(typeface, Typeface.BOLD)
		}
		root.addView(preview)

		fun updatePreview() {
			val effC2 = if (useGradient) color2 else color1
			preview.background = createGradientDrawable(12f * density, color1, effC2)
			preview.setTextColor(contrastColor(color1, effC2))
		}

		fun createColorSection(
			sectionTitle: String,
			initialColor: Int,
			onColorChanged: (Int) -> Unit
		): LinearLayout {
			val box = LinearLayout(activity).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
			}

			val headerRow = LinearLayout(activity).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
			}
			val titleTv = TextView(activity).apply {
				layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
				text = sectionTitle
				setTypeface(typeface, Typeface.BOLD)
			}
			val hexInput = EditText(activity).apply {
				layoutParams = LinearLayout.LayoutParams((110 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
				setText(String.format("#%06X", 0xFFFFFF and initialColor))
				textSize = 14f
				isSingleLine = true
			}
			headerRow.addView(titleTv)
			headerRow.addView(hexInput)
			box.addView(headerRow)

			var updatingFromSliders = false
			var updatingFromHex = false

			val rBar = SeekBar(activity).apply { max = 255; progress = Color.red(initialColor) }
			val gBar = SeekBar(activity).apply { max = 255; progress = Color.green(initialColor) }
			val bBar = SeekBar(activity).apply { max = 255; progress = Color.blue(initialColor) }

			fun addSlider(label: String, bar: SeekBar) {
				val row = LinearLayout(activity).apply {
					orientation = LinearLayout.HORIZONTAL
					gravity = Gravity.CENTER_VERTICAL
				}
				val tv = TextView(activity).apply {
					text = label
					layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
				}
				bar.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
				row.addView(tv)
				row.addView(bar)
				box.addView(row)
			}

			addSlider("R", rBar)
			addSlider("G", gBar)
			addSlider("B", bBar)

			val seekListener = object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
					if (updatingFromHex) return
					updatingFromSliders = true
					val c = Color.rgb(rBar.progress, gBar.progress, bBar.progress)
					hexInput.setText(String.format("#%06X", 0xFFFFFF and c))
					onColorChanged(c)
					updatePreview()
					updatingFromSliders = false
				}
				override fun onStartTrackingTouch(seekBar: SeekBar?) {}
				override fun onStopTrackingTouch(seekBar: SeekBar?) {}
			}
			rBar.setOnSeekBarChangeListener(seekListener)
			gBar.setOnSeekBarChangeListener(seekListener)
			bBar.setOnSeekBarChangeListener(seekListener)

			hexInput.addTextChangedListener(object : TextWatcher {
				override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
				override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
				override fun afterTextChanged(s: Editable?) {
					if (updatingFromSliders) return
					val raw = s?.toString()?.trim() ?: return
					val normalized = if (raw.startsWith("#")) raw else "#$raw"
					if (normalized.length == 7) {
						try {
							val parsed = Color.parseColor(normalized)
							updatingFromHex = true
							rBar.progress = Color.red(parsed)
							gBar.progress = Color.green(parsed)
							bBar.progress = Color.blue(parsed)
							onColorChanged(parsed)
							updatePreview()
							updatingFromHex = false
						} catch (_: Exception) {
						}
					}
				}
			})

			return box
		}

		val section1 = createColorSection("Primary color (Start)", color1) { color1 = it }
		root.addView(section1)

		val gradientCheck = CheckBox(activity).apply {
			text = "Enable 2-color gradient"
			isChecked = useGradient
		}
		root.addView(gradientCheck)

		val section2 = createColorSection("Secondary color (End)", color2) { color2 = it }
		section2.visibility = if (useGradient) View.VISIBLE else View.GONE
		root.addView(section2)

		gradientCheck.setOnCheckedChangeListener { _, isChecked ->
			useGradient = isChecked
			section2.visibility = if (isChecked) View.VISIBLE else View.GONE
			updatePreview()
		}

		updatePreview()

		AlertDialog.Builder(activity)
			.setTitle("Custom color / gradient")
			.setView(scroll)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				Preferences.setAccentTheme("custom", color1, color2, useGradient)
				onChanged()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}
}
