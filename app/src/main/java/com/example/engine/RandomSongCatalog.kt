package com.example.engine

import kotlin.random.Random

/**
 * Selects real, varied song queries when the user asks to "play music" or "play a song"
 * without specifying a track, artist, or playlist. Specific requests pass through untouched.
 */
object RandomSongCatalog {

    private val tracks: List<String> = listOf(
        "Midnight City M83",
        "Blinding Lights The Weeknd",
        "Dreams Fleetwood Mac",
        "Levitating Dua Lipa",
        "Take Five Dave Brubeck",
        "Bohemian Rhapsody Queen",
        "As It Was Harry Styles",
        "Electric Feel MGMT",
        "Fly Me to the Moon Frank Sinatra",
        "Golden Hour JVKE",
        "Sunflower Post Malone",
        "Clair de Lune Debussy",
        "Mr. Brightside The Killers",
        "Superstition Stevie Wonder",
        "September Earth Wind and Fire",
        "Lofi Beats Chill",
        "Starboy The Weeknd",
        "Africa Toto",
        "Heat Waves Glass Animals",
        "Feel Good Inc Gorillaz",
        "Billie Jean Michael Jackson",
        "Hotel California Eagles",
        "Cruel Summer Taylor Swift",
        "Sunday Morning Maroon 5"
    )

    private val genericMusicPhrases = setOf(
        "",
        "music",
        "some music",
        "any music",
        "random music",
        "song",
        "a song",
        "songs",
        "some songs",
        "any song",
        "random",
        "a random",
        "random song",
        "a random song",
        "track",
        "a track",
        "tracks",
        "random track",
        "a random track",
        "tune",
        "a tune",
        "tunes",
        "audio",
        "something",
        "anything",
        "playlist",
        "shuffled music",
        "shuffle music",
        "surprise me"
    )

    private val themedTrackCatalog: Map<String, List<String>> = mapOf(
        "christmas" to listOf(
            "Christmas in Our Hearts Jose Mari Chan",
            "All I Want for Christmas Is You Mariah Carey",
            "Last Christmas Wham!",
            "Jingle Bell Rock Bobby Helms",
            "Rockin Around the Christmas Tree Brenda Lee",
            "Its Beginning to Look a Lot Like Christmas Michael Buble",
            "Santa Tell Me Ariana Grande",
            "White Christmas Bing Crosby",
            "Feliz Navidad Jose Feliciano",
            "Have Yourself a Merry Little Christmas Frank Sinatra",
            "Let It Snow Dean Martin",
            "Silent Night Pentatonix",
            "Sleigh Ride The Ronettes",
            "Winter Wonderland Michael Buble",
            "Pasko Na Sinta Ko Gary Valenciano"
        ),
        "pop" to listOf(
            "Blinding Lights The Weeknd",
            "Levitating Dua Lipa",
            "As It Was Harry Styles",
            "Cruel Summer Taylor Swift",
            "Flowers Miley Cyrus",
            "Uptown Funk Bruno Mars",
            "Espresso Sabrina Carpenter"
        ),
        "jazz" to listOf(
            "Take Five Dave Brubeck",
            "Fly Me to the Moon Frank Sinatra",
            "So What Miles Davis",
            "Autumn Leaves Chet Baker",
            "What a Wonderful World Louis Armstrong",
            "Feeling Good Nina Simone"
        ),
        "rock" to listOf(
            "Bohemian Rhapsody Queen",
            "Hotel California Eagles",
            "Mr. Brightside The Killers",
            "Sweet Child O Mine Guns N Roses",
            "Dreams Fleetwood Mac",
            "Smells Like Teen Spirit Nirvana"
        ),
        "chill" to listOf(
            "Lofi Beats Chill",
            "Sunset Lover Petit Biscuit",
            "Golden Hour JVKE",
            "Electric Feel MGMT",
            "Sunday Morning Maroon 5",
            "Weightless Marconi Union"
        ),
        "acoustic" to listOf(
            "Perfect Ed Sheeran",
            "Banana Pancakes Jack Johnson",
            "Fast Car Tracy Chapman",
            "I'm Yours Jason Mraz",
            "Let Her Go Passenger"
        ),
        "love" to listOf(
            "Perfect Ed Sheeran",
            "All of Me John Legend",
            "Thinking Out Loud Ed Sheeran",
            "A Thousand Years Christina Perri",
            "Can't Help Falling in Love Elvis Presley",
            "Forevermore Side A"
        ),
        "opm" to listOf(
            "Christmas in Our Hearts Jose Mari Chan",
            "Tadhana Up Dharma Down",
            "Buwan Juan Karlos",
            "Kathang Isip Ben&Ben",
            "Mundo IV of Spades",
            "Raining in Manila Lola Amour"
        ),
        "classical" to listOf(
            "Clair de Lune Debussy",
            "Four Seasons Spring Vivaldi",
            "Moonlight Sonata Beethoven",
            "Nocturne Op 9 No 2 Chopin",
            "Canon in D Pachelbel"
        ),
        "workout" to listOf(
            "Eye of the Tiger Survivor",
            "Stronger Kanye West",
            "Till I Collapse Eminem",
            "Blinding Lights The Weeknd",
            "Can't Hold Us Macklemore"
        ),
        "happy" to listOf(
            "Happy Pharrell Williams",
            "September Earth Wind and Fire",
            "Walking on Sunshine Katrina and the Waves",
            "Uptown Funk Bruno Mars",
            "Good Vibrations The Beach Boys"
        ),
        "sad" to listOf(
            "Someone Like You Adele",
            "Fix You Coldplay",
            "All Too Well Taylor Swift",
            "Drivers License Olivia Rodrigo",
            "Let Somebody Go Coldplay"
        ),
        "rnb" to listOf(
            "Superstition Stevie Wonder",
            "Snooze SZA",
            "Redbone Childish Gambino",
            "No Scrubs TLC",
            "Leave the Door Open Silk Sonic"
        ),
        "hiphop" to listOf(
            "Sunflower Post Malone",
            "Lose Yourself Eminem",
            "Humble Kendrick Lamar",
            "Sicko Mode Travis Scott",
            "God's Plan Drake"
        ),
        "country" to listOf(
            "Take Me Home Country Roads John Denver",
            "Tennessee Whiskey Chris Stapleton",
            "Jolene Dolly Parton",
            "Wagon Wheel Darius Rucker"
        ),
        "kpop" to listOf(
            "Dynamite BTS",
            "How You Like That BLACKPINK",
            "Super Shy NewJeans",
            "Cupid FIFTY FIFTY",
            "Seven Jung Kook"
        ),
        "wedding" to listOf(
            "Canon in D Pachelbel",
            "At Last Etta James",
            "A Thousand Years Christina Perri",
            "Can't Help Falling in Love Elvis Presley",
            "All of Me John Legend",
            "Perfect Ed Sheeran",
            "Marry You Bruno Mars",
            "Make You Feel My Love Adele",
            "Thinking Out Loud Ed Sheeran",
            "Forevermore Side A",
            "Ikaw Yeng Constantino",
            "Stand By Me Ben E. King"
        ),
        "birthday" to listOf(
            "Happy Birthday to You Traditional",
            "Birthday Katy Perry",
            "In Da Club 50 Cent",
            "Celebration Kool and the Gang",
            "Birthday Beatles"
        ),
        "worship" to listOf(
            "Oceans Hillsong UNITED",
            "Amazing Grace Chris Tomlin",
            "10000 Reasons Matt Redman",
            "Way Maker Sinach",
            "Goodness of God Bethel Music"
        ),
        "party" to listOf(
            "Uptown Funk Bruno Mars",
            "I Gotta Feeling Black Eyed Peas",
            "Don't Stop Me Now Queen",
            "Levitating Dua Lipa",
            "September Earth Wind and Fire",
            "Dancing Queen ABBA"
        ),
        "oldies" to listOf(
            "Fly Me to the Moon Frank Sinatra",
            "Stand By Me Ben E. King",
            "My Girl The Temptations",
            "Hey Jude The Beatles",
            "Can't Help Falling in Love Elvis Presley",
            "What a Wonderful World Louis Armstrong"
        ),
        "sleep" to listOf(
            "Weightless Marconi Union",
            "Clair de Lune Debussy",
            "Gymnopedie No 1 Erik Satie",
            "Twinkle Twinkle Little Star Instrumental",
            "River Flows in You Yiruma"
        )
    )

    private val themeAliases: Map<String, String> = mapOf(
        "christmas" to "christmas",
        "xmas" to "christmas",
        "x-mas" to "christmas",
        "holiday" to "christmas",
        "holidays" to "christmas",
        "festive" to "christmas",
        "pasko" to "christmas",
        "pop" to "pop",
        "jazz" to "jazz",
        "smooth jazz" to "jazz",
        "rock" to "rock",
        "classic rock" to "rock",
        "lofi" to "chill",
        "lo-fi" to "chill",
        "lo fi" to "chill",
        "chill" to "chill",
        "relaxing" to "chill",
        "calm" to "chill",
        "study" to "chill",
        "sleep" to "chill",
        "ambient" to "chill",
        "acoustic" to "acoustic",
        "love" to "love",
        "romantic" to "love",
        "ballad" to "love",
        "opm" to "opm",
        "filipino" to "opm",
        "tagalog" to "opm",
        "pinoy" to "opm",
        "classical" to "classical",
        "piano" to "classical",
        "instrumental" to "classical",
        "workout" to "workout",
        "gym" to "workout",
        "upbeat" to "workout",
        "energetic" to "workout",
        "happy" to "happy",
        "feel good" to "happy",
        "party" to "happy",
        "sad" to "sad",
        "heartbreak" to "sad",
        "rnb" to "rnb",
        "r&b" to "rnb",
        "soul" to "rnb",
        "hip hop" to "hiphop",
        "hiphop" to "hiphop",
        "hip-hop" to "hiphop",
        "rap" to "hiphop",
        "country" to "country",
        "kpop" to "kpop",
        "k-pop" to "kpop",
        "korean" to "kpop",
        "wedding" to "wedding",
        "bridal" to "wedding",
        "marriage" to "wedding",
        "wedding reception" to "wedding",
        "first dance" to "wedding",
        "ceremony" to "wedding",
        "birthday" to "birthday",
        "bday" to "birthday",
        "happy birthday" to "birthday",
        "worship" to "worship",
        "gospel" to "worship",
        "praise" to "worship",
        "christian" to "worship",
        "party" to "party",
        "dance" to "party",
        "oldies" to "oldies",
        "80s" to "oldies",
        "90s" to "oldies",
        "throwback" to "oldies",
        "lullaby" to "sleep",
        "bedtime" to "sleep"
    )

    private val themedSongQueryRegex = Regex(
        "^(?:(?:a|an|some|any)\\s+)?(?:random\\s+)?([a-z0-9&-]+(?:\\s+[a-z0-9&-]+)?)\\s+(?:song|songs|music|track|tracks|tune|tunes|carol|carols|ballad|ballads|playlist)$",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    private var lastPickedTrack: String? = null

    private val genericDescriptiveSongRegex = Regex(
        "^(?:(?:a|an|some|any)\\s+)?(?:random\\s+)?[a-z0-9&-]+(?:\\s+[a-z0-9&-]+){0,2}\\s+(?:song|songs|music|track|tracks|tune|tunes|carol|carols|ballad|ballads|playlist)$",
        RegexOption.IGNORE_CASE
    )

    private val somethingDescriptorRegex = Regex(
        "^(?:something|anything)(?:\\s+.+)?$",
        RegexOption.IGNORE_CASE
    )

    private val songForPurposeRegex = Regex(
        "^(?:(?:a|an|some|any)\\s+)?(?:random\\s+)?(?:song|songs|music|track|tracks|tune|tunes|carol|carols|ballad|ballads|playlist)\\s+(?:for|to|about|like|with)\\s+.+$",
        RegexOption.IGNORE_CASE
    )

    fun isGenericOrRandomMusicQuery(rawQuery: String?): Boolean {
        val norm = rawQuery?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
        return norm in genericMusicPhrases || extractThemeKey(norm) != null
    }

    fun isDescriptiveGenreOrMoodQuery(rawQuery: String?): Boolean {
        val norm = rawQuery?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
        if (norm.isEmpty()) return false
        return isGenericOrRandomMusicQuery(norm) ||
            genericDescriptiveSongRegex.matches(norm) ||
            somethingDescriptorRegex.matches(norm) ||
            songForPurposeRegex.matches(norm)
    }

    fun extractThemeKey(rawQuery: String?): String? {
        val norm = rawQuery?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
        if (norm.isEmpty()) return null
        val m = themedSongQueryRegex.find(norm)
        if (m != null) {
            val candidateTheme = m.groupValues[1].trim()
            themeAliases[candidateTheme]?.let { return it }
        }
        if (somethingDescriptorRegex.matches(norm) || songForPurposeRegex.matches(norm)) {
            for ((alias, themeKey) in themeAliases) {
                if (Regex("(?:^|\\b)${Regex.escape(alias)}(?:\\b|$)").containsMatchIn(norm)) {
                    return themeKey
                }
            }
        }
        return null
    }

    fun pickRandomTrack(random: Random = Random.Default): String {
        val candidates = tracks.filter { it != lastPickedTrack }.ifEmpty { tracks }
        val chosen = candidates[random.nextInt(candidates.size)]
        lastPickedTrack = chosen
        return chosen
    }

    fun pickRandomTrackForTheme(themeKey: String, random: Random = Random.Default): String {
        val pool = themedTrackCatalog[themeKey] ?: tracks
        val candidates = pool.filter { it != lastPickedTrack }.ifEmpty { pool }
        val chosen = candidates[random.nextInt(candidates.size)]
        lastPickedTrack = chosen
        return chosen
    }

    /**
     * Returns (effectiveQuery, wasRandomized).
     * - Generic/unspecified queries like "music", "a song", or "random" resolve to a randomized track.
     * - Themed/genre random queries like "christmas song", "random christmas song", "jazz music"
     *   resolve to a randomized real song from that theme.
     * - Specific song titles/artists (e.g. "christmas in our hearts") are preserved verbatim.
     */
    fun resolveEffectiveSongQuery(rawQuery: String?, random: Random = Random.Default): Pair<String, Boolean> {
        val cleaned = rawQuery?.trim().orEmpty()
        val norm = cleaned.lowercase().replace(Regex("\\s+"), " ")
        if (norm in genericMusicPhrases) {
            return pickRandomTrack(random) to true
        }
        val themeKey = extractThemeKey(norm)
        if (themeKey != null) {
            return pickRandomTrackForTheme(themeKey, random) to true
        }
        return cleaned to false
    }

    fun allTracks(): List<String> = tracks

    fun tracksForTheme(themeKey: String): List<String> = themedTrackCatalog[themeKey].orEmpty()
}
