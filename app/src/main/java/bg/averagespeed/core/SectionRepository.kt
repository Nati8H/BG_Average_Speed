package bg.averagespeed.core

import android.content.Context
import java.io.File

/**
 * Вградените отсечки идват от assets/sections.json, а добавените от потребителя
 * се пазят във файл в паметта на приложението. Вградени отсечки могат да се скриват.
 */
class SectionRepository(private val context: Context) {

    private val userFile get() = File(context.filesDir, "user_sections.json")
    private val prefs get() = context.getSharedPreferences("sections", Context.MODE_PRIVATE)

    fun builtIn(): List<Section> =
        context.assets.open("sections.json").bufferedReader().use { SectionJson.parse(it.readText(), userDefined = false) }

    fun user(): List<Section> =
        if (userFile.exists()) runCatching { SectionJson.parse(userFile.readText(), userDefined = true) }.getOrDefault(emptyList())
        else emptyList()

    private fun hiddenIds(): Set<String> = prefs.getStringSet("hidden", emptySet()) ?: emptySet()

    /** Всички активни отсечки: потребителските + нескритите вградени, с променените ограничения. */
    fun all(): List<Section> {
        val hidden = hiddenIds()
        return (user() + builtIn().filterNot { it.id in hidden }).map { s ->
            val limit = prefs.getInt(LIMIT_PREFIX + s.id, 0)
            if (limit > 0) s.copy(limitKmh = limit) else s
        }
    }

    /** Променя ограничението на скоростта за отсечка (напр. ако се различава от стандартното). */
    fun setLimit(section: Section, limitKmh: Int) {
        prefs.edit().putInt(LIMIT_PREFIX + section.id, limitKmh).apply()
    }

    fun hiddenCount(): Int = hiddenIds().size

    fun addUser(section: Section) {
        val list = user().filterNot { it.id == section.id } + section
        userFile.writeText(SectionJson.toJson(list))
    }

    fun importUser(json: String): Int {
        val imported = SectionJson.parse(json, userDefined = true)
        val ids = imported.map { it.id }.toSet()
        userFile.writeText(SectionJson.toJson(user().filterNot { it.id in ids } + imported))
        return imported.size
    }

    fun delete(section: Section) {
        if (section.userDefined) {
            userFile.writeText(SectionJson.toJson(user().filterNot { it.id == section.id }))
        } else {
            prefs.edit().putStringSet("hidden", hiddenIds() + section.id).apply()
        }
    }

    fun restoreBuiltIn() {
        prefs.edit().remove("hidden").apply()
    }

    fun exportAll(): String = SectionJson.toJson(all())

    private companion object {
        const val LIMIT_PREFIX = "limit_"
    }
}
