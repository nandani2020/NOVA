package com.example.engine

import android.content.Context
import com.example.data.SafetyTier
import com.example.data.ToolCallRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic Alexa-Style Everyday Assistant Engine (Process A, < 5ms offline + live HTTPS weather).
 *
 * Handles high-frequency everyday voice questions cleanly without ever misrouting to Wikipedia trivia:
 * - Current time, date, day of week, tomorrow/yesterday, year/month, holiday countdowns
 * - World clock across 60+ global cities & timezones
 * - Live weather & temperature (Open-Meteo / wttr.in, zero API key required)
 * - Quick math, percentages, tips, bill splitting, square roots, exponents
 * - Unit conversions (temperature, length, weight, volume/cooking, speed, data)
 * - Coin flip, dice roller (d6 / NdM), random number generator, Rock-Paper-Scissors, spelling
 * - Everyday greetings, thanks, identity & "what can you do"
 */
object AlexaUtilityEngine {

    @Volatile
    internal var testWeatherOverride: ((String) -> String?)? = null

    @Volatile
    internal var testNowMillisOverride: Long? = null

    private fun nowMillis(): Long = testNowMillisOverride ?: System.currentTimeMillis()

    // ── Regex Matchers ──────────────────────────────────────────────────────────────────

    private val worldTimeRegex = Regex(
        "^(?:(?:what(?:'s|s|\\s+is)|tell\\s+me|check|give\\s+me)\\s+)?(?:the\\s+)?(?:current\\s+|local\\s+)?time\\s+(?:is\\s+it\\s+)?(?:right\\s+now\\s+)?in\\s+([a-zA-Z][a-zA-Z .'-]{1,40})(?:\\s+(?:right\\s+now|now))?$|" +
            "^what\\s+time\\s+is\\s+it\\s+in\\s+([a-zA-Z][a-zA-Z .'-]{1,40})(?:\\s+(?:right\\s+now|now))?$",
        RegexOption.IGNORE_CASE
    )

    private val currentTimeRegex = Regex(
        "^(?:what\\s+time\\s+is\\s+it|what(?:'s|s|\\s+is)\\s+(?:the\\s+)?(?:current\\s+|local\\s+)?time(?:\\s+and\\s+date)?|tell\\s+me\\s+(?:the\\s+)?(?:current\\s+|local\\s+)?time|tell\\s+me\\s+what\\s+time\\s+it\\s+is|give\\s+me\\s+(?:the\\s+)?(?:current\\s+|local\\s+)?time|check\\s+(?:the\\s+)?(?:current\\s+)?time|do\\s+you\\s+(?:have|know)\\s+(?:the\\s+)?(?:current\\s+)?time|(?:the\\s+)?current\\s+time|time\\s+(?:right\\s+)?now|time\\s+and\\s+date|date\\s+and\\s+time|what\\s+does\\s+the\\s+clock\\s+say)(?:\\s+(?:is\\s+it|right\\s+now|now|today|locally|here))*$",
        RegexOption.IGNORE_CASE
    )

    private val currentDateRegex = Regex(
        "^(?:what(?:'s|s|\\s+is)\\s+(?:the\\s+)?(?:current\\s+|today'?s?\\s+)?date|what\\s+date\\s+is\\s+(?:it|today)|tell\\s+me\\s+(?:the\\s+|today'?s?\\s+)?date|(?:the\\s+)?(?:current\\s+|today'?s?\\s+)?date)(?:\\s+(?:today|right\\s+now|now))*$",
        RegexOption.IGNORE_CASE
    )

    private val currentDayRegex = Regex(
        "^(?:what\\s+day(?:\\s+of\\s+the\\s+week)?\\s+is\\s+(?:it|today)|what(?:'s|s|\\s+is)\\s+today(?:'s\\s+day)?)(?:\\s+(?:today|now))?$",
        RegexOption.IGNORE_CASE
    )

    private val relativeDayRegex = Regex(
        "^(?:what\\s+(?:day|date)\\s+(?:is|will\\s+be)\\s+(tomorrow)|what\\s+(?:day|date)\\s+(?:was)\\s+(yesterday)|what\\s+(year|month)\\s+is\\s+(?:it|this))$",
        RegexOption.IGNORE_CASE
    )

    private val daysUntilRegex = Regex(
        "^how\\s+many\\s+days\\s+(?:until|till|left\\s+(?:until|till|to)|to)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val weatherRegex = Regex(
        "^(?:what(?:'s|s|\\s+is)|how(?:'s|s|\\s+is)|check|tell\\s+me|give\\s+me|show\\s+me)\\s+(?:the\\s+)?(?:current\\s+|today'?s?\\s+|local\\s+)?(?:weather|temperature|forecast)(?:\\s+(?:forecast|report|like))?(?:\\s+(?:for|in|at)\\s+([a-zA-Z][a-zA-Z .,'-]{1,40}))?(?:\\s+(?:today|tonight|right\\s+now|now|tomorrow|this\\s+week|outside))?$|" +
            "^(?:weather|temperature|forecast)(?:\\s+(?:for|in|at)\\s+([a-zA-Z][a-zA-Z .,'-]{1,40}))?(?:\\s+(?:today|tonight|right\\s+now|now|tomorrow))?$|" +
            "^(?:is\\s+it\\s+(?:going\\s+to\\s+)?(?:rain|raining|hot|cold|sunny|cloudy|snowing)|will\\s+it\\s+(?:rain|snow)|do\\s+i\\s+need\\s+an\\s+umbrella)(?:\\s+(?:in|at)\\s+([a-zA-Z][a-zA-Z .,'-]{1,40}))?(?:\\s+(?:today|tonight|now|outside|tomorrow))?$",
        RegexOption.IGNORE_CASE
    )

    private val coinFlipRegex = Regex(
        "^(?:flip|toss)\\s+(?:a\\s+)?coin$|^heads\\s+or\\s+tails$",
        RegexOption.IGNORE_CASE
    )

    private val diceRollRegex = Regex(
        "^roll\\s+(?:(a|an|one|two|three|four|five|six|\\d+)\\s+)?(?:(\\d+)[-\\s]?sided\\s+)?(?:die|dice|d(\\d+))$",
        RegexOption.IGNORE_CASE
    )

    private val randomNumberRegex = Regex(
        "^(?:pick|choose|give\\s+me|generate)\\s+(?:a\\s+)?random\\s+number(?:\\s+(?:between|from)\\s+(-?\\d+)\\s+(?:and|to)\\s+(-?\\d+))?$|" +
            "^random\\s+number(?:\\s+(?:between|from)\\s+(-?\\d+)\\s+(?:and|to)\\s+(-?\\d+))?$",
        RegexOption.IGNORE_CASE
    )

    private val rpsRegex = Regex(
        "^(?:play\\s+)?rock\\s*,?\\s*paper\\s*,?\\s*scissors$",
        RegexOption.IGNORE_CASE
    )

    private val spellWordRegex = Regex(
        "^(?:how\\s+do\\s+(?:you|i)\\s+spell|spell(?:\\s+out)?(?:\\s+the\\s+word)?)\\s+([a-zA-Z][a-zA-Z'-]{1,40})$",
        RegexOption.IGNORE_CASE
    )

    private val tipCalcRegex = Regex(
        "^(?:(?:what(?:'s|s|\\s+is)|calculate)\\s+)?(?:a\\s+)?(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+tip\\s+(?:on|for)\\s+\\$?(\\d+(?:\\.\\d+)?)$",
        RegexOption.IGNORE_CASE
    )

    private val splitBillRegex = Regex(
        "^split\\s+(?:a\\s+)?\\$?(\\d+(?:\\.\\d+)?)\\s+(?:bill\\s+)?(?:between|among|by|into)\\s+(\\d+)(?:\\s+people|\\s+ways)?$",
        RegexOption.IGNORE_CASE
    )

    private val percentOfRegex = Regex(
        "^(?:(?:what(?:'s|s|\\s+is)|how\\s+much\\s+is|calculate|compute)\\s+)?(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)\\s+of\\s+\\$?(\\d+(?:\\.\\d+)?)$",
        RegexOption.IGNORE_CASE
    )

    private val sqrtRegex = Regex(
        "^(?:(?:what(?:'s|s|\\s+is)|calculate)\\s+)?(?:the\\s+)?square\\s+root\\s+of\\s+(\\d+(?:\\.\\d+)?)$",
        RegexOption.IGNORE_CASE
    )

    private val unitConversionRegex = Regex(
        "^(?:(?:convert|what(?:'s|s|\\s+is)|how\\s+(?:much|many)\\s+(?:is|are))\\s+)?(-?\\d+(?:\\.\\d+)?)\\s*°?\\s*([a-zA-Z°/ ]{1,22}?)\\s+(?:to|in|into)\\s+°?([a-zA-Z°/ ]{1,22})$",
        RegexOption.IGNORE_CASE
    )

    private val howManyInOneUnitRegex = Regex(
        "^how\\s+many\\s+([a-zA-Z°/ ]{1,22}?)\\s+(?:are\\s+)?(?:in|make|equals?)\\s+(?:a|an|1|one)\\s+([a-zA-Z°/ ]{1,22})$",
        RegexOption.IGNORE_CASE
    )

    private val mathExpressionRegex = Regex(
        "^(?:(?:what(?:'s|s|\\s+is)|how\\s+much\\s+is|calculate|compute|solve|evaluate)\\s+)?([-+]?\\d+(?:\\.\\d+)?(?:\\s*(?:\\+|-|\\*|x|×|/|÷|\\^|plus|minus|times|multiplied\\s+by|divided\\s+by|over|to\\s+the\\s+power\\s+of)\\s*[-+]?\\d+(?:\\.\\d+)?)+)$",
        RegexOption.IGNORE_CASE
    )

    private val greetingRegex = Regex(
        "^(?:hi|hello|hey|hey\\s+there|hi\\s+nova|hello\\s+nova|good\\s+afternoon|good\\s+evening|yo|sup|what's\\s+up|whats\\s+up)$",
        RegexOption.IGNORE_CASE
    )

    private val thanksRegex = Regex(
        "^(?:thanks|thank\\s+you|thank\\s+you\\s+nova|thanks\\s+nova|appreciate\\s+it|you're\\s+awesome|good\\s+job|nice\\s+job)$",
        RegexOption.IGNORE_CASE
    )

    private val identityRegex = Regex(
        "^(?:who\\s+are\\s+you|what(?:'s|s|\\s+is)\\s+your\\s+name|introduce\\s+yourself)$",
        RegexOption.IGNORE_CASE
    )

    private val capabilitiesRegex = Regex(
        "^(?:what\\s+can\\s+you\\s+do|help|how\\s+can\\s+you\\s+help(?:\\s+me)?|what\\s+are\\s+your\\s+(?:features|capabilities|skills))$",
        RegexOption.IGNORE_CASE
    )

    private val selfStatusRegex = Regex(
        "^(?:(?:what(?:'s|s|\\s+is)|how(?:'s|s|\\s+is)|check|tell\\s+me|give\\s+me|report)\\s+)?(?:your\\s+)?(?:current\\s+)?status(?:\\s+report)?$|" +
            "^(?:check\\s+)?(?:device|phone|system|nova)\\s+(?:current\\s+)?status$|" +
            "^how\\s+are\\s+you\\s+(?:doing|feeling)\\s+(?:right\\s+now|currently)$",
        RegexOption.IGNORE_CASE
    )

    private val sensorStatusRegex = Regex(
        "^(?:check|read|list|show|what\\s+are)\\s+(?:all\\s+)?(?:your\\s+|the\\s+|device\\s+|phone\\s+)?sensors(?:\\s+(?:do\\s+you\\s+have|right\\s+now|currently))?$|" +
            "^what\\s+do\\s+(?:your\\s+)?sensors\\s+(?:say|read|show)$|" +
            "^sensor\\s+(?:status|report|readings?)$",
        RegexOption.IGNORE_CASE
    )

    private val thermalSensorRegex = Regex(
        "^(?:how\\s+(?:hot|warm|cool|cold)\\s+(?:are\\s+you|is\\s+(?:the\\s+|my\\s+)?phone)|what(?:'s|s|\\s+is)\\s+(?:your|the\\s+(?:phone|device|battery))\\s+temperature|are\\s+you\\s+(?:hot|warm|overheating))$",
        RegexOption.IGNORE_CASE
    )

    private val lightSensorRegex = Regex(
        "^(?:how\\s+bright\\s+is\\s+it(?:\\s+(?:in\\s+here|right\\s+now))?|check\\s+(?:the\\s+)?(?:ambient\\s+)?light\\s+sensor|what(?:'s|s|\\s+is)\\s+the\\s+(?:ambient\\s+)?light(?:\\s+level)?)$",
        RegexOption.IGNORE_CASE
    )

    private val motionSensorRegex = Regex(
        "^(?:am\\s+i|are\\s+you)\\s+(?:moving|still)|is\\s+(?:the\\s+)?phone\\s+(?:moving|still)|(?:check\\s+(?:the\\s+)?)?(?:motion|accelerometer|gyroscope)\\s+(?:sensor|status|reading)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Checks whether a query is a deterministic Alexa-style everyday utility that should
     * resolve instantly on Tier 1 without hitting Wikipedia trivia or stalling on an LLM.
     */
    fun matchFastUtility(clean: String): RegexFastPathEngine.FastPathMatch? {
        worldTimeRegex.find(clean)?.let { m ->
            val city = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            if (city.isNotBlank()) {
                return utilityMatch("world_time", city, "Checked time in $city")
            }
        }

        currentTimeRegex.find(clean)?.let {
            return utilityMatch("time_date", "", "Checked live time & date")
        }

        currentDateRegex.find(clean)?.let {
            return utilityMatch("date", "", "Checked today's date")
        }

        currentDayRegex.find(clean)?.let {
            return utilityMatch("day", "", "Checked day of the week")
        }

        relativeDayRegex.find(clean)?.let { m ->
            val target = m.groupValues[1].ifBlank {
                m.groupValues[2].ifBlank { m.groupValues[3] }
            }.lowercase()
            return utilityMatch(target, "", "Checked calendar $target")
        }

        daysUntilRegex.find(clean)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotEmpty()) {
                return utilityMatch("countdown", target, "Calculated days until $target")
            }
        }

        weatherRegex.find(clean)?.let { m ->
            val city = m.groupValues[1].ifBlank { m.groupValues[2].ifBlank { m.groupValues[3] } }.trim()
            val summary = if (city.isNotBlank()) "Checked weather in $city" else "Checked local weather"
            return utilityMatch("weather", city, summary)
        }

        coinFlipRegex.find(clean)?.let {
            return utilityMatch("coin", "", "Flipped a coin")
        }

        diceRollRegex.find(clean)?.let {
            return utilityMatch("dice", clean, "Rolled dice")
        }

        randomNumberRegex.find(clean)?.let { m ->
            val lo = m.groupValues[1].ifBlank { m.groupValues[3] }
            val hi = m.groupValues[2].ifBlank { m.groupValues[4] }
            val rangeSpec = if (lo.isNotBlank() && hi.isNotBlank()) "$lo:$hi" else "1:100"
            return utilityMatch("random_number", rangeSpec, "Picked a random number")
        }

        rpsRegex.find(clean)?.let {
            return utilityMatch("rps", "", "Played Rock Paper Scissors")
        }

        spellWordRegex.find(clean)?.let { m ->
            val word = m.groupValues[1].trim()
            return utilityMatch("spell", word, "Spelled '$word'")
        }

        tipCalcRegex.find(clean)?.let { m ->
            return utilityMatch("tip", "${m.groupValues[1]}:${m.groupValues[2]}", "Calculated tip")
        }

        splitBillRegex.find(clean)?.let { m ->
            return utilityMatch("split_bill", "${m.groupValues[1]}:${m.groupValues[2]}", "Split the bill")
        }

        percentOfRegex.find(clean)?.let { m ->
            return utilityMatch("percent_of", "${m.groupValues[1]}:${m.groupValues[2]}", "Calculated percentage")
        }

        sqrtRegex.find(clean)?.let { m ->
            return utilityMatch("sqrt", m.groupValues[1], "Calculated square root")
        }

        unitConversionRegex.find(clean)?.let { m ->
            val amount = m.groupValues[1].toDoubleOrNull()
            val pair = resolveUnitPair(m.groupValues[2], m.groupValues[3])
            if (amount != null && pair != null) {
                return utilityMatch("convert", "$amount|${pair.first}|${pair.second}", "Converted units")
            }
        }

        howManyInOneUnitRegex.find(clean)?.let { m ->
            val pair = resolveUnitPair(m.groupValues[2], m.groupValues[1])
            if (pair != null) {
                return utilityMatch("convert", "1.0|${pair.first}|${pair.second}", "Converted units")
            }
        }

        mathExpressionRegex.find(clean)?.let { m ->
            val expr = m.groupValues[1].trim()
            if (evaluateMathExpression(expr) != null) {
                return utilityMatch("math", expr, "Calculated math expression")
            }
        }

        greetingRegex.find(clean)?.let {
            return utilityMatch("greeting", clean, "Greeted user")
        }

        thanksRegex.find(clean)?.let {
            return utilityMatch("thanks", clean, "Acknowledged thanks")
        }

        identityRegex.find(clean)?.let {
            return utilityMatch("identity", clean, "Introduced Nova")
        }

        capabilitiesRegex.find(clean)?.let {
            return utilityMatch("capabilities", clean, "Listed Nova capabilities")
        }

        selfStatusRegex.find(clean)?.let {
            return utilityMatch("self_status", "", "Checked live device & sensor status")
        }

        sensorStatusRegex.find(clean)?.let {
            return utilityMatch("sensors", "", "Read live device sensors")
        }

        thermalSensorRegex.find(clean)?.let {
            return utilityMatch("thermal_sensor", "", "Checked thermal & battery temperature sensors")
        }

        lightSensorRegex.find(clean)?.let {
            return utilityMatch("light_sensor", "", "Checked ambient light sensor")
        }

        motionSensorRegex.find(clean)?.let {
            return utilityMatch("motion_sensor", "", "Checked motion & accelerometer sensors")
        }

        return null
    }

    private fun utilityMatch(
        topic: String,
        query: String,
        summary: String
    ): RegexFastPathEngine.FastPathMatch {
        val args = if (query.isNotEmpty()) {
            mapOf("topic" to topic, "query" to query.take(120))
        } else {
            mapOf("topic" to topic)
        }
        return RegexFastPathEngine.FastPathMatch(
            skillId = "alexa-utility-briefings",
            toolCall = ToolCallRequest(
                toolName = "get_device_briefing",
                args = args,
                safetyTier = SafetyTier.GREEN
            ),
            summary = summary
        )
    }

    /**
     * Executes an Alexa utility topic. Returns `null` if the topic should fall through
     * to the standard briefing handler (`morning`, `evening`, `battery`, `connectivity`, etc.).
     */
    suspend fun executeUtility(topic: String, query: String = "", context: Context? = null): String? {
        val nowMs = nowMillis()
        val now = Date(nowMs)
        return when (topic.lowercase()) {
            "time_date" -> {
                val timeOnly = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
                val dayDate = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
                "It's $timeOnly on $dayDate."
            }
            "date" -> {
                val dateFull = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(now)
                "Today is $dateFull."
            }
            "day" -> {
                val dayName = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
                "Today is $dayName."
            }
            "tomorrow" -> {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                val tomorrowStr = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(cal.time)
                "Tomorrow is $tomorrowStr."
            }
            "yesterday" -> {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    add(Calendar.DAY_OF_YEAR, -1)
                }
                val yestStr = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(cal.time)
                "Yesterday was $yestStr."
            }
            "year" -> {
                val yr = SimpleDateFormat("yyyy", Locale.getDefault()).format(now)
                "It is $yr."
            }
            "month" -> {
                val mo = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now)
                "It is $mo."
            }
            "countdown" -> computeDaysUntil(query, nowMs)
            "world_time" -> computeWorldTime(query, nowMs)
            "weather" -> fetchLiveWeather(query, context)
            "coin" -> {
                val side = if (Random.nextBoolean()) "Heads" else "Tails"
                "I flipped a coin — it landed on $side!"
            }
            "dice" -> rollDice(query)
            "random_number" -> pickRandomNumber(query)
            "rps" -> {
                val pick = listOf("Rock", "Paper", "Scissors").random()
                "Rock, paper, scissors — I choose $pick!"
            }
            "spell" -> {
                val word = query.trim()
                val letters = word.uppercase(Locale.getDefault()).toCharArray().joinToString(" - ")
                "$word is spelled: $letters."
            }
            "tip" -> {
                val parts = query.split(":")
                val pct = parts.getOrNull(0)?.toDoubleOrNull() ?: 15.0
                val bill = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val tip = bill * (pct / 100.0)
                val total = bill + tip
                "A ${fmt(pct)}% tip on ${fmtMoney(bill)} is ${fmtMoney(tip)}, bringing the total to ${fmtMoney(total)}."
            }
            "split_bill" -> {
                val parts = query.split(":")
                val total = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val ways = parts.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val each = total / ways
                "Splitting ${fmtMoney(total)} across $ways comes out to ${fmtMoney(each)} each."
            }
            "percent_of" -> {
                val parts = query.split(":")
                val pct = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val base = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val res = base * (pct / 100.0)
                "${fmt(pct)}% of ${fmt(base)} is ${fmt(res)}."
            }
            "sqrt" -> {
                val num = query.toDoubleOrNull() ?: 0.0
                "The square root of ${fmt(num)} is ${fmt(sqrt(num))}."
            }
            "math" -> {
                val res = evaluateMathExpression(query)
                if (res != null) "${cleanSpokenExpr(query)} equals ${fmt(res)}." else "I couldn't calculate '$query'."
            }
            "convert" -> executeConversion(query)
            "greeting" -> {
                val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
                when {
                    hour < 12 -> "Good morning! How can I help you today?"
                    hour < 18 -> "Good afternoon! What can I do for you?"
                    else -> "Good evening! How can I help?"
                }
            }
            "thanks" -> "You're very welcome! Let me know anytime you need anything else."
            "identity" -> "I'm Nova, your hands-free on-device Android assistant."
            "capabilities" ->
                "I can tell the time, date, world clocks, and live weather, do quick math and unit conversions, set timers, alarms, and reminders, manage your shopping and to-do lists, play music, read your notifications, control phone settings, run routines, and answer questions."
            else -> null
        }
    }

    // ── World Clock ─────────────────────────────────────────────────────────────────────

    private val cityTimeZones: Map<String, Pair<String, String>> = mapOf(
        "manila" to ("Manila" to "Asia/Manila"),
        "philippines" to ("Manila" to "Asia/Manila"),
        "cebu" to ("Cebu" to "Asia/Manila"),
        "davao" to ("Davao" to "Asia/Manila"),
        "tokyo" to ("Tokyo" to "Asia/Tokyo"),
        "japan" to ("Tokyo" to "Asia/Tokyo"),
        "seoul" to ("Seoul" to "Asia/Seoul"),
        "korea" to ("Seoul" to "Asia/Seoul"),
        "singapore" to ("Singapore" to "Asia/Singapore"),
        "hong kong" to ("Hong Kong" to "Asia/Hong_Kong"),
        "taipei" to ("Taipei" to "Asia/Taipei"),
        "bangkok" to ("Bangkok" to "Asia/Bangkok"),
        "jakarta" to ("Jakarta" to "Asia/Jakarta"),
        "kuala lumpur" to ("Kuala Lumpur" to "Asia/Kuala_Lumpur"),
        "beijing" to ("Beijing" to "Asia/Shanghai"),
        "shanghai" to ("Shanghai" to "Asia/Shanghai"),
        "china" to ("Beijing" to "Asia/Shanghai"),
        "mumbai" to ("Mumbai" to "Asia/Kolkata"),
        "delhi" to ("New Delhi" to "Asia/Kolkata"),
        "india" to ("India" to "Asia/Kolkata"),
        "dubai" to ("Dubai" to "Asia/Dubai"),
        "riyadh" to ("Riyadh" to "Asia/Riyadh"),
        "sydney" to ("Sydney" to "Australia/Sydney"),
        "melbourne" to ("Melbourne" to "Australia/Melbourne"),
        "auckland" to ("Auckland" to "Pacific/Auckland"),
        "london" to ("London" to "Europe/London"),
        "uk" to ("London" to "Europe/London"),
        "paris" to ("Paris" to "Europe/Paris"),
        "france" to ("Paris" to "Europe/Paris"),
        "berlin" to ("Berlin" to "Europe/Berlin"),
        "germany" to ("Berlin" to "Europe/Berlin"),
        "madrid" to ("Madrid" to "Europe/Madrid"),
        "rome" to ("Rome" to "Europe/Rome"),
        "amsterdam" to ("Amsterdam" to "Europe/Amsterdam"),
        "zurich" to ("Zurich" to "Europe/Zurich"),
        "moscow" to ("Moscow" to "Europe/Moscow"),
        "istanbul" to ("Istanbul" to "Europe/Istanbul"),
        "cairo" to ("Cairo" to "Africa/Cairo"),
        "johannesburg" to ("Johannesburg" to "Africa/Johannesburg"),
        "new york" to ("New York" to "America/New_York"),
        "nyc" to ("New York" to "America/New_York"),
        "boston" to ("Boston" to "America/New_York"),
        "miami" to ("Miami" to "America/New_York"),
        "toronto" to ("Toronto" to "America/Toronto"),
        "chicago" to ("Chicago" to "America/Chicago"),
        "dallas" to ("Dallas" to "America/Chicago"),
        "denver" to ("Denver" to "America/Denver"),
        "phoenix" to ("Phoenix" to "America/Phoenix"),
        "los angeles" to ("Los Angeles" to "America/Los_Angeles"),
        "la" to ("Los Angeles" to "America/Los_Angeles"),
        "san francisco" to ("San Francisco" to "America/Los_Angeles"),
        "seattle" to ("Seattle" to "America/Los_Angeles"),
        "vancouver" to ("Vancouver" to "America/Vancouver"),
        "honolulu" to ("Honolulu" to "Pacific/Honolulu"),
        "hawaii" to ("Hawaii" to "Pacific/Honolulu"),
        "mexico city" to ("Mexico City" to "America/Mexico_City"),
        "sao paulo" to ("São Paulo" to "America/Sao_Paulo"),
        "buenos aires" to ("Buenos Aires" to "America/Argentina/Buenos_Aires"),
        "utc" to ("UTC" to "UTC"),
        "gmt" to ("GMT" to "GMT")
    )

    private fun computeWorldTime(rawCity: String, nowMs: Long): String {
        val key = rawCity.trim().lowercase().replace(Regex("^the\\s+"), "")
        val direct = cityTimeZones[key]
            ?: cityTimeZones.entries.firstOrNull { (k, _) ->
                k.length >= 4 && (key.contains(k) || (key.length >= 4 && k.contains(key)))
            }?.value
        if (direct != null) {
            val tz = TimeZone.getTimeZone(direct.second)
            val fmtTime = SimpleDateFormat("h:mm a, EEEE", Locale.getDefault()).apply { timeZone = tz }
            return "It's currently ${fmtTime.format(Date(nowMs))} in ${direct.first}."
        }
        // Fallback: match against JVM IANA timezone IDs (e.g., "Asia/Bangkok", "America/Lima")
        val normalizedToken = key.replace(' ', '_')
        val matchedId = TimeZone.getAvailableIDs().firstOrNull { id ->
            id.substringAfterLast('/').equals(normalizedToken, ignoreCase = true)
        }
        if (matchedId != null) {
            val tz = TimeZone.getTimeZone(matchedId)
            val label = matchedId.substringAfterLast('/').replace('_', ' ')
            val fmtTime = SimpleDateFormat("h:mm a, EEEE", Locale.getDefault()).apply { timeZone = tz }
            return "It's currently ${fmtTime.format(Date(nowMs))} in $label."
        }
        return "I don't have the timezone for '$rawCity' stored locally."
    }

    // ── Holiday & Date Countdowns ───────────────────────────────────────────────────────

    private fun computeDaysUntil(rawTarget: String, nowMs: Long): String {
        val lower = rawTarget.trim().lowercase()
        val today = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val (label, month, day) = when {
            lower.contains("christmas") || lower.contains("xmas") -> Triple("Christmas", Calendar.DECEMBER, 25)
            lower.contains("new year") -> Triple("New Year's Day", Calendar.JANUARY, 1)
            lower.contains("valentine") -> Triple("Valentine's Day", Calendar.FEBRUARY, 14)
            lower.contains("halloween") -> Triple("Halloween", Calendar.OCTOBER, 31)
            lower.contains("independence") && lower.contains("us") -> Triple("Independence Day", Calendar.JULY, 4)
            lower.contains("weekend") || lower.contains("saturday") -> {
                val dow = today.get(Calendar.DAY_OF_WEEK)
                val diff = (Calendar.SATURDAY - dow + 7) % 7
                return when (diff) {
                    0 -> "It's already the weekend!"
                    1 -> "There is 1 day until the weekend."
                    else -> "There are $diff days until the weekend."
                }
            }
            else -> return "Tell me a holiday like Christmas, New Year's Day, Valentine's Day, or Halloween to count down to."
        }
        val target = (today.clone() as Calendar).apply {
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            if (before(today)) add(Calendar.YEAR, 1)
        }
        val diffDays = ((target.timeInMillis - today.timeInMillis) / (24L * 3600_000L)).toInt()
        return when (diffDays) {
            0 -> "Today is $label!"
            1 -> "There is 1 day until $label."
            else -> "There are $diffDays days until $label."
        }
    }

    // ── Live Weather (Open-Meteo + wttr.in, Zero API Key) ───────────────────────────────

    private suspend fun fetchLiveWeather(city: String, context: Context?): String {
        testWeatherOverride?.let { override ->
            override(city)?.let { return it }
        }
        if (!WebKnowledgeResolver.isInternetAvailable(context)) {
            return "You're offline right now, so I can't fetch live weather. Try again once you're connected to the internet."
        }
        if (android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) {
            return "Live weather requires an internet connection on a real device."
        }
        return withContext(Dispatchers.IO) {
            if (city.isNotBlank()) {
                fetchOpenMeteoWeatherForCity(city)
                    ?: fetchWttrWeather(city)
                    ?: "I couldn't fetch live weather for $city right now. Please try again in a moment."
            } else {
                fetchWttrWeather("")
                    ?: "I couldn't fetch your local weather right now. Try asking with a city name, like 'what's the weather in Manila'."
            }
        }
    }

    private fun fetchOpenMeteoWeatherForCity(city: String): String? {
        val encoded = runCatching { URLEncoder.encode(city.trim(), "UTF-8") }.getOrNull() ?: return null
        val geoJson = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json")
            ?: return null
        return runCatching {
            val first = JSONObject(geoJson).optJSONArray("results")?.optJSONObject(0) ?: return null
            val lat = first.getDouble("latitude")
            val lon = first.getDouble("longitude")
            val resolvedName = first.optString("name", city).ifBlank { city }
            val weatherJson = httpGet(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,weather_code,relative_humidity_2m" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=1&timezone=auto"
            ) ?: return null
            val root = JSONObject(weatherJson)
            val current = root.optJSONObject("current") ?: return null
            val tempC = current.optDouble("temperature_2m", Double.NaN)
            val feelsC = current.optDouble("apparent_temperature", Double.NaN)
            val wmoCode = current.optInt("weather_code", -1)
            if (tempC.isNaN()) return null
            val condition = describeWmoCode(wmoCode)
            val daily = root.optJSONObject("daily")
            val hiC = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
            val loC = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
            val rainPct = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1

            buildString {
                append("In $resolvedName, it's currently ${tempC.roundToInt()}°C and $condition")
                if (!feelsC.isNaN() && abs(feelsC - tempC) >= 2.0) {
                    append(", feeling like ${feelsC.roundToInt()}°C")
                }
                append(".")
                if (!hiC.isNaN() && !loC.isNaN()) {
                    append(" Today's high is ${hiC.roundToInt()}°C with a low of ${loC.roundToInt()}°C")
                    if (rainPct >= 25) append(" and a $rainPct% chance of rain")
                    append(".")
                }
            }
        }.getOrNull()
    }

    private fun fetchWttrWeather(city: String): String? {
        val path = if (city.isBlank()) "" else (runCatching { URLEncoder.encode(city.trim(), "UTF-8") }.getOrNull() ?: "")
        val jsonStr = httpGet("https://wttr.in/$path?format=j1") ?: return null
        return runCatching {
            val root = JSONObject(jsonStr)
            val current = root.optJSONArray("current_condition")?.optJSONObject(0) ?: return null
            val tempC = current.optString("temp_C").trim()
            val feelsC = current.optString("FeelsLikeC").trim()
            val desc = current.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value").orEmpty().trim()
            val area = root.optJSONArray("nearest_area")?.optJSONObject(0)
                ?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value").orEmpty().trim()
                .ifBlank { city.ifBlank { "your area" } }
            val today = root.optJSONArray("weather")?.optJSONObject(0)
            val maxC = today?.optString("maxtempC").orEmpty().trim()
            val minC = today?.optString("mintempC").orEmpty().trim()
            if (tempC.isEmpty()) return null
            buildString {
                append("In $area, it's currently $tempC°C")
                if (desc.isNotEmpty()) append(" and ${desc.lowercase()}")
                if (feelsC.isNotEmpty() && feelsC != tempC) append(", feeling like $feelsC°C")
                append(".")
                if (maxC.isNotEmpty() && minC.isNotEmpty()) {
                    append(" Today's high is $maxC°C and low is $minC°C.")
                }
            }
        }.getOrNull()
    }

    private fun describeWmoCode(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzling"
        61, 63, 65 -> "raining"
        66, 67 -> "freezing rain"
        71, 73, 75, 77 -> "snowing"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorms"
        else -> "mild"
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return runCatching {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 4500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NovaAssistant/1.0")
                setRequestProperty("Accept", "application/json")
            }
            if (conn!!.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn!!.inputStream)).use { it.readText() }
        }.getOrNull().also {
            runCatching { conn?.disconnect() }
        }
    }

    // ── Dice & Random Utilities ─────────────────────────────────────────────────────────

    private fun rollDice(raw: String): String {
        val m = diceRollRegex.find(raw.trim())
        val countToken = m?.groupValues?.getOrNull(1).orEmpty().lowercase()
        val count = when (countToken) {
            "", "a", "an", "one" -> 1
            "two" -> 2
            "three" -> 3
            "four" -> 4
            "five" -> 5
            "six" -> 6
            else -> countToken.toIntOrNull()?.coerceIn(1, 10) ?: 1
        }
        val sides = m?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: m?.groupValues?.getOrNull(3)?.toIntOrNull()
            ?: 6
        val safeSides = sides.coerceIn(2, 100)
        val rolls = List(count) { Random.nextInt(1, safeSides + 1) }
        return if (count == 1) {
            "I rolled a $safeSides-sided die and got ${rolls.first()}."
        } else {
            "I rolled $count dice and got ${rolls.joinToString(", ")}, for a total of ${rolls.sum()}."
        }
    }

    private fun pickRandomNumber(rangeSpec: String): String {
        val parts = rangeSpec.split(":")
        val a = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val b = parts.getOrNull(1)?.toIntOrNull() ?: 100
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        val picked = Random.nextInt(lo, hi + 1)
        return "Your random number between $lo and $hi is $picked."
    }

    // ── Arithmetic Evaluator ────────────────────────────────────────────────────────────

    fun evaluateMathExpression(raw: String): Double? {
        val normalized = raw.lowercase()
            .replace("multiplied by", "*")
            .replace("divided by", "/")
            .replace("to the power of", "^")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("times", "*")
            .replace("over", "/")
            .replace("×", "*")
            .replace("÷", "/")
            .replace(Regex("(?<=\\d|\\s)x(?=\\s|\\d)"), "*")
            .replace(Regex("\\s+"), "")
        if (normalized.isEmpty() || !normalized.any { it.isDigit() }) return null
        if (!Regex("^[0-9.+\\-*/^()]+$").matches(normalized)) return null

        var pos = 0
        fun peek(): Char? = normalized.getOrNull(pos)
        fun consume(): Char = normalized[pos++]

        lateinit var parseExpr: () -> Double
        lateinit var parseFactor: () -> Double

        fun parsePower(): Double {
            var base = parseFactor()
            if (peek() == '^') {
                consume()
                val exp = parsePower()
                base = base.pow(exp)
            }
            return base
        }

        fun parseTerm(): Double {
            var left = parsePower()
            while (peek() == '*' || peek() == '/') {
                val op = consume()
                val right = parsePower()
                left = if (op == '*') left * right else {
                    if (right == 0.0) return Double.NaN
                    left / right
                }
            }
            return left
        }

        parseExpr = {
            var left = parseTerm()
            while (peek() == '+' || peek() == '-') {
                val op = consume()
                val right = parseTerm()
                left = if (op == '+') left + right else left - right
            }
            left
        }

        parseFactor = {
            when (peek()) {
                '+' -> {
                    consume()
                    parseFactor()
                }
                '-' -> {
                    consume()
                    -parseFactor()
                }
                '(' -> {
                    consume()
                    val v = parseExpr()
                    if (peek() == ')') consume()
                    v
                }
                else -> {
                    val start = pos
                    while (peek()?.let { it.isDigit() || it == '.' } == true) consume()
                    if (start == pos) Double.NaN else normalized.substring(start, pos).toDoubleOrNull() ?: Double.NaN
                }
            }
        }

        return runCatching {
            val result = parseExpr()
            if (pos == normalized.length && !result.isNaN() && !result.isInfinite()) result else null
        }.getOrNull()
    }

    private fun cleanSpokenExpr(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")

    // ── Unit Conversions ────────────────────────────────────────────────────────────────

    private enum class UnitCategory { TEMP, LENGTH, WEIGHT, VOLUME, SPEED, DATA }

    private data class UnitDef(
        val key: String,
        val displayPlural: String,
        val category: UnitCategory,
        val toBaseFactor: Double // ignored for TEMP
    )

    private val unitDefs = mapOf(
        // Temperature
        "c" to UnitDef("c", "°C", UnitCategory.TEMP, 1.0),
        "f" to UnitDef("f", "°F", UnitCategory.TEMP, 1.0),
        "k" to UnitDef("k", "K", UnitCategory.TEMP, 1.0),
        // Length (base: meters)
        "mm" to UnitDef("mm", "millimeters", UnitCategory.LENGTH, 0.001),
        "cm" to UnitDef("cm", "centimeters", UnitCategory.LENGTH, 0.01),
        "m" to UnitDef("m", "meters", UnitCategory.LENGTH, 1.0),
        "km" to UnitDef("km", "kilometers", UnitCategory.LENGTH, 1000.0),
        "in" to UnitDef("in", "inches", UnitCategory.LENGTH, 0.0254),
        "ft" to UnitDef("ft", "feet", UnitCategory.LENGTH, 0.3048),
        "yd" to UnitDef("yd", "yards", UnitCategory.LENGTH, 0.9144),
        "mi" to UnitDef("mi", "miles", UnitCategory.LENGTH, 1609.344),
        // Weight (base: grams)
        "g" to UnitDef("g", "grams", UnitCategory.WEIGHT, 1.0),
        "kg" to UnitDef("kg", "kilograms", UnitCategory.WEIGHT, 1000.0),
        "oz" to UnitDef("oz", "ounces", UnitCategory.WEIGHT, 28.349523125),
        "lb" to UnitDef("lb", "pounds", UnitCategory.WEIGHT, 453.59237),
        // Volume (base: milliliters)
        "ml" to UnitDef("ml", "milliliters", UnitCategory.VOLUME, 1.0),
        "l" to UnitDef("l", "liters", UnitCategory.VOLUME, 1000.0),
        "tsp" to UnitDef("tsp", "teaspoons", UnitCategory.VOLUME, 4.92892),
        "tbsp" to UnitDef("tbsp", "tablespoons", UnitCategory.VOLUME, 14.7868),
        "floz" to UnitDef("floz", "fluid ounces", UnitCategory.VOLUME, 29.5735),
        "cup" to UnitDef("cup", "cups", UnitCategory.VOLUME, 236.588),
        "gal" to UnitDef("gal", "gallons", UnitCategory.VOLUME, 3785.41),
        // Speed (base: km/h)
        "kmh" to UnitDef("kmh", "km/h", UnitCategory.SPEED, 1.0),
        "mph" to UnitDef("mph", "mph", UnitCategory.SPEED, 1.609344),
        // Data (base: MB)
        "kb" to UnitDef("kb", "KB", UnitCategory.DATA, 1.0 / 1024.0),
        "mb" to UnitDef("mb", "MB", UnitCategory.DATA, 1.0),
        "gb" to UnitDef("gb", "GB", UnitCategory.DATA, 1024.0),
        "tb" to UnitDef("tb", "TB", UnitCategory.DATA, 1024.0 * 1024.0)
    )

    private fun normalizeUnit(raw: String): String? {
        val u = raw.trim().lowercase().replace("°", "").replace(Regex("\\s+"), " ")
        return when (u) {
            "c", "celsius", "centigrade", "degrees c", "degree c", "degrees celsius" -> "c"
            "f", "fahrenheit", "degrees f", "degree f", "degrees fahrenheit" -> "f"
            "k", "kelvin" -> "k"
            "mm", "millimeter", "millimeters", "millimetre", "millimetres" -> "mm"
            "cm", "centimeter", "centimeters", "centimetre", "centimetres" -> "cm"
            "m", "meter", "meters", "metre", "metres" -> "m"
            "km", "kilometer", "kilometers", "kilometre", "kilometres", "kilo", "kilos" -> "km"
            "in", "inch", "inches" -> "in"
            "ft", "foot", "feet" -> "ft"
            "yd", "yard", "yards" -> "yd"
            "mi", "mile", "miles" -> "mi"
            "g", "gram", "grams" -> "g"
            "kg", "kilogram", "kilograms", "kgs" -> "kg"
            "oz", "ounce", "ounces" -> "oz"
            "lb", "lbs", "pound", "pounds" -> "lb"
            "ml", "milliliter", "milliliters", "millilitre", "millilitres" -> "ml"
            "l", "liter", "liters", "litre", "litres" -> "l"
            "tsp", "teaspoon", "teaspoons" -> "tsp"
            "tbsp", "tablespoon", "tablespoons" -> "tbsp"
            "fl oz", "floz", "fluid ounce", "fluid ounces" -> "floz"
            "cup", "cups" -> "cup"
            "gal", "gallon", "gallons" -> "gal"
            "kmh", "km/h", "kph", "kilometers per hour" -> "kmh"
            "mph", "miles per hour" -> "mph"
            "kb", "kilobyte", "kilobytes" -> "kb"
            "mb", "megabyte", "megabytes" -> "mb"
            "gb", "gigabyte", "gigabytes" -> "gb"
            "tb", "terabyte", "terabytes" -> "tb"
            else -> null
        }
    }

    private fun resolveUnitPair(rawFrom: String, rawTo: String): Pair<String, String>? {
        var from = normalizeUnit(rawFrom) ?: return null
        var to = normalizeUnit(rawTo) ?: return null
        // Everyday kitchen phrasing: "how many ounces in a cup" means fluid ounces
        if (from == "oz" && unitDefs[to]?.category == UnitCategory.VOLUME) from = "floz"
        if (to == "oz" && unitDefs[from]?.category == UnitCategory.VOLUME) to = "floz"
        return if (canConvert(from, to)) from to to else null
    }

    private fun canConvert(fromKey: String, toKey: String): Boolean {
        val a = unitDefs[fromKey] ?: return false
        val b = unitDefs[toKey] ?: return false
        return a.category == b.category && a.key != b.key
    }

    private fun executeConversion(spec: String): String {
        val parts = spec.split("|")
        val value = parts.getOrNull(0)?.toDoubleOrNull() ?: return "I couldn't convert that."
        val from = unitDefs[parts.getOrNull(1)] ?: return "I couldn't convert that."
        val to = unitDefs[parts.getOrNull(2)] ?: return "I couldn't convert that."
        val converted = if (from.category == UnitCategory.TEMP) {
            val celsius = when (from.key) {
                "c" -> value
                "f" -> (value - 32.0) * 5.0 / 9.0
                else -> value - 273.15
            }
            when (to.key) {
                "c" -> celsius
                "f" -> celsius * 9.0 / 5.0 + 32.0
                else -> celsius + 273.15
            }
        } else {
            (value * from.toBaseFactor) / to.toBaseFactor
        }
        val fromText = if (from.category == UnitCategory.TEMP) "${fmt(value)}${from.displayPlural}" else "${fmt(value)} ${from.displayPlural}"
        val toText = if (to.category == UnitCategory.TEMP) "${fmt(converted)}${to.displayPlural}" else "${fmt(converted)} ${to.displayPlural}"
        return "$fromText is $toText."
    }

    private fun fmt(v: Double): String = DecimalFormat("#.##").format(v)

    private fun fmtMoney(v: Double): String = String.format(Locale.US, "%.2f", v)
}
