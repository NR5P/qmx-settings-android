package com.qmxsettings.app.profiles

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class EntryKind { MENU, CAT }

/**
 * One remembered setting.
 * MENU: [key] is the menu name path ("CW › CW Keyer › Keyer mode"), [column] set for Band config cells.
 * CAT:  [key] is the two-letter code ("KS").
 */
data class ProfileEntry(
    val kind: EntryKind,
    val key: String,
    val value: String,
    val column: Int? = null,
    val label: String = key,
) {
    val id: String get() = "$kind:$key${column?.let { "[$it]" } ?: ""}"
}

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val radio: String = "",
    val firmware: String = "",
    val entries: List<ProfileEntry>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "qmx-settings-profile/1")
        put("id", id)
        put("name", name)
        put("notes", notes)
        put("createdAt", createdAt)
        put("radio", radio)
        put("firmware", firmware)
        put("entries", JSONArray().apply {
            for (e in entries) put(JSONObject().apply {
                put("kind", e.kind.name)
                put("key", e.key)
                put("value", e.value)
                e.column?.let { put("column", it) }
                put("label", e.label)
            })
        })
    }

    companion object {
        fun fromJson(o: JSONObject, newId: Boolean = false): Profile {
            val a = o.getJSONArray("entries")
            val entries = List(a.length()) { i ->
                val e = a.getJSONObject(i)
                ProfileEntry(
                    kind = EntryKind.valueOf(e.getString("kind")),
                    key = e.getString("key"),
                    value = e.getString("value"),
                    column = if (e.has("column")) e.getInt("column") else null,
                    label = e.optString("label", e.getString("key")),
                )
            }
            return Profile(
                id = if (newId || !o.has("id")) UUID.randomUUID().toString() else o.getString("id"),
                name = o.optString("name", "Imported"),
                notes = o.optString("notes", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                radio = o.optString("radio", ""),
                firmware = o.optString("firmware", ""),
                entries = entries,
            )
        }
    }
}

/** Profiles saved as JSON files in the app's private storage. */
class ProfileStore(private val dir: File) {
    init { dir.mkdirs() }

    fun all(): List<Profile> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { f -> try { Profile.fromJson(JSONObject(f.readText())) } catch (_: Exception) { null } }
        .sortedBy { it.name.lowercase() }

    fun save(p: Profile) {
        val tmp = File(dir, "${p.id}.tmp")
        tmp.writeText(p.toJson().toString(2))
        tmp.renameTo(File(dir, "${p.id}.json"))
    }

    fun delete(p: Profile) { File(dir, "${p.id}.json").delete() }
}

/** One pending change when applying a profile. */
data class PlannedChange(val entry: ProfileEntry, val current: String?, val found: Boolean) {
    val changes: Boolean get() = !found || current == null || !same(current, entry.value)

    companion object {
        fun same(a: String, b: String): Boolean {
            if (a.trim().equals(b.trim(), ignoreCase = true)) return true
            val x = a.trim().toDoubleOrNull(); val y = b.trim().toDoubleOrNull()
            return x != null && y != null && x == y
        }
    }
}
