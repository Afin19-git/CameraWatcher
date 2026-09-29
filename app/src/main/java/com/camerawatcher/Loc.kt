package com.camerawatcher

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * Язык интерфейса: «авто» (как в системе) или English / Русский / Українська.
 * Ресурсы лежат в values (English по умолчанию), values-ru и values-uk.
 */
object Loc {
    val CODES = listOf("auto", "en", "ru", "uk")
    private val SUPPORTED = setOf("en", "ru", "uk")

    // Читаем настройки напрямую: Prefs инициализируется позже, чем создаётся базовый контекст.
    private fun savedCode(base: Context): String =
        base.getSharedPreferences("camerawatcher", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"

    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

    /** Контекст с нужным языком. Зовём в attachBaseContext и там, где строки нужны «на лету». */
    fun ctx(base: Context): Context {
        val code = savedCode(base)
        val loc = if (code == "auto") systemLocale() else Locale(code)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        return base.createConfigurationContext(cfg)
    }

    /** Какой из трёх языков реально используется (en/ru/uk). */
    fun effective(base: Context): String {
        val code = savedCode(base)
        val l = if (code == "auto") systemLocale().language else code
        return if (l in SUPPORTED) l else "en"
    }
}

/**
 * Имена папок на Google Drive. Не зависят от текущего языка интерфейса: язык фиксируется при входе в Drive
 * (Prefs.driveNamesLang), чтобы смена языка не создавала второе дерево папок.
 * Для тех, кто уже пользовался русской версией, значение по умолчанию — "ru" (их папки не меняются).
 */
object DriveNames {
    fun root(lang: String): String = when (lang) {
        "ru" -> "Камера видеонаблюдения"
        "uk" -> "Камера відеоспостереження"
        else -> "CameraWatcher"
    }

    fun part(lang: String, hour: Int): String {
        val i = when (hour) {
            in 6..11 -> 0
            in 12..17 -> 1
            in 18..21 -> 2
            else -> 3
        }
        return when (lang) {
            "ru" -> listOf("утро", "день", "вечер", "ночь")
            "uk" -> listOf("ранок", "день", "вечір", "ніч")
            else -> listOf("morning", "day", "evening", "night")
        }[i]
    }
}
