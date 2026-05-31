package io.github.aedev.flow.ui.screens.music

import java.util.Locale

private val hebrewMusicText = mapOf(
    "MUSIC" to "מוזיקה",
    "Music" to "מוזיקה",
    "Listen again" to "להאזין שוב",
    "Your Daily Discover" to "הגילוי היומי שלך",
    "Quick picks" to "בחירות מהירות",
    "Speed dial" to "גישה מהירה",
    "Shuffle play" to "ניגון אקראי",
    "Recommended" to "מומלץ",
    "Recently played" to "הושמע לאחרונה",
    "Music videos" to "קליפים",
    "Music videos for you" to "קליפים בשבילך",
    "Live performances" to "הופעות חיות",
    "New releases" to "חדשים",
    "Popular artists" to "אמנים פופולריים",
    "Mixed for you" to "מיקסים בשבילך",
    "Moods & Genres" to "מצבי רוח וז׳אנרים",
    "Mood and Genres" to "מצבי רוח וז׳אנרים",
    "From the community" to "מהקהילה",
    "Top Albums" to "אלבומים מובילים",
    "Top result" to "התוצאה המובילה",
    "Albums" to "אלבומים",
    "Songs" to "שירים",
    "Community playlists" to "פלייליסטים מהקהילה",
    "Artist" to "אמן",
    "Radio" to "רדיו",
    "NOW PLAYING" to "מתנגן עכשיו",
    "Commute" to "נסיעה",
    "Party" to "מסיבה",
    "Relax" to "רוגע",
    "Relaxing" to "רוגע",
    "Workout" to "אימון",
    "Feel good" to "מצב רוח טוב",
    "Energize" to "אנרגיה",
    "Podcasts" to "פודקאסטים",
    "Focus" to "פוקוס",
    "Romance" to "רומנטי",
    "Sad" to "עצוב",
    "Sleep" to "שינה",
    "Discover" to "גילוי",
    "Popular" to "פופולרי",
    "Deep cuts" to "פנינים נסתרות"
)

fun localizeMusicText(text: String, language: String = Locale.getDefault().language): String {
    if (language != "he" && language != "iw") return text
    val trimmed = text.trim()
    return hebrewMusicText[trimmed] ?: text
}
