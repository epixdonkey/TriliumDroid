package eu.fliegendewurst.triliumdroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class IconTest {

	@Test
	fun isMaterialIconChecks() {
		assertTrue(Icon.isMaterialIcon("mat mat_desktop_cloud_stack"))
		assertTrue(Icon.isMaterialIcon("mat_desktop_cloud_stack"))
		assertTrue(Icon.isMaterialIcon("mat-desktop-cloud-stack"))
		assertTrue(Icon.isMaterialIcon("mat"))
		assertTrue(Icon.isMaterialIcon(" mat foo "))

		assertFalse(Icon.isMaterialIcon("bx bx-file-blank"))
		assertFalse(Icon.isMaterialIcon("bx bxs-user"))
		assertFalse(Icon.isMaterialIcon("unknown-icon"))
		assertFalse(Icon.isMaterialIcon(null))
		assertFalse(Icon.isMaterialIcon(""))
	}

	@Test
	fun loadAndLookupMaterialIcons() {
		val sampleJson = "{\"mat_desktop_cloud_stack\": \"\uF3BE\", \"mat_folder\": \"\uE2C7\"}"

		Icon.loadMaterialIcons(ByteArrayInputStream(sampleJson.toByteArray(Charsets.UTF_8)))

		assertEquals("\uF3BE", Icon.getUnicodeCharacter("mat mat_desktop_cloud_stack"))
		assertEquals("\uF3BE", Icon.getUnicodeCharacter("mat_desktop_cloud_stack"))
		assertEquals("\uF3BE", Icon.getUnicodeCharacter("mat-desktop-cloud-stack"))
		assertEquals("\uF3BE", Icon.getUnicodeCharacter("mat desktop_cloud_stack"))
		assertEquals("\uE2C7", Icon.getUnicodeCharacter("mat mat_folder"))
		assertEquals("\uE2C7", Icon.getUnicodeCharacter("mat folder"))

		assertEquals("mat mat_desktop_cloud_stack", Icon.getCssClass("\uF3BE"))
		assertEquals("mat mat_desktop_cloud_stack", Icon.getCssClass("mat mat_desktop_cloud_stack"))
		assertTrue(Icon.isMaterialGlyph("\uF3BE"))
	}

	@Test
	fun loadRealAssetFile() {
		val assetFile = java.io.File("src/main/assets/material_icons.json")
		if (assetFile.exists()) {
			assetFile.inputStream().use { Icon.loadMaterialIcons(it) }
			assertEquals("\uF3BE", Icon.getUnicodeCharacter("mat mat_desktop_cloud_stack"))
			assertEquals("mat mat_desktop_cloud_stack", Icon.getCssClass("\uF3BE"))
		}
	}

	@Test
	fun boxiconsLookup() {
		val glyph = Icon.getUnicodeCharacter("bx bx-file-blank")
		assertNotNull(glyph)
		assertEquals("bx bx-file-blank", Icon.getCssClass(glyph!!))
	}
}
