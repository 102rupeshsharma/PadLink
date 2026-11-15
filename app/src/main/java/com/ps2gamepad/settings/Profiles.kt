package com.ps2gamepad.settings

import com.ps2gamepad.model.Appearance
import com.ps2gamepad.model.ControllerProfile
import com.ps2gamepad.model.LastConnection
import com.ps2gamepad.model.ProfileStore
import com.ps2gamepad.model.StickSettings
import kotlinx.serialization.json.Json

/**
 * Built-in profiles. They only tune layout and analog response, as starting points for each game.
 * They are NOT verified against the games. Every profile sends the same HID buttons.
 */
object DefaultProfiles {
    const val STANDARD = "standard"
    const val GOW = "gow"
    const val GOW2 = "gow2"
    const val GTA_SA = "gta_sa"
    const val NFS_MW = "nfs_mw"
    const val CUSTOM = "custom"

    val all: List<ControllerProfile> = listOf(
        ControllerProfile(STANDARD, "Standard PS2", builtIn = true),
        // Combat-heavy: slightly bigger buttons for mashing, right stick is the evade flick -> quick response.
        ControllerProfile(
            GOW, "God of War", builtIn = true,
            appearance = Appearance(buttonScale = 1.1f),
            right = StickSettings(deadZone = 0.15f, sensitivity = 1.3f, curve = 0.8f),
        ),
        ControllerProfile(
            GOW2, "God of War II", builtIn = true,
            appearance = Appearance(buttonScale = 1.1f, buttonSpacing = 1.1f),
            right = StickSettings(deadZone = 0.15f, sensitivity = 1.3f, curve = 0.8f),
        ),
        // Third-person aiming with the right stick: finer control near the centre.
        ControllerProfile(
            GTA_SA, "GTA San Andreas", builtIn = true,
            right = StickSettings(deadZone = 0.08f, sensitivity = 0.9f, curve = 1.4f),
        ),
        // Steering on the left stick: small dead zone, curve for fine corrections, full lock before the edge.
        ControllerProfile(
            NFS_MW, "Need for Speed Most Wanted", builtIn = true,
            left = StickSettings(deadZone = 0.06f, curve = 1.5f, maxRange = 0.9f),
        ),
        ControllerProfile(CUSTOM, "Custom Profile", builtIn = true),
    )

    fun byId(id: String): ControllerProfile? = all.firstOrNull { it.id == id }

    fun store() = ProfileStore(activeId = STANDARD, profiles = all)
}

/** Pure profile operations. Every function returns a new store and never breaks the invariants:
 *  at least one profile, unique ids, unique names, active id always valid, built-ins never removed. */
object ProfileManager {

    /** User loadouts (non-built-in profiles) are limited to this number. */
    const val MAX_LOADOUTS = 10

    fun loadouts(s: ProfileStore): List<ControllerProfile> = s.profiles.filter { !it.builtIn }
    fun canCreateLoadout(s: ProfileStore): Boolean = loadouts(s).size < MAX_LOADOUTS

    /**
     * New loadout as a copy of [templateId]'s layout, look, and analog settings (so the user
     * starts from what they know), with a new id and name. Refused (store unchanged) at the limit.
     */
    fun createFrom(s: ProfileStore, name: String, templateId: String, idGen: () -> String): ProfileStore {
        if (!canCreateLoadout(s)) return s
        val src = s.profiles.firstOrNull { it.id == templateId } ?: s.active
        val p = src.copy(id = uniqueId(s, idGen), name = uniqueName(s, name.ifBlank { "Loadout" }), builtIn = false).sanitized()
        return s.copy(profiles = s.profiles + p, activeId = p.id)
    }

    fun switchTo(s: ProfileStore, id: String): ProfileStore =
        if (s.profiles.any { it.id == id }) s.copy(activeId = id) else s

    fun create(s: ProfileStore, name: String, idGen: () -> String): ProfileStore {
        if (!canCreateLoadout(s)) return s
        val p = ControllerProfile(id = uniqueId(s, idGen), name = uniqueName(s, name.ifBlank { "New profile" }))
        return s.copy(profiles = s.profiles + p.sanitized(), activeId = p.id)
    }

    fun duplicate(s: ProfileStore, id: String, idGen: () -> String): ProfileStore {
        val src = s.profiles.firstOrNull { it.id == id } ?: return s
        if (!canCreateLoadout(s)) return s
        val copy = src.copy(id = uniqueId(s, idGen), name = uniqueName(s, "${src.name} copy"), builtIn = false)
        return s.copy(profiles = s.profiles + copy, activeId = copy.id)
    }

    fun rename(s: ProfileStore, id: String, name: String): ProfileStore {
        val clean = name.trim().take(ControllerProfile.MAX_NAME)
        if (clean.isEmpty()) return s
        val others = s.profiles.filter { it.id != id }
        if (others.any { it.name.equals(clean, ignoreCase = true) }) return s
        return s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Built-in profiles cannot be deleted. Deleting the active profile activates the first one. */
    fun delete(s: ProfileStore, id: String): ProfileStore {
        val p = s.profiles.firstOrNull { it.id == id } ?: return s
        if (p.builtIn || s.profiles.size <= 1) return s
        val rest = s.profiles.filter { it.id != id }
        return s.copy(profiles = rest, activeId = if (s.activeId == id) rest.first().id else s.activeId)
    }

    /** Built-in: back to factory values (keeps a user rename). Custom: back to standard settings. */
    fun restoreDefaults(s: ProfileStore, id: String): ProfileStore = s.copy(profiles = s.profiles.map {
        if (it.id != id) it
        else (DefaultProfiles.byId(id) ?: ControllerProfile(id, it.name)).copy(name = it.name, builtIn = it.builtIn)
    })

    fun updateActive(s: ProfileStore, f: (ControllerProfile) -> ControllerProfile): ProfileStore =
        s.copy(profiles = s.profiles.map { if (it.id == s.activeId) f(it).copy(id = it.id, builtIn = it.builtIn).sanitized() else it })

    fun recordConnection(s: ProfileStore, c: LastConnection): ProfileStore = s.copy(lastConnection = c)

    /** Repairs a loaded store: drops duplicates, re-adds missing built-ins, fixes the active id. */
    fun normalize(s: ProfileStore?): ProfileStore {
        if (s == null) return DefaultProfiles.store()
        val seen = HashSet<String>()
        val profiles = s.profiles.filter { it.id.isNotBlank() && seen.add(it.id) }.map { it.sanitized() }.toMutableList()
        for (b in DefaultProfiles.all) if (profiles.none { it.id == b.id }) profiles.add(b)
        val active = if (profiles.any { it.id == s.activeId }) s.activeId else DefaultProfiles.STANDARD
        // copy(): every other field (and any future one) is kept as stored
        return s.copy(activeId = active, profiles = profiles)
    }

    private fun uniqueId(s: ProfileStore, idGen: () -> String): String {
        var id = idGen()
        while (s.profiles.any { it.id == id }) id = idGen()
        return id
    }

    /**
     * Unique among the user's loadouts. Built-in presets don't count: a loadout called
     * "God of War" must not silently become "God of War 2" (which reads like the sequel).
     */
    private fun uniqueName(s: ProfileStore, base: String): String {
        val b = base.trim().take(ControllerProfile.MAX_NAME - 3)
        val taken = loadouts(s)
        if (taken.none { it.name.equals(b, true) }) return b
        var i = 2
        while (taken.any { it.name.equals("$b $i", true) }) i++
        return "$b $i"
    }
}

/** JSON (de)serialisation. Unknown fields are ignored; corrupt data falls back to defaults. */
object ProfileCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    fun encode(s: ProfileStore): String = json.encodeToString(ProfileStore.serializer(), s)

    fun decode(text: String?): ProfileStore =
        ProfileManager.normalize(text?.let { runCatching { json.decodeFromString(ProfileStore.serializer(), it) }.getOrNull() })
}
