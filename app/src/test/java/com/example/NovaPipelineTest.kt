package com.example

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.AgenticMemoryType
import com.example.data.AssistantPersonality
import com.example.data.ConditionType
import com.example.data.DeviceAccountHelper
import com.example.data.DeviceActionExecutor
import com.example.data.ElseBehavior
import com.example.data.LlmModelManager
import com.example.data.LocalFtsIndexHelper
import com.example.data.ModelSlot
import com.example.data.OrbVisualMode
import com.example.data.ProcessHealthState
import com.example.data.RecordedRoutine
import com.example.data.RecordedRoutineStore
import com.example.data.RoutineActionType
import com.example.data.RoutineCondition
import com.example.data.RoutineJsonCodec
import com.example.data.RoutineScope
import com.example.data.RoutineStep
import com.example.data.RoutingTier
import com.example.data.SafetyTier
import com.example.data.TierRedConfirmation
import com.example.data.TimeOfDayBucket
import com.example.data.ToolCallRequest
import com.example.data.TurnExecutionOutcome
import com.example.data.UiNodeSnapshot
import com.example.engine.AgenticMemoryEngine
import com.example.engine.AppCapabilityRegistry
import com.example.engine.AutonomousDialogResolver
import com.example.engine.ConditionEvalSnapshot
import com.example.engine.DeferredToolRegistry
import com.example.engine.InputNormalizer
import com.example.engine.MultiStageActionPlanner
import com.example.engine.NovaPipelineRouter
import com.example.engine.RandomSongCatalog
import com.example.engine.RegexFastPathEngine
import com.example.engine.RoutineExecutionEngine
import com.example.engine.SemanticLookupEngine
import com.example.engine.SkillCatalog
import android.app.Notification
import com.example.engine.ImportantNotificationFilter
import com.example.engine.ImportantNotificationFilter.ImportantNotificationType
import com.example.engine.ImportantNotificationFilter.RawNotificationSignals
import com.example.processB.LiteRtLmRuntimeWrapper
import com.example.processA.EmbeddingGemmaRuntime
import com.example.processA.LlmProcessBridge
import com.example.processA.NovaAccessibilityService
import com.example.processA.NovaVoiceForegroundService
import com.example.processA.RoutineRecorderManager
import com.example.ui.NovaViewModel
import com.example.ui.screens.NovaSystemDocumentation
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NovaPipelineTest {

    @Test
    fun `skills enforce max 3 parameters and smart home is disabled`() {
        assertEquals(18, SkillCatalog.allSkills.size)
        val qaSkill = SkillCatalog.findById("general-knowledge-qa")
        assertNotNull(qaSkill)
        assertTrue(qaSkill!!.enabled)
        assertTrue(qaSkill.tools.any { it.name == "answer_web_question" })
        val smartHome = SkillCatalog.findById("smart-home-controller")
        assertNotNull(smartHome)
        assertFalse(smartHome!!.enabled)

        val phoneSkill = SkillCatalog.findById("phone-calls-manager")
        assertNotNull(phoneSkill)
        assertTrue(phoneSkill!!.tools.any { it.name == "search_contacts" })

        val appsSkill = SkillCatalog.findById("app-knowledge-controller")
        assertNotNull(appsSkill)
        assertTrue(appsSkill!!.tools.any { it.name == "list_installed_apps" })
        assertTrue(appsSkill.tools.any { it.name == "get_app_skills" })

        val appMediaSkill = SkillCatalog.findById("app-media-streaming")
        assertNotNull(appMediaSkill)
        assertTrue(appMediaSkill!!.tools.any { it.name == "play_in_app" })

        val appSearchSkill = SkillCatalog.findById("app-search-and-action")
        assertNotNull(appSearchSkill)
        assertTrue(appSearchSkill!!.tools.any { it.name == "search_in_app" })
        assertTrue(appSearchSkill.tools.any { it.name == "navigate_in_maps" })

        val localScreenSkill = SkillCatalog.findById("local-screen-control")
        assertNotNull(localScreenSkill)
        assertTrue(localScreenSkill!!.tools.any { it.name == "screen_control_app" })
        assertTrue(localScreenSkill.tools.any { it.name == "ui_find_and_tap" })

        val alexaSkill = SkillCatalog.findById("alexa-utility-briefings")
        assertNotNull(alexaSkill)
        assertTrue(alexaSkill!!.tools.any { it.name == "manage_list_item" })

        val allTools = DeferredToolRegistry.getAllRegisteredTools()
        assertTrue(allTools.isNotEmpty())
        allTools.forEach { tool ->
            assertTrue(
                "Tool ${tool.name} has ${tool.parameters.size} parameters",
                tool.parameters.size <= 3
            )
        }
    }

    @Test
    fun `model manager presets include Gemma 4, Gemma 3n, LFM25, Qwen 25, and EmbeddingGemma 2`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LlmModelManager(context)
        val presetIds = modelManager.presets.map { it.id }
        assertTrue(presetIds.contains("gemma_4_e2b"))
        assertTrue(presetIds.contains("gemma_3n_e2b_int4"))
        assertTrue(presetIds.contains("lfm25_1_2b_int4_gpu"))
        assertTrue(presetIds.contains("qwen25_1_5b_q8"))
        assertTrue(presetIds.contains("lfm2_700m_int4"))
        assertTrue(presetIds.contains("embedding_gemma_2_270m"))
        assertEquals(ModelSlot.LLM, modelManager.llmPreset.slot)
        assertEquals(ModelSlot.EMBEDDING, modelManager.embeddingPreset.slot)
    }

    @Test
    fun `tier 0 normalizer strips self corrections and splits compound commands`() {
        val corrected = InputNormalizer.normalize("turn on wifi wait no turn on bluetooth")
        assertTrue(corrected.strippedCorrection)
        assertEquals("turn on bluetooth", corrected.cleanedFull.lowercase())

        val compound = InputNormalizer.normalize("turn on flashlight and set volume to 70%")
        assertEquals(2, compound.subCommands.size)
    }

    @Test
    fun `tier 1 regex fast path matches phone reflexes os controls and ignores disabled smart home`() {
        val torch = RegexFastPathEngine.match("turn on flashlight")
        assertNotNull(torch)
        assertEquals("set_hardware_state", torch?.toolCall?.toolName)

        val bt = RegexFastPathEngine.match("turn on bluetooth")
        assertNotNull(bt)
        assertEquals("bluetooth", bt?.toolCall?.args?.get("feature"))
        assertEquals("on", bt?.toolCall?.args?.get("state"))

        val timer = RegexFastPathEngine.match("set a timer for 25 minutes")
        assertNotNull(timer)
        assertEquals("1500", timer?.toolCall?.args?.get("duration_sec"))

        val addList = RegexFastPathEngine.match("add oat milk to my shopping list")
        assertNotNull(addList)
        assertEquals("manage_list_item", addList?.toolCall?.toolName)
        assertEquals("oat milk", addList?.toolCall?.args?.get("item"))

        val battery = RegexFastPathEngine.match("battery level")
        assertNotNull(battery)
        assertEquals("get_device_briefing", battery?.toolCall?.toolName)

        // OS-wide phone navigation controls
        val home = RegexFastPathEngine.match("go home")
        assertEquals("HOME", home?.toolCall?.args?.get("action"))
        val notifs = RegexFastPathEngine.match("open notifications")
        assertEquals("NOTIFICATIONS", notifs?.toolCall?.args?.get("action"))
        val qs = RegexFastPathEngine.match("open quick settings")
        assertEquals("QUICK_SETTINGS", qs?.toolCall?.args?.get("action"))
        val shot = RegexFastPathEngine.match("take a screenshot")
        assertEquals("SCREENSHOT", shot?.toolCall?.args?.get("action"))

        // Screen summarization routes to Gemma 4 vision/context rather than stopping at raw dump
        assertNull(RegexFastPathEngine.match("summarize this screen"))

        // Smart home controls must be disabled
        assertNull(RegexFastPathEngine.match("turn on bedroom lights"))
        assertNull(RegexFastPathEngine.match("set ac to 20 degrees"))
    }

    @Test
    fun `ui_act defaults to GREEN while risky buttons remain Tier Red`() {
        val benignClick = DeferredToolRegistry.validateToolCall(
            ToolCallRequest("ui_act", mapOf("action" to "CLICK", "node_id" to "n03"))
        )
        assertEquals(SafetyTier.GREEN, benignClick.safetyTier)

        val openGcash = DeferredToolRegistry.validateToolCall(
            ToolCallRequest("ui_open_and_scan", mapOf("app_name" to "GCash"))
        )
        assertEquals(SafetyTier.GREEN, openGcash.safetyTier)

        assertFalse(DeferredToolRegistry.isRedButtonLabel("Allow"))
        assertFalse(DeferredToolRegistry.isRedButtonLabel("Just once"))
        assertFalse(DeferredToolRegistry.isRedButtonLabel("user@example.com"))
        assertTrue(DeferredToolRegistry.isRedButtonLabel("Pay Now"))
        assertTrue(DeferredToolRegistry.isRedButtonLabel("Confirm Order"))
        assertTrue(DeferredToolRegistry.isRedButtonLabel("Delete Account"))
    }

    @Test
    fun `autonomous dialog resolver handles accounts dispatches permissions and blocks real payments`() {
        val accountNodes = listOf(
            UiNodeSnapshot("n01", "Choose an account", "TextView", "[0,0,100,40]", clickable = false),
            UiNodeSnapshot("n02", "alex.dev@gmail.com", "LinearLayout", "[0,50,400,120]", clickable = true)
        )
        val acctDecision = AutonomousDialogResolver.evaluateScreen(accountNodes, "com.google.android.gms", null)
        assertNotNull(acctDecision)
        assertEquals("n02", acctDecision?.targetNode?.id)
        assertEquals("alex.dev@gmail.com", acctDecision?.detectedAccount)

        val chooserNodes = listOf(
            UiNodeSnapshot("n01", "Open with", "TextView", "[0,0,100,40]", clickable = false),
            UiNodeSnapshot("n02", "Chrome", "LinearLayout", "[0,50,300,100]", clickable = true),
            UiNodeSnapshot("n03", "Just once", "Button", "[200,120,380,180]", clickable = true)
        )
        val dispatchDecision = AutonomousDialogResolver.evaluateScreen(chooserNodes, "android", null)
        assertNotNull(dispatchDecision)
        assertEquals("n03", dispatchDecision?.targetNode?.id)

        val permNodes = listOf(
            UiNodeSnapshot("n01", "Allow Nova to access location?", "TextView", "[0,0,300,60]", clickable = false),
            UiNodeSnapshot("n02", "While using the app", "Button", "[0,80,300,140]", clickable = true)
        )
        val permDecision = AutonomousDialogResolver.evaluateScreen(
            permNodes,
            "com.google.android.permissioncontroller",
            null
        )
        assertNotNull(permDecision)
        assertEquals("n02", permDecision?.targetNode?.id)

        val paymentNodes = listOf(
            UiNodeSnapshot("n01", "Review Order $49.99", "TextView", "[0,0,300,60]", clickable = false),
            UiNodeSnapshot("n02", "Pay Now", "Button", "[0,100,300,160]", clickable = true),
            UiNodeSnapshot("n03", "Continue", "Button", "[0,170,300,220]", clickable = true)
        )
        assertNull(AutonomousDialogResolver.evaluateScreen(paymentNodes, "com.shop.app", null))
    }

    @Test
    fun `device account helper starts empty without synthetic defaults and persists detected account`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = DeviceAccountHelper(context)
        helper.clearRecordedAccount()
        assertNull(helper.getRecordedAccount())

        val recorded = helper.recordAccount("Account: alex.member@gmail.com [Primary]")
        assertEquals("alex.member@gmail.com", recorded)
        assertEquals("alex.member@gmail.com", helper.getRecordedAccount())
    }

    @Test
    fun `multi choice local search formats plain non technical choices and resolves ordinal replies`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)
        fts.upsertDocument("doc_1", "notes", "Wifi Home", "Network Alpha 1234", "memory://wifi1")
        fts.upsertDocument("doc_2", "notes", "Wifi Office", "Network Beta 5678", "memory://wifi2")

        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val report = executor.execute(
            ToolCallRequest("local_search", mapOf("query" to "Wifi", "source" to "notes"))
        )
        assertEquals(2, report.ftsResults.size)
        assertTrue(report.summary.contains("First,"))
        assertTrue(report.summary.contains("Second,"))
        assertTrue(report.summary.contains("Which one would you like?"))
        assertFalse(report.summary.contains("FTS5", ignoreCase = true))
        assertFalse(report.summary.contains("EmbeddingGemma", ignoreCase = true))
        assertFalse(report.summary.contains("nodes", ignoreCase = true))

        val picked = NovaPipelineRouter.matchPendingChoice("the second one", report.ftsResults)
        assertNotNull(picked)
        assertEquals("doc_2", picked?.docId)
        fts.close()
    }

    @Test
    fun `llm output parser and sanitizer never leak raw json to the user`() {
        // Case 1: Empty reply with valid tool call must not fall back to raw JSON
        val toolOnlyJson = """{"calls":[{"tool":"local_search","args":{"query":"wifi","source":"notes"}}],"reply":""}"""
        val parsedToolOnly = com.example.processB.LiteRtLmRuntimeWrapper.parseModelTurnText(toolOnlyJson)
        assertNotNull(parsedToolOnly)
        assertEquals(1, parsedToolOnly!!.first.size)
        val cleanToolReply = com.example.processB.LiteRtLmRuntimeWrapper.sanitizeAssistantReply(
            parsedToolOnly.second,
            toolOnlyJson,
            hasToolCalls = true
        )
        assertEquals("", cleanToolReply)

        // Case 2: Markdown-fenced JSON + thought tags
        val fenced = "<thought>thinking</thought>\n```json\n{\"calls\":[],\"reply\":\"It is 72 degrees outside.\"}\n```"
        val parsedFenced = com.example.processB.LiteRtLmRuntimeWrapper.parseModelTurnText(fenced)
        assertNotNull(parsedFenced)
        assertEquals("It is 72 degrees outside.", parsedFenced!!.second)

        // Case 3: Broken/truncated JSON still recovers reply and never surfaces braces
        val brokenJson = """{"calls":[{"tool":"nonexistent","args":{"x":1}}],"reply":"Here are your contacts."""
        val recovered = com.example.processB.LiteRtLmRuntimeWrapper.parseModelTurnText(brokenJson)
        assertEquals("Here are your contacts.", recovered?.second)

        // Case 4: Unrecoverable malformed JSON falls back to friendly non-JSON text
        val gibberishJson = """{"calls":[{"tool":"unknown_tool"""
        val sanitizedGibberish = com.example.processB.LiteRtLmRuntimeWrapper.sanitizeAssistantReply(
            parsedReply = gibberishJson,
            rawFallback = gibberishJson,
            hasToolCalls = false
        )
        assertFalse(sanitizedGibberish.contains("{"))
        assertFalse(sanitizedGibberish.contains("\"calls\""))

        // Case 5: Pythonic call syntax & tool_code fences parse cleanly into validated tool calls
        val pythonicCall = "```tool_code\nplay_in_app(app_name='YouTube Music', query='song')\n```"
        val parsedPythonic = com.example.processB.LiteRtLmRuntimeWrapper.parseModelTurnText(pythonicCall)
        assertNotNull(parsedPythonic)
        assertEquals(1, parsedPythonic!!.first.size)
        assertEquals("play_in_app", parsedPythonic.first.first().toolName)
        assertEquals("YouTube Music", parsedPythonic.first.first().args["app_name"])

        // Case 6: Plain-English conversational output preserves text without triggering JSON error fallback
        val plainProse = "Sure, I can help you look up your schedule or open YouTube Music."
        val parsedProse = com.example.processB.LiteRtLmRuntimeWrapper.parseModelTurnText(plainProse)
        assertNotNull(parsedProse)
        assertTrue(parsedProse!!.first.isEmpty())
        assertEquals(plainProse, parsedProse.second)
    }

    @Test
    fun `app based skills resolve typos and fast path matches holistic app media and search commands`() {
        val sampleApps = listOf(
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "YouTube Music",
                packageName = "com.google.android.apps.youtube.music",
                categoryLabel = "Audio/Music",
                capabilities = listOf("launch", "media_search", "ui_screen_automation"),
                howToUse = "play_in_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Spotify",
                packageName = "com.spotify.music",
                categoryLabel = "Audio/Music",
                capabilities = listOf("launch", "media_search", "ui_screen_automation"),
                howToUse = "play_in_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Maps",
                packageName = "com.google.android.apps.maps",
                categoryLabel = "Maps/Navigation",
                capabilities = listOf("launch", "navigation", "ui_screen_automation"),
                howToUse = "navigate_in_maps"
            )
        )

        val typoMatch = AppCapabilityRegistry.findBestMatchFromProfiles(sampleApps, "youitube music")
        assertNotNull(typoMatch)
        assertEquals("YouTube Music", typoMatch?.appName)
        assertTrue(AppCapabilityRegistry.describeAppSkills(typoMatch!!).contains("play or search music"))

        val openAndPlay = RegexFastPathEngine.match("open youitube music and play a song")
        assertNotNull(openAndPlay)
        assertEquals("play_in_app", openAndPlay?.toolCall?.toolName)
        assertEquals("youitube music", openAndPlay?.toolCall?.args?.get("app_name"))

        val genericPlay = RegexFastPathEngine.match("play a song")
        assertNotNull(genericPlay)
        assertEquals("play_in_app", genericPlay?.toolCall?.toolName)
        assertEquals("random", genericPlay?.toolCall?.args?.get("query"))

        val transportResume = RegexFastPathEngine.match("play")
        assertEquals("media_transport", transportResume?.toolCall?.toolName)
        assertEquals("play", transportResume?.toolCall?.args?.get("command"))

        val specificPlay = RegexFastPathEngine.match("play Bohemian Rhapsody")
        assertEquals("play_in_app", specificPlay?.toolCall?.toolName)
        assertEquals("Bohemian Rhapsody", specificPlay?.toolCall?.args?.get("query"))

        val playOnSpotify = RegexFastPathEngine.match("play jazz on Spotify")
        assertEquals("play_in_app", playOnSpotify?.toolCall?.toolName)
        assertEquals("Spotify", playOnSpotify?.toolCall?.args?.get("app_name"))
        assertEquals("jazz", playOnSpotify?.toolCall?.args?.get("query"))

        val searchInYt = RegexFastPathEngine.match("search Kotlin in YouTube")
        assertEquals("search_in_app", searchInYt?.toolCall?.toolName)
        assertEquals("YouTube", searchInYt?.toolCall?.args?.get("app_name"))
        assertEquals("Kotlin", searchInYt?.toolCall?.args?.get("query"))

        val nav = RegexFastPathEngine.match("navigate to airport")
        assertEquals("navigate_in_maps", nav?.toolCall?.toolName)
        assertEquals("airport", nav?.toolCall?.args?.get("destination"))

        val skillsQuery = RegexFastPathEngine.match("what can Spotify do")
        assertEquals("get_app_skills", skillsQuery?.toolCall?.toolName)
        assertEquals("Spotify", skillsQuery?.toolCall?.args?.get("app_name"))
    }

    @Test
    fun `alexa shopping list and device briefing execute against real local storage`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }

        val addReport = executor.execute(
            ToolCallRequest(
                toolName = "manage_list_item",
                args = mapOf("list_name" to "shopping", "action" to "add", "item" to "Espresso beans")
            )
        )
        assertTrue(addReport.success)

        val readReport = executor.execute(
            ToolCallRequest(
                toolName = "manage_list_item",
                args = mapOf("list_name" to "shopping", "action" to "list")
            )
        )
        assertTrue(readReport.summary.contains("Espresso beans"))

        val timeReport = executor.execute(
            ToolCallRequest(toolName = "get_device_briefing", args = mapOf("topic" to "time_date"))
        )
        assertTrue(timeReport.success)
        assertTrue(timeReport.summary.startsWith("It's "))
        fts.close()
    }

    @Test
    fun `tier 1_5 semantic lookup never selects disabled smart home skill`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LlmModelManager(context)
        modelManager.deleteActiveModel(ModelSlot.EMBEDDING)
        val embeddingRuntime = EmbeddingGemmaRuntime(context, modelManager)

        val searchMatch = SemanticLookupEngine.lookup("Find my local notes")
        assertTrue(searchMatch.candidateSkills.all { it.enabled })
        assertFalse(searchMatch.candidateSkills.any { it.id == "smart-home-controller" })

        val runtimeMatch = SemanticLookupEngine.lookupWithRuntime("Find my local notes", embeddingRuntime)
        assertFalse(runtimeMatch.usedNeuralEmbedding)
        assertTrue(runtimeMatch.candidateSkills.all { it.enabled })
        assertFalse(runtimeMatch.candidateSkills.any { it.id == "smart-home-controller" })
        embeddingRuntime.shutdown()
    }

    @Test
    fun `embedding gemma runtime refuses inference when model is missing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LlmModelManager(context)
        modelManager.deleteActiveModel(ModelSlot.EMBEDDING)
        val embeddingRuntime = EmbeddingGemmaRuntime(context, modelManager)

        assertFalse(embeddingRuntime.isModelFilePresent())
        assertFalse(embeddingRuntime.warmup())
        assertNull(embeddingRuntime.embedQuery("parking locker code"))
        assertNull(embeddingRuntime.embedDocument("Parking", "Basement P2"))
        embeddingRuntime.shutdown()
    }

    @Test
    fun `local sqlite fts index starts empty and supports hybrid vector search`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = LocalFtsIndexHelper(context)
        assertEquals(0, helper.countDocuments())

        val targetVec = floatArrayOf(1f, 0f, 0f, 0f)
        val otherVec = floatArrayOf(0f, 1f, 0f, 0f)

        helper.upsertDocument(
            docId = "mem_test",
            category = "notes",
            title = "Gym Locker",
            body = "Locker 19 code 7391",
            uriOrRef = "memory://locker",
            embedding = targetVec
        )
        helper.upsertDocument(
            docId = "mem_other",
            category = "notes",
            title = "Grocery List",
            body = "Buy milk and coffee",
            uriOrRef = "memory://groceries",
            embedding = otherVec
        )

        val memHits = helper.search("7391", "notes")
        assertTrue(memHits.any { it.docId == "mem_test" })

        val vectorHits = helper.hybridSearch("unrelated phrase", targetVec, "notes")
        assertTrue(vectorHits.isNotEmpty())
        assertEquals("mem_test", vectorHits.first().docId)
        helper.close()
    }

    @Test
    fun `pipeline router refuses AI queries when LLM model is not present`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LlmModelManager(context)
        modelManager.deleteActiveModel(ModelSlot.LLM)
        modelManager.deleteActiveModel(ModelSlot.EMBEDDING)
        assertFalse(modelManager.isModelPresent(ModelSlot.LLM))

        val fts = LocalFtsIndexHelper(context)
        val embeddingRuntime = EmbeddingGemmaRuntime(context, modelManager)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts, embeddingRuntime) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor, embeddingRuntime)

        // Tier 1 regex fast-path still works locally without LLM
        val fastOutcome = router.routeAndExecute("turn on flashlight") { _, _, _ -> }
        assertEquals(RoutingTier.TIER_1_REGEX, fastOutcome.routingTier)

        // Tier 2/3 LLM query MUST NOT work when model file is missing
        val blockedOutcome = router.routeAndExecute("Summarize my saved notes and plan my day") { _, _, _ -> }
        assertTrue(blockedOutcome.requiresModelSetup)
        assertEquals(OrbVisualMode.ERROR_ABORTED, blockedOutcome.orbMode)
        embeddingRuntime.shutdown()
        fts.close()
    }

    @Test
    fun `yt music launcher label resolves youtube music queries via aliases and tags`() {
        val deviceApps = listOf(
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "YouTube",
                packageName = "com.google.android.youtube",
                categoryLabel = "Video/Streaming",
                capabilities = listOf("launch", "media_search", "in_app_search", "ui_screen_automation"),
                howToUse = "search_in_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "YT Music",
                packageName = "com.google.android.apps.youtube.music",
                categoryLabel = "Audio/Music",
                capabilities = listOf("launch", "media_search", "ui_screen_automation"),
                howToUse = "play_in_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Maps",
                packageName = "com.google.android.apps.maps",
                categoryLabel = "Maps/Navigation",
                capabilities = listOf("launch", "navigation", "ui_screen_automation"),
                howToUse = "navigate_in_maps"
            )
        )

        val queries = listOf("youtube music", "youitube music", "yt music", "ytm", "YouTube Music")
        for (q in queries) {
            val matched = AppCapabilityRegistry.findBestMatchFromProfiles(deviceApps, q)
            assertNotNull("Expected match for '$q'", matched)
            assertEquals("Query '$q' should resolve to YT Music", "com.google.android.apps.youtube.music", matched?.packageName)
        }

        val mapsMatch = AppCapabilityRegistry.findBestMatchFromProfiles(deviceApps, "google maps")
        assertEquals("com.google.android.apps.maps", mapsMatch?.packageName)

        val llmCtx = AppCapabilityRegistry.formatLlmAppContext(deviceApps, "play song on youtube music")
        assertTrue(llmCtx.contains("YT Music"))
        assertTrue(llmCtx.contains("YouTube Music"))
        assertTrue(llmCtx.contains("tags:"))
    }

    @Test
    fun `local screen control skill triggers fast path and enforces tier red confirmation`() = runTest {
        val tapPlay = RegexFastPathEngine.match("tap Play")
        assertNotNull(tapPlay)
        assertEquals("local-screen-control", tapPlay?.skillId)
        assertEquals("ui_find_and_tap", tapPlay?.toolCall?.toolName)
        assertEquals(SafetyTier.GREEN, tapPlay?.toolCall?.safetyTier)

        val typeSearch = RegexFastPathEngine.match("type lo-fi beats into Search")
        assertNotNull(typeSearch)
        assertEquals("ui_find_and_tap", typeSearch?.toolCall?.toolName)
        assertEquals("type", typeSearch?.toolCall?.args?.get("action_type"))
        assertEquals("lo-fi beats", typeSearch?.toolCall?.args?.get("input_text"))
        assertEquals("Search", typeSearch?.toolCall?.args?.get("target_label"))

        val scrollDown = RegexFastPathEngine.match("scroll down")
        assertEquals("ui_act", scrollDown?.toolCall?.toolName)
        assertEquals("SCROLL_DOWN", scrollDown?.toolCall?.args?.get("action"))

        val automateYtMusic = RegexFastPathEngine.match("automate youtube music to play jazz")
        assertNotNull(automateYtMusic)
        assertEquals("screen_control_app", automateYtMusic?.toolCall?.toolName)
        assertEquals("youtube music", automateYtMusic?.toolCall?.args?.get("app_name"))
        assertEquals("play jazz", automateYtMusic?.toolCall?.args?.get("goal"))

        // Risky button taps pause for Tier Red confirmation even via Tier 1 fast-path
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LlmModelManager(context)
        val fts = LocalFtsIndexHelper(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        val redOutcome = router.routeAndExecute("tap Pay Now") { _, _, _ -> }
        assertEquals(OrbVisualMode.CONFIRMATION_PAUSED, redOutcome.orbMode)
        assertNotNull(redOutcome.pendingConfirmation)
        fts.close()
    }

    @Test
    fun `in-app search supports natural phrasing app intents and gallery screen control fallback`() {
        val ytSearchFor = RegexFastPathEngine.match("search youtube for lo-fi beats")
        assertNotNull(ytSearchFor)
        assertEquals("search_in_app", ytSearchFor?.toolCall?.toolName)
        assertEquals("youtube", ytSearchFor?.toolCall?.args?.get("app_name"))
        assertEquals("lo-fi beats", ytSearchFor?.toolCall?.args?.get("query"))

        val playSearchFor = RegexFastPathEngine.match("search playstore for spotify")
        assertNotNull(playSearchFor)
        assertEquals("search_in_app", playSearchFor?.toolCall?.toolName)
        assertEquals("playstore", playSearchFor?.toolCall?.args?.get("app_name"))
        assertEquals("spotify", playSearchFor?.toolCall?.args?.get("query"))

        val gallerySearch = RegexFastPathEngine.match("search gallery for sunset")
        assertNotNull(gallerySearch)
        assertEquals("search_in_app", gallerySearch?.toolCall?.toolName)
        assertEquals("gallery", gallerySearch?.toolCall?.args?.get("app_name"))
        assertEquals("sunset", gallerySearch?.toolCall?.args?.get("query"))

        val findInPhotos = RegexFastPathEngine.match("find cats in google photos")
        assertNotNull(findInPhotos)
        assertEquals("search_in_app", findInPhotos?.toolCall?.toolName)
        assertEquals("google photos", findInPhotos?.toolCall?.args?.get("app_name"))
        assertEquals("cats", findInPhotos?.toolCall?.args?.get("query"))

        val openAndSearch = RegexFastPathEngine.match("open youtube and search for kotlin")
        assertNotNull(openAndSearch)
        assertEquals("search_in_app", openAndSearch?.toolCall?.toolName)
        assertEquals("youtube", openAndSearch?.toolCall?.args?.get("app_name"))
        assertEquals("kotlin", openAndSearch?.toolCall?.args?.get("query"))

        // Local FTS5 sources must not be hijacked as third-party apps
        assertNull(RegexFastPathEngine.match("find my receipt in files"))
        assertNull(RegexFastPathEngine.match("search my notes for wifi"))

        // PlayStore & Gallery alias matching
        val sampleApps = listOf(
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Google Play Store",
                packageName = "com.android.vending",
                categoryLabel = "Shopping/Food",
                capabilities = listOf("launch", "in_app_search", "ui_screen_automation"),
                howToUse = "search_in_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Gallery",
                packageName = "com.sec.android.gallery3d",
                categoryLabel = "Camera/Photos",
                capabilities = listOf("launch", "ui_screen_automation"),
                howToUse = "search_in_app"
            )
        )
        assertEquals("com.android.vending", AppCapabilityRegistry.findBestMatchFromProfiles(sampleApps, "playstore")?.packageName)
        assertEquals("com.sec.android.gallery3d", AppCapabilityRegistry.findBestMatchFromProfiles(sampleApps, "gallery")?.packageName)

        // App-specific search intent builders vs screen-control fallback for Gallery
        val ytIntents = DeviceActionExecutor.buildAppSearchIntents("com.google.android.youtube", "Video/Streaming", "kotlin")
        assertTrue(ytIntents.any { it.dataString?.contains("youtube.com/results") == true })

        val ytmIntents = DeviceActionExecutor.buildAppSearchIntents("com.google.android.apps.youtube.music", "Audio/Music", "jazz")
        assertTrue(ytmIntents.any { it.dataString?.contains("music.youtube.com/search") == true })

        val playIntents = DeviceActionExecutor.buildAppSearchIntents("com.android.vending", "Shopping/Food", "spotify")
        assertTrue(playIntents.any { it.dataString?.startsWith("market://search") == true })

        val galleryIntents = DeviceActionExecutor.buildAppSearchIntents("com.sec.android.gallery3d", "Camera/Photos", "sunset")
        assertTrue("Gallery has no direct URI intent and proceeds to screen control", galleryIntents.isEmpty())
    }

    @Test
    fun `multi-stage planner finalizes intent open plus ui automation globally across apps`() = runTest {
        val apps = listOf(
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "YouTube",
                packageName = "com.google.android.youtube",
                categoryLabel = "Video/Streaming",
                capabilities = listOf("launch", "media_search", "in_app_search", "ui_screen_automation"),
                howToUse = "search_in_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Gallery",
                packageName = "com.sec.android.gallery3d",
                categoryLabel = "Camera/Photos",
                capabilities = listOf("launch", "ui_screen_automation"),
                howToUse = "search_in_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Google Play Store",
                packageName = "com.android.vending",
                categoryLabel = "Shopping/Food",
                capabilities = listOf("launch", "in_app_search", "ui_screen_automation"),
                howToUse = "search_in_app"
            )
        )

        // Exact user repro: "open youtube and find a christ mas song"
        val ytPlan = com.example.engine.MultiStageActionPlanner.plan(
            normalized = InputNormalizer.normalize("open youtube and find a christ mas song"),
            installedApps = apps
        )
        assertNotNull(ytPlan)
        assertEquals(2, ytPlan!!.stages.size)
        assertEquals(com.example.engine.MultiStageActionPlanner.StageExecutionKind.INTENT_LAUNCH, ytPlan.stages[0].kind)
        assertEquals("launch_app", ytPlan.stages[0].toolCall.toolName)
        assertEquals("YouTube", ytPlan.stages[0].toolCall.args["app_name"])
        assertEquals(com.example.engine.MultiStageActionPlanner.StageExecutionKind.UI_AUTOMATION, ytPlan.stages[1].kind)
        assertEquals("screen_control_app", ytPlan.stages[1].toolCall.toolName)
        assertEquals("search_on_screen", ytPlan.stages[1].toolCall.args["goal"])
        assertEquals("christ mas song", ytPlan.stages[1].toolCall.args["query_text"])

        // Preserves "tom and jerry" without splitting inside search query
        val tomAndJerryPlan = com.example.engine.MultiStageActionPlanner.plan(
            normalized = InputNormalizer.normalize("open youtube and find tom and jerry"),
            installedApps = apps
        )
        assertNotNull(tomAndJerryPlan)
        assertEquals(2, tomAndJerryPlan!!.stages.size)
        assertEquals("tom and jerry", tomAndJerryPlan.stages[1].toolCall.args["query_text"])

        // 3-stage global workflow on Gallery (Intent -> UI Search -> UI Scroll)
        val gallery3Stage = com.example.engine.MultiStageActionPlanner.plan(
            normalized = InputNormalizer.normalize("open gallery and find sunset and scroll down"),
            installedApps = apps
        )
        assertNotNull(gallery3Stage)
        assertEquals(3, gallery3Stage!!.stages.size)
        assertEquals("launch_app", gallery3Stage.stages[0].toolCall.toolName)
        assertEquals("Gallery", gallery3Stage.stages[0].toolCall.args["app_name"])
        assertEquals("screen_control_app", gallery3Stage.stages[1].toolCall.toolName)
        assertEquals("sunset", gallery3Stage.stages[1].toolCall.args["query_text"])
        assertEquals("ui_act", gallery3Stage.stages[2].toolCall.toolName)
        assertEquals("SCROLL_DOWN", gallery3Stage.stages[2].toolCall.args["action"])

        // Single-clause search in Gallery (no direct URI intent) auto-plans Open Intent + UI Control
        val galleryImplicit2Stage = com.example.engine.MultiStageActionPlanner.plan(
            normalized = InputNormalizer.normalize("search gallery for beach"),
            installedApps = apps
        )
        assertNotNull(galleryImplicit2Stage)
        assertEquals(2, galleryImplicit2Stage!!.stages.size)
        assertEquals("launch_app", galleryImplicit2Stage.stages[0].toolCall.toolName)
        assertEquals("screen_control_app", galleryImplicit2Stage.stages[1].toolCall.toolName)

        // Local personal search must NOT be hijacked into app UI automation
        assertNull(
            com.example.engine.MultiStageActionPlanner.plan(
                normalized = InputNormalizer.normalize("find my receipt in files"),
                installedApps = apps
            )
        )

        // Router emits finalized PENDING stages first, then aborts Stage 2 safely if Stage 1 app isn't installed
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LlmModelManager(context)
        val fts = LocalFtsIndexHelper(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        val headersSeen = mutableListOf<String>()
        var sawPendingPlan = false
        val outcome = router.routeAndExecute("open youtube and find a christ mas song") { pills, _, header ->
            headersSeen.add(header)
            if (header.startsWith("PLAN FINALIZED")) {
                sawPendingPlan = pills.any { it.status == com.example.data.StepStatus.PENDING && it.label.contains("UI Control") }
            }
        }
        assertTrue("Router should finalize and broadcast PENDING multi-stage plan before running", sawPendingPlan)
        assertEquals(2, outcome.steps.count { it.label.contains("youtube", ignoreCase = true) })
        // Since Stage 1 failed in empty Robolectric PM, Stage 2 should be marked BLOCKED and not run blindly
        assertTrue(outcome.steps.last().status == com.example.data.StepStatus.BLOCKED)
        assertEquals(1, outcome.toolCalls.size)
        fts.close()
    }

    @Test
    fun `random song is chosen when playing music unless user specifies a song`() {
        val (picked1, wasRandom1) = RandomSongCatalog.resolveEffectiveSongQuery("music")
        assertTrue(wasRandom1)
        assertTrue(picked1 in RandomSongCatalog.allTracks())

        val (picked2, wasRandom2) = RandomSongCatalog.resolveEffectiveSongQuery("a random song")
        assertTrue(wasRandom2)
        assertTrue(picked2 in RandomSongCatalog.allTracks())
        assertTrue(picked1 != picked2)

        val (specific, wasRandomSpec) = RandomSongCatalog.resolveEffectiveSongQuery("Bohemian Rhapsody")
        assertFalse(wasRandomSpec)
        assertEquals("Bohemian Rhapsody", specific)

        // Multi-stage open + generic music keeps "random", whereas specific song preserves title
        val apps = listOf(
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "YT Music",
                packageName = "com.google.android.apps.youtube.music",
                categoryLabel = "Audio/Music",
                capabilities = listOf("launch", "media_search", "ui_screen_automation"),
                howToUse = "play_in_app"
            )
        )
        val openPlayMusic = com.example.engine.MultiStageActionPlanner.plan(
            normalized = InputNormalizer.normalize("open youtube music and play music"),
            installedApps = apps
        )
        assertNotNull(openPlayMusic)
        assertEquals(2, openPlayMusic!!.stages.size)
        assertEquals("random", openPlayMusic.stages[1].toolCall.args["query_text"])

        val openPlaySpecific = com.example.engine.MultiStageActionPlanner.plan(
            normalized = InputNormalizer.normalize("open youtube music and play Bohemian Rhapsody"),
            installedApps = apps
        )
        assertNotNull(openPlaySpecific)
        assertEquals(2, openPlaySpecific!!.stages.size)
        assertEquals("Bohemian Rhapsody", openPlaySpecific.stages[1].toolCall.args["query_text"])
    }

    @Test
    fun `accessibility ui automation scans visible nodes and reports disabled setup state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        // When Accessibility Service is off, UI tap outcomes guide user cleanly with requiresAccessibilitySetup
        val blockedOutcome = router.routeAndExecute("tap Search") { _, _, _ -> }
        assertTrue(blockedOutcome.requiresAccessibilitySetup)
        assertTrue(blockedOutcome.responseText.contains("Accessibility", ignoreCase = true))

        // Verify Robolectric AccessibilityService scan & click execution using live node cache
        val controller = Robolectric.buildService(NovaAccessibilityService::class.java).create()
        val svc = controller.get()
        val onConnMethod = NovaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
        onConnMethod.isAccessible = true
        onConnMethod.invoke(svc)

        val root = android.view.accessibility.AccessibilityNodeInfo()
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        val searchBtn = android.view.accessibility.AccessibilityNodeInfo()
        searchBtn.className = "android.widget.Button"
        searchBtn.contentDescription = "Search YouTube"
        searchBtn.isClickable = true
        searchBtn.isVisibleToUser = true
        val shadowSearch = Shadows.shadowOf(searchBtn)
        val actionLog = mutableListOf<Int>()
        shadowSearch.setOnPerformActionListener { action, _ ->
            actionLog.add(action)
            true
        }

        val editBox = android.view.accessibility.AccessibilityNodeInfo()
        editBox.className = "android.widget.EditText"
        editBox.hintText = "Search songs"
        editBox.isEditable = true
        editBox.isClickable = true
        editBox.isVisibleToUser = true
        val shadowEdit = Shadows.shadowOf(editBox)
        shadowEdit.setOnPerformActionListener { action, _ ->
            actionLog.add(action)
            true
        }

        val shadowRoot = Shadows.shadowOf(root)
        shadowRoot.addChild(searchBtn)
        shadowRoot.addChild(editBox)

        val shadowSvc = Shadows.shadowOf(svc)
        shadowSvc.setRootInActiveWindow(root)

        val scanned = svc.scanActiveScreenNodes()
        assertEquals(2, scanned.size)
        assertTrue(scanned.any { it.label.contains("Search YouTube") && it.clickable })
        assertTrue(scanned.any { it.editable })

        val tapOutcome = svc.performFindAndAct("Search YouTube", "CLICK")
        assertTrue(tapOutcome.success)
        assertTrue(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK in actionLog)

        val typeOutcome = svc.performFindAndAct("Search songs", "TYPE", "Midnight City")
        assertTrue(typeOutcome.success)
        assertTrue(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS in actionLog)
        assertTrue(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT in actionLog)

        controller.destroy()
        fts.close()
    }

    @Test
    fun `play song with preposition in title parses full title rather than fake app name`() = runTest {
        val installedApps = listOf(
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "YouTube",
                packageName = "com.google.android.youtube",
                categoryLabel = "Video",
                capabilities = listOf("launch", "media_search", "ui_screen_automation"),
                howToUse = "search_in_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Spotify",
                packageName = "com.spotify.music",
                categoryLabel = "Audio/Music",
                capabilities = listOf("launch", "media_search", "ui_screen_automation"),
                howToUse = "play_in_app"
            )
        )

        val directMatch = RegexFastPathEngine.match(
            InputNormalizer.normalize("play christmas in our hearts").cleanedFull,
            installedApps
        )
        assertNotNull(directMatch)
        assertEquals("play_in_app", directMatch!!.toolCall.toolName)
        assertEquals("", directMatch.toolCall.args["app_name"])
        assertEquals("christmas in our hearts", directMatch.toolCall.args["query"])

        val explicitAppMatch = RegexFastPathEngine.match(
            InputNormalizer.normalize("play jazz on Spotify").cleanedFull,
            installedApps
        )
        assertNotNull(explicitAppMatch)
        assertEquals("play_in_app", explicitAppMatch!!.toolCall.toolName)
        assertEquals("Spotify", explicitAppMatch.toolCall.args["app_name"])
        assertEquals("jazz", explicitAppMatch.toolCall.args["query"])

        val multiStage = com.example.engine.MultiStageActionPlanner.plan(
            normalized = InputNormalizer.normalize("open youtube and play christmas in our hearts"),
            installedApps = installedApps
        )
        assertNotNull(multiStage)
        assertEquals(2, multiStage!!.stages.size)
        assertEquals("launch_app", multiStage.stages[0].toolCall.toolName)
        assertEquals("YouTube", multiStage.stages[0].toolCall.args["app_name"])
        assertEquals("screen_control_app", multiStage.stages[1].toolCall.toolName)
        assertEquals("YouTube", multiStage.stages[1].toolCall.args["app_name"])
        assertEquals("search_and_play", multiStage.stages[1].toolCall.args["goal"])
        assertEquals("christmas in our hearts", multiStage.stages[1].toolCall.args["query_text"])

        // End-to-end router check for "play christmas in our hearts" (must never think "our hearts" is an app)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        val outcome = router.routeAndExecute("play christmas in our hearts") { _, _, _ -> }
        assertFalse(outcome.responseText.contains("couldn't find 'our hearts'", ignoreCase = true))
        fts.close()
    }

    @Test
    fun `hey nova wake phrase extraction normalization and router wakeup work cleanly`() = runTest {
        assertEquals("", NovaVoiceForegroundService.extractWakeCommand("Hey Nova"))
        assertEquals("turn on bluetooth", NovaVoiceForegroundService.extractWakeCommand("Hey Nova, turn on bluetooth"))
        assertEquals(
            "play christmas in our hearts",
            NovaVoiceForegroundService.extractWakeCommand("Okay Nova play christmas in our hearts")
        )
        assertNull(NovaVoiceForegroundService.extractWakeCommand("hello world"))

        val strippedNorm = InputNormalizer.normalize("Hey Nova turn on flashlight")
        assertTrue(strippedNorm.wakeWordStripped)
        assertFalse(strippedNorm.isWakeWordOnly)
        assertEquals("turn on flashlight", strippedNorm.cleanedFull)

        val fastMatch = RegexFastPathEngine.match(strippedNorm.cleanedFull)
        assertNotNull(fastMatch)
        assertEquals("set_hardware_state", fastMatch!!.toolCall.toolName)
        assertEquals("flashlight", fastMatch.toolCall.args["feature"])
        assertEquals("on", fastMatch.toolCall.args["state"])

        val bareNorm = InputNormalizer.normalize("Hey Nova")
        assertTrue(bareNorm.wakeWordStripped)
        assertTrue(bareNorm.isWakeWordOnly)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        val wakeOutcome = router.routeAndExecute("Hey Nova") { _, _, _ -> }
        assertEquals(OrbVisualMode.LISTENING, wakeOutcome.orbMode)
        assertFalse(wakeOutcome.requiresModelSetup)
        assertFalse(wakeOutcome.responseText.contains("model", ignoreCase = true))
        fts.close()
    }

    @Test
    fun `important notification filter prompts only messages missed calls and reminders`() {
        val smsMsg = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "0|com.whatsapp|1",
                packageName = "com.whatsapp",
                ownPackageName = "com.example",
                appLabel = "WhatsApp",
                category = Notification.CATEGORY_MESSAGE,
                title = "Alice",
                text = "Are we still meeting at 3?",
                hasMessagingMessages = true
            )
        )
        assertNotNull(smsMsg)
        assertEquals(ImportantNotificationType.MESSAGE, smsMsg!!.type)
        assertTrue(smsMsg.spokenPrompt.contains("Alice"))
        assertTrue(smsMsg.spokenPrompt.contains("meeting at 3"))

        val missedCall = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "0|com.google.android.dialer|2",
                packageName = "com.google.android.dialer",
                ownPackageName = "com.example",
                appLabel = "Phone",
                category = Notification.CATEGORY_MISSED_CALL,
                title = "Missed call",
                text = "Mom"
            )
        )
        assertNotNull(missedCall)
        assertEquals(ImportantNotificationType.MISSED_CALL, missedCall!!.type)
        assertTrue(missedCall.spokenPrompt.contains("missed a call", ignoreCase = true))

        val reminderAlert = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "0|com.google.android.calendar|3",
                packageName = "com.google.android.calendar",
                ownPackageName = "com.example",
                appLabel = "Calendar",
                category = Notification.CATEGORY_REMINDER,
                title = "Dentist Appointment",
                text = "Today at 4:00 PM"
            )
        )
        assertNotNull(reminderAlert)
        assertEquals(ImportantNotificationType.REMINDER, reminderAlert!!.type)
        assertTrue(reminderAlert.spokenPrompt.contains("Dentist Appointment"))

        // Promos, media controls, downloads, group summaries, and system noise must be ignored
        val promo = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "0|com.shop.app|4",
                packageName = "com.shop.app",
                ownPackageName = "com.example",
                appLabel = "ShopNow",
                category = Notification.CATEGORY_PROMO,
                title = "Flash Sale 50% OFF!",
                text = "Limited time offer today"
            )
        )
        assertNull(promo)

        val mediaOngoing = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "0|com.spotify.music|5",
                packageName = "com.spotify.music",
                ownPackageName = "com.example",
                appLabel = "Spotify",
                category = Notification.CATEGORY_TRANSPORT,
                title = "Playing Now",
                text = "Track Title",
                hasMediaSession = true,
                flags = Notification.FLAG_ONGOING_EVENT
            )
        )
        assertNull(mediaOngoing)

        val downloadProgress = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "0|com.android.providers.downloads|6",
                packageName = "com.android.providers.downloads",
                ownPackageName = "com.example",
                appLabel = "Downloads",
                category = Notification.CATEGORY_PROGRESS,
                title = "Downloading update",
                text = "45%",
                hasProgress = true
            )
        )
        assertNull(downloadProgress)

        val groupSummary = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "0|com.whatsapp|summary",
                packageName = "com.whatsapp",
                ownPackageName = "com.example",
                appLabel = "WhatsApp",
                category = Notification.CATEGORY_MESSAGE,
                title = "WhatsApp",
                text = "3 new messages",
                flags = Notification.FLAG_GROUP_SUMMARY
            )
        )
        assertNull(groupSummary)
    }

    @Test
    fun `read notifications fast path and create reminder auto saves without waiting for user`() = runTest {
        val readNotifMatch = RegexFastPathEngine.match("read my notifications")
        assertNotNull(readNotifMatch)
        assertEquals("read_recent_messages", readNotifMatch!!.toolCall.toolName)
        assertEquals(SafetyTier.GREEN, readNotifMatch.toolCall.safetyTier)

        val createRemMatch = RegexFastPathEngine.match("create reminder buy milk at 5pm")
        assertNotNull(createRemMatch)
        assertEquals("create_calendar_event", createRemMatch!!.toolCall.toolName)
        assertEquals(SafetyTier.GREEN, createRemMatch.toolCall.safetyTier)
        assertEquals("buy milk", createRemMatch.toolCall.args["title"])
        assertEquals("at 5pm", createRemMatch.toolCall.args["start_iso"])

        val bareRemMatch = RegexFastPathEngine.match("create reminder")
        assertNotNull(bareRemMatch)
        assertEquals("create_calendar_event", bareRemMatch!!.toolCall.toolName)
        assertEquals("Nova Reminder", bareRemMatch.toolCall.args["title"])

        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        val remOutcome = router.routeAndExecute("create reminder call dentist tomorrow at 9am") { _, _, _ -> }
        assertNull(remOutcome.pendingConfirmation)
        assertTrue(remOutcome.responseText.contains("saved reminder", ignoreCase = true))
        assertTrue(remOutcome.responseText.contains("call dentist", ignoreCase = true))
        assertFalse(remOutcome.responseText.contains("Opened Calendar", ignoreCase = true))

        // Verify Accessibility autoApproveSaveForm automatically taps Save on an external editor template
        val controller = Robolectric.buildService(NovaAccessibilityService::class.java).create()
        val svc = controller.get()
        val onConnMethod = NovaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
        onConnMethod.isAccessible = true
        onConnMethod.invoke(svc)

        val root = android.view.accessibility.AccessibilityNodeInfo()
        root.packageName = "com.google.android.calendar"
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        val cancelBtn = android.view.accessibility.AccessibilityNodeInfo()
        cancelBtn.text = "Cancel"
        cancelBtn.className = "android.widget.Button"
        cancelBtn.isClickable = true
        cancelBtn.isVisibleToUser = true
        var cancelClicked = false
        Shadows.shadowOf(cancelBtn).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) cancelClicked = true
            true
        }

        val saveBtn = android.view.accessibility.AccessibilityNodeInfo()
        saveBtn.text = "Save"
        saveBtn.className = "android.widget.Button"
        saveBtn.isClickable = true
        saveBtn.isVisibleToUser = true
        var saveClicked = false
        Shadows.shadowOf(saveBtn).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) saveClicked = true
            true
        }

        Shadows.shadowOf(root).addChild(cancelBtn)
        Shadows.shadowOf(root).addChild(saveBtn)
        Shadows.shadowOf(svc).setRootInActiveWindow(root)

        val autoSaveOutcome = svc.autoApproveSaveForm(timeoutMs = 500L)
        assertTrue(autoSaveOutcome.success)
        assertTrue(saveClicked)
        assertFalse(cancelClicked)

        controller.destroy()
        fts.close()
    }

    @Test
    fun `embedding gemma prompt formatting and clean skill prototypes follow spec`() = runTest {
        assertEquals(
            "task: search result | query: electricity bill pdf",
            EmbeddingGemmaRuntime.formatQueryPrompt("  electricity bill pdf  ")
        )
        assertEquals(
            "title: Utility Bill | text: Total due 1200 pesos",
            EmbeddingGemmaRuntime.formatDocumentPrompt("  Utility Bill ", " Total due 1200 pesos ")
        )
        assertEquals(
            "title: none | text: Parked on Basement 2 pillar B4",
            EmbeddingGemmaRuntime.formatDocumentPrompt("  ", "Parked on Basement 2 pillar B4")
        )

        SkillCatalog.enabledSkills.forEach { skill ->
            val cleanPrototypes = SemanticLookupEngine.buildCleanSkillPrototypes(skill)
            assertTrue("Skill ${skill.id} should have prototypes", cleanPrototypes.isNotEmpty())
            cleanPrototypes.forEach { proto ->
                assertFalse("Prototype for ${skill.id} must not leak JSON syntax: $proto", proto.contains("{"))
                assertFalse("Prototype for ${skill.id} must not leak arrow examples: $proto", proto.contains("->"))
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)
        fts.upsertDocument("doc_late", "notes", "Tax Receipt", "Receipt 2026", "memory://tax", null)
        val newVec = EmbeddingGemmaRuntime.normalizeL2(floatArrayOf(3f, 4f, 0f, 0f))
        fts.updateDocumentEmbedding("doc_late", newVec)

        val hits = fts.hybridSearch("completely unrelated query", newVec, "notes")
        assertTrue(hits.any { it.docId == "doc_late" })
        fts.close()
    }

    @Test
    fun `progressive disclosure injects skill guidance companion tools and balanced examples`() {
        SkillCatalog.enabledSkills.forEach { skill ->
            assertTrue("Skill ${skill.id} should define operational guidance", skill.guidance.isNotBlank())
        }

        val disclosedTools = DeferredToolRegistry.getToolsForSkills(
            listOf("local-screen-control", "messaging-omni-send")
        )
        val toolNames = disclosedTools.map { it.name }.toSet()
        assertTrue("Expected primary tool screen_control_app", "screen_control_app" in toolNames)
        assertTrue("Expected companion tool launch_app for local-screen-control", "launch_app" in toolNames)
        assertTrue("Expected companion tool ui_act for local-screen-control", "ui_act" in toolNames)
        assertTrue("Expected companion tool search_contacts for messaging-omni-send", "search_contacts" in toolNames)

        val prompt = com.example.processB.LiteRtLmRuntimeWrapper.buildProgressivePrompt(
            query = "open youtube and text Mom",
            disclosedSkills = listOfNotNull(
                SkillCatalog.findById("local-screen-control"),
                SkillCatalog.findById("messaging-omni-send")
            ),
            fewShotHints = emptyList()
        )
        assertTrue(prompt.contains("Skill Guidance:"))
        assertTrue(prompt.contains("launch_app("))
        assertTrue(prompt.contains("search_contacts("))
        assertTrue(prompt.contains("read my important notifications") || prompt.contains("text Mom I am on my way"))
    }

    @Test
    fun `upgraded skill fast paths and executor handlers work end to end`() = runTest {
        val volUp = RegexFastPathEngine.match("turn up the volume")
        assertNotNull(volUp)
        assertEquals("set_level", volUp?.toolCall?.toolName)
        assertEquals("+15", volUp?.toolCall?.args?.get("percent"))

        val brighter = RegexFastPathEngine.match("increase brightness")
        assertNotNull(brighter)
        assertEquals("brightness", brighter?.toolCall?.args?.get("target"))
        assertEquals("+20", brighter?.toolCall?.args?.get("percent"))

        val ans = RegexFastPathEngine.match("answer call")
        assertEquals("handle_incoming_call", ans?.toolCall?.toolName)
        assertEquals("answer", ans?.toolCall?.args?.get("action"))

        val dec = RegexFastPathEngine.match("decline incoming call")
        assertEquals("handle_incoming_call", dec?.toolCall?.toolName)
        assertEquals("decline", dec?.toolCall?.args?.get("action"))

        val cal = RegexFastPathEngine.match("what is on my schedule today")
        assertEquals("query_calendar_events", cal?.toolCall?.toolName)

        val saveMem = RegexFastPathEngine.match("remember my locker code is 4812")
        assertEquals("save_memory_note", saveMem?.toolCall?.toolName)
        assertEquals("locker_code", saveMem?.toolCall?.args?.get("key"))

        val recallMem = RegexFastPathEngine.match("where did i park")
        assertEquals("recall_memory_note", recallMem?.toolCall?.toolName)
        assertEquals("park", recallMem?.toolCall?.args?.get("query"))

        val morning = RegexFastPathEngine.match("good morning")
        assertEquals("get_routine_steps", morning?.toolCall?.toolName)
        assertEquals("morning", morning?.toolCall?.args?.get("routine_name"))

        val focus = RegexFastPathEngine.match("start focus mode")
        assertEquals("get_routine_steps", focus?.toolCall?.toolName)
        assertEquals("focus", focus?.toolCall?.args?.get("routine_name"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)
        fts.upsertDocument("contact_mom", "contacts", "Mom", "+15551234567", "tel:+15551234567")

        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }

        // store_turn_artifact persists artifacts cleanly
        val artifactRes = executor.execute(
            ToolCallRequest("store_turn_artifact", mapOf("key" to "last_receipt", "value" to "PHP 1250"))
        )
        assertTrue(artifactRes.success)
        assertEquals("PHP 1250", executor.getTurnArtifact("last_receipt"))

        // Contact-name resolution for place_call and message_send
        val callMom = executor.execute(
            ToolCallRequest("place_call", mapOf("contact_id" to "Mom", "speakerphone" to "true"))
        )
        assertTrue(callMom.summary.contains("5551234567"))

        val textMom = executor.execute(
            ToolCallRequest("message_send", mapOf("contact_id" to "Mom", "text" to "On my way", "channel" to "whatsapp"))
        )
        assertTrue(textMom.summary.contains("Mom"))

        // Named routines execute distinct workflows
        val focusRes = executor.execute(ToolCallRequest("get_routine_steps", mapOf("routine_name" to "focus")))
        assertTrue(focusRes.success)
        assertTrue(focusRes.summary.contains("25-minute", ignoreCase = true))

        val morningRes = executor.execute(ToolCallRequest("get_routine_steps", mapOf("routine_name" to "morning")))
        assertTrue(morningRes.success)
        assertNotNull(executor.getTurnArtifact("morning_briefing"))

        val relVolRes = executor.execute(ToolCallRequest("set_level", mapOf("target" to "volume", "percent" to "+15")))
        assertTrue(relVolRes.success)
        assertTrue(relVolRes.summary.contains("Volume set to"))

        fts.close()
    }

    @Test
    fun `recorded routine store persists custom conditional steps and indexes into fts`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RecordedRoutineStore(context)
        val fts = LocalFtsIndexHelper(context)

        val steps = listOf(
            RoutineStep(
                id = "s1",
                actionType = RoutineActionType.IF_CONDITION,
                title = "Check ad skip button",
                delayBeforeMs = 250L,
                condition = RoutineCondition(
                    type = ConditionType.SCREEN_CONTAINS_TEXT,
                    expectedValue = "Skip",
                    elseBehavior = ElseBehavior.SKIP_NEXT
                )
            ),
            RoutineStep(
                id = "s2",
                actionType = RoutineActionType.TAP,
                title = "Tap Skip",
                x = 920f,
                y = 1850f,
                holdDurationMs = 95L,
                delayBeforeMs = 420L,
                targetLabel = "Skip",
                useExactCoordinates = false
            ),
            RoutineStep(
                id = "s3",
                actionType = RoutineActionType.SWIPE,
                title = "Swipe Up (350ms)",
                x = 540f,
                y = 1600f,
                endX = 540f,
                endY = 600f,
                holdDurationMs = 350L,
                delayBeforeMs = 1100L,
                useExactCoordinates = true,
                condition = RoutineCondition(
                    type = ConditionType.BATTERY_AT_LEAST,
                    expectedValue = "20",
                    elseBehavior = ElseBehavior.STOP_ROUTINE
                )
            )
        )

        // JSON codec round-trip preserves exact delays, coordinates, hold duration, and conditionals
        val decoded = RoutineJsonCodec.stepsFromJson(RoutineJsonCodec.stepsToJson(steps))
        assertEquals(3, decoded.size)
        assertEquals(420L, decoded[1].delayBeforeMs)
        assertEquals(95L, decoded[1].holdDurationMs)
        assertEquals(ConditionType.SCREEN_CONTAINS_TEXT, decoded[0].condition?.type)
        assertEquals(ElseBehavior.STOP_ROUTINE, decoded[2].condition?.elseBehavior)

        val savedPerApp = store.saveRoutine(
            RecordedRoutine(
                id = "rt_yt_skip",
                name = "YouTube Skip Intro",
                description = "Skips intro and scrolls feed",
                scope = RoutineScope.PER_APP,
                targetPackage = "com.google.android.youtube",
                targetAppName = "YouTube",
                steps = steps
            )
        )
        store.syncRoutineIntoFts(savedPerApp, fts)

        val matchedByApp = store.findByName("skip intro", appFilter = "YouTube")
        assertNotNull(matchedByApp)
        assertEquals("rt_yt_skip", matchedByApp?.id)
        assertTrue(matchedByApp!!.hasConditionals())

        val ftsHits = fts.search("YouTube Skip Intro", "routines")
        assertTrue(ftsHits.any { it.docId == "rt_rt_yt_skip" })

        store.deleteRoutine("rt_yt_skip")
        assertNull(store.findById("rt_yt_skip"))

        fts.close()
        store.close()
    }

    @Test
    fun `routine condition evaluator covers screen battery connectivity and overnight time`() {
        val snap = ConditionEvalSnapshot(
            screenTexts = listOf("Home", "Skip Ad", "Subscribe"),
            foregroundPackage = "com.google.android.youtube",
            batteryPercent = 45,
            wifiOn = true,
            bluetoothOn = false,
            currentTimeHHmm = "23:15"
        )

        assertTrue(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.SCREEN_CONTAINS_TEXT, "skip ad"),
                snap
            )
        )
        assertFalse(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.SCREEN_MISSING_TEXT, "Skip Ad"),
                snap
            )
        )
        assertTrue(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.SCREEN_MISSING_TEXT, "Buy Now"),
                snap
            )
        )
        assertTrue(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.APP_IN_FOREGROUND, "youtube"),
                snap
            )
        )
        assertTrue(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.BATTERY_AT_LEAST, "40"),
                snap
            )
        )
        assertFalse(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.BATTERY_BELOW, "40"),
                snap
            )
        )
        assertTrue(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.WIFI_IS_ON, "true"),
                snap
            )
        )
        assertFalse(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.BLUETOOTH_IS_ON, "true"),
                snap
            )
        )
        assertTrue(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.TIME_BETWEEN, "22:00", "06:00"),
                snap
            )
        )
        assertFalse(
            RoutineExecutionEngine.evaluateCondition(
                RoutineCondition(ConditionType.TIME_BETWEEN, "09:00", "17:00"),
                snap
            )
        )
    }

    @Test
    fun `routine recorder manager captures taps swipes conditions typed text and saves per app`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)

        RoutineRecorderManager.startRecording(
            scope = RoutineScope.PER_APP,
            targetAppName = "YouTube",
            targetPackage = "com.google.android.youtube",
            draftName = "Morning Queue"
        )
        assertTrue(RoutineRecorderManager.isRecording())

        RoutineRecorderManager.recordManualTap(
            x = 400f,
            y = 220f,
            holdDurationMs = 80L,
            labelHint = "Search"
        )
        RoutineRecorderManager.onAccessibilityTextCaptured("Search", "lo", "com.google.android.youtube")
        RoutineRecorderManager.onAccessibilityTextCaptured("Search", "lo-fi beats", "com.google.android.youtube")
        RoutineRecorderManager.recordManualSwipe(
            startX = 500f,
            startY = 1500f,
            endX = 500f,
            endY = 600f,
            durationMs = 300L
        )
        RoutineRecorderManager.recordConditionStep(
            RoutineCondition(ConditionType.SCREEN_CONTAINS_TEXT, "Play", elseBehavior = ElseBehavior.STOP_ROUTINE)
        )

        val currentSteps = RoutineRecorderManager.state.value.capturedSteps
        // Text input coalesces consecutive keystrokes into one step, giving 4 distinct steps
        assertEquals(4, currentSteps.size)
        assertEquals(RoutineActionType.TAP, currentSteps[0].actionType)
        assertEquals(RoutineActionType.TYPE_TEXT, currentSteps[1].actionType)
        assertEquals("lo-fi beats", currentSteps[1].textValue)
        assertEquals(RoutineActionType.SWIPE, currentSteps[2].actionType)
        assertEquals(RoutineActionType.IF_CONDITION, currentSteps[3].actionType)

        // Accessibility tap from another package must be filtered out while PER_APP scoped
        RoutineRecorderManager.startRecording(
            scope = RoutineScope.PER_APP,
            targetAppName = "YouTube",
            targetPackage = "com.google.android.youtube",
            draftName = "Scoped Test"
        )
        RoutineRecorderManager.onAccessibilityTapCaptured(100f, 100f, "WrongApp", "com.other.app")
        assertTrue(RoutineRecorderManager.state.value.capturedSteps.isEmpty())

        RoutineRecorderManager.recordManualTap(300f, 500f, labelHint = "Home")
        val saved = RoutineRecorderManager.stopAndSaveRecording(context, "Scoped YT Tap", fts)
        assertNotNull(saved)
        assertEquals("Scoped YT Tap", saved?.name)
        assertEquals(RoutineScope.PER_APP, saved?.scope)
        assertEquals(1, saved?.steps?.size)

        RoutineRecorderManager.hideFloatingControl()
        val store = RecordedRoutineStore(context)
        saved?.id?.let { store.deleteRoutine(it) }
        store.close()
        fts.close()
    }

    @Test
    fun `routine skill fast paths and executor handlers support ai callable routines`() = runTest {
        val routineSkill = SkillCatalog.findById("compound-routine-orchestrator")
        assertNotNull(routineSkill)
        assertTrue(routineSkill!!.tools.any { it.name == "run_recorded_routine" })
        assertTrue(routineSkill.tools.any { it.name == "control_routine_recorder" })

        val showFloating = RegexFastPathEngine.match("show floating routine control")
        assertEquals("control_routine_recorder", showFloating?.toolCall?.toolName)
        assertEquals("show_floating", showFloating?.toolCall?.args?.get("action"))

        val hideFloating = RegexFastPathEngine.match("hide floating routine overlay")
        assertEquals("control_routine_recorder", hideFloating?.toolCall?.toolName)
        assertEquals("hide_floating", hideFloating?.toolCall?.args?.get("action"))

        val recordForApp = RegexFastPathEngine.match("record a routine for YouTube")
        assertEquals("control_routine_recorder", recordForApp?.toolCall?.toolName)
        assertEquals("start", recordForApp?.toolCall?.args?.get("action"))
        assertEquals("YouTube", recordForApp?.toolCall?.args?.get("target_scope"))

        val listRoutines = RegexFastPathEngine.match("list my routines")
        assertEquals("control_routine_recorder", listRoutines?.toolCall?.toolName)
        assertEquals("list", listRoutines?.toolCall?.args?.get("action"))

        val stopRoutine = RegexFastPathEngine.match("stop recording routine")
        assertEquals("control_routine_recorder", stopRoutine?.toolCall?.toolName)
        assertEquals("stop", stopRoutine?.toolCall?.args?.get("action"))

        val runRecorded = RegexFastPathEngine.match("run routine Skip Intro")
        assertEquals("run_recorded_routine", runRecorded?.toolCall?.toolName)
        assertEquals("Skip Intro", runRecorded?.toolCall?.args?.get("routine_name"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val fts = LocalFtsIndexHelper(context)
        val store = RecordedRoutineStore(context)
        store.saveRoutine(
            RecordedRoutine(
                id = "rt_hw_check",
                name = "Battery Guarded Torch",
                scope = RoutineScope.GLOBAL,
                steps = listOf(
                    RoutineStep(
                        actionType = RoutineActionType.IF_CONDITION,
                        title = "Battery check",
                        delayBeforeMs = 0L,
                        condition = RoutineCondition(
                            type = ConditionType.BATTERY_BELOW,
                            expectedValue = "1",
                            elseBehavior = ElseBehavior.STOP_ROUTINE
                        )
                    ),
                    RoutineStep(
                        actionType = RoutineActionType.HARDWARE_TOGGLE,
                        title = "Torch",
                        targetLabel = "flashlight",
                        textValue = "on",
                        delayBeforeMs = 0L
                    )
                )
            )
        )

        val executor = DeviceActionExecutor(context, fts, routineStore = store) { _, _, _, _ -> }

        val listReport = executor.execute(
            ToolCallRequest("control_routine_recorder", mapOf("action" to "list"))
        )
        assertTrue(listReport.success)
        assertTrue(listReport.summary.contains("Battery Guarded Torch"))

        // Conditional false triggers STOP_ROUTINE cleanly without requiring Accessibility
        val condRun = executor.execute(
            ToolCallRequest("run_recorded_routine", mapOf("routine_name" to "Battery Guarded Torch"))
        )
        assertTrue(condRun.success)
        assertTrue(condRun.summary.contains("stopped safely", ignoreCase = true))

        // Unknown routine explains nicely
        val missingRun = executor.execute(
            ToolCallRequest("run_recorded_routine", mapOf("routine_name" to "Nonexistent Flow"))
        )
        assertFalse(missingRun.success)
        assertTrue(missingRun.summary.contains("Battery Guarded Torch"))

        // Opening floating control while Accessibility is disabled guides user cleanly
        val accBlocked = executor.execute(
            ToolCallRequest("control_routine_recorder", mapOf("action" to "show_floating"))
        )
        assertFalse(accBlocked.success)
        assertTrue(accBlocked.summary.contains("Accessibility", ignoreCase = true))

        store.deleteRoutine("rt_hw_check")
        store.close()
        fts.close()
    }

    @Test
    fun `question responses trigger 10s follow up listening and time out politely`() {
        assertEquals(10_000L, NovaViewModel.FOLLOW_UP_TIMEOUT_MS)

        // Detector covers questions, disambiguation choices, confirmations, and ignores statements / setup prompts
        val questionOutcome = TurnExecutionOutcome(
            normalizedQuery = "find wifi",
            routingTier = RoutingTier.TIER_1_REGEX,
            selectedSkills = listOf("local-search-rag"),
            latencyMs = 4L,
            thinkingEnabled = false,
            steps = emptyList(),
            responseText = "First, Wifi Home. Second, Wifi Office. Which one would you like?"
        )
        assertTrue(NovaPipelineRouter.shouldAwaitFollowUp(questionOutcome, hasPendingChoices = true))

        val confirmOutcome = TurnExecutionOutcome(
            normalizedQuery = "tap pay now",
            routingTier = RoutingTier.TIER_1_REGEX,
            selectedSkills = listOf("local-screen-control"),
            latencyMs = 5L,
            thinkingEnabled = false,
            steps = emptyList(),
            responseText = "Just to confirm: should I tap 'Pay Now'?",
            pendingConfirmation = TierRedConfirmation(
                id = "c1",
                title = "Confirm",
                summary = "Just to confirm: should I tap 'Pay Now'?",
                targetApp = "Shop",
                targetNodeId = "n01",
                riskTag = "CONFIRMATION REQUIRED",
                pipScreenNodes = emptyList(),
                pendingToolCall = ToolCallRequest("ui_act", mapOf("action" to "CLICK", "node_id" to "n01"), safetyTier = SafetyTier.RED)
            ),
            orbMode = OrbVisualMode.CONFIRMATION_PAUSED
        )
        assertTrue(NovaPipelineRouter.shouldAwaitFollowUp(confirmOutcome))

        val statementOutcome = TurnExecutionOutcome(
            normalizedQuery = "turn on flashlight",
            routingTier = RoutingTier.TIER_1_REGEX,
            selectedSkills = listOf("hardware-toggle-controller"),
            latencyMs = 2L,
            thinkingEnabled = false,
            steps = emptyList(),
            responseText = "Flashlight turned ON."
        )
        assertFalse(NovaPipelineRouter.shouldAwaitFollowUp(statementOutcome))

        val setupOutcome = statementOutcome.copy(
            responseText = "Please download the model?",
            requiresModelSetup = true
        )
        assertFalse(NovaPipelineRouter.shouldAwaitFollowUp(setupOutcome))

        // ViewModel behavior: asking a question enters follow-up listening and falls back to "Let me know what you need."
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = NovaViewModel(app)
        vm.setFollowUpTimeoutForTest(80L)

        vm.submitCommand("hey nova", speakResponse = false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "Expected Nova to await user reply after wake-only prompt",
            vm.uiState.value.voiceState.isAwaitingFollowUp
        )
        assertEquals(OrbVisualMode.LISTENING, vm.uiState.value.voiceState.orbMode)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(120L, TimeUnit.MILLISECONDS)
        assertFalse(vm.uiState.value.voiceState.isAwaitingFollowUp)
        assertEquals(OrbVisualMode.IDLE, vm.uiState.value.voiceState.orbMode)
        assertEquals("STANDING BY", vm.uiState.value.voiceState.statusHeader)
        assertEquals("Let me know what you need.", vm.uiState.value.voiceState.assistantResponse)

        // Submitting a new command during follow-up cancels timeout cleanly
        vm.submitCommand("hey nova", speakResponse = false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.uiState.value.voiceState.isAwaitingFollowUp)
        vm.submitCommand("turn on flashlight", speakResponse = false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(120L, TimeUnit.MILLISECONDS)
        assertFalse(vm.uiState.value.voiceState.isAwaitingFollowUp)
        assertFalse(vm.uiState.value.voiceState.assistantResponse == "Let me know what you need.")

        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
    }

    @Test
    fun `thinking toggle defaults on persists off and gates router thinking behavior`() = runTest {
        assertTrue(ProcessHealthState().thinkingEnabled)

        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = NovaViewModel(app)
        Thread.sleep(60L)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.uiState.value.processHealth.thinkingEnabled)

        vm.setThinkingEnabled(false)
        Thread.sleep(60L)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.uiState.value.processHealth.thinkingEnabled)
        assertTrue(vm.uiState.value.statusToast?.contains("OFF", ignoreCase = true) == true)

        vm.setThinkingEnabled(true)
        Thread.sleep(60L)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.uiState.value.processHealth.thinkingEnabled)
        assertTrue(vm.uiState.value.statusToast?.contains("ON", ignoreCase = true) == true)

        // Router respects thinkingAllowed = false even before model missing short-circuit or with fast paths
        val modelManager = LlmModelManager(app)
        modelManager.deleteActiveModel(ModelSlot.LLM)
        val fts = LocalFtsIndexHelper(app)
        val bridge = LlmProcessBridge(app, modelManager)
        val executor = DeviceActionExecutor(app, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)
        val outcome = router.routeAndExecute("turn on bluetooth", thinkingAllowed = false) { _, _, _ -> }
        assertFalse(outcome.thinkingEnabled)

        fts.close()
        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
    }

    @Test
    fun `floating overlay focus suppresses recording and recorded taps preserve exact coordinates`() {
        RoutineRecorderManager.startRecording(
            scope = RoutineScope.GLOBAL,
            draftName = "Focus & Precision Test"
        )

        // While floating overlay is focused or recently interacted with, Accessibility events must NOT record steps
        RoutineRecorderManager.setFloatingOverlayFocused(true)
        assertTrue(RoutineRecorderManager.isOverlaySuppressingCapture())
        RoutineRecorderManager.onAccessibilityTapCaptured(120f, 90f, "Record", "com.android.systemui")
        RoutineRecorderManager.onAccessibilityScrollCaptured(200f, 800f, 200f, 300f, "com.google.android.youtube")
        RoutineRecorderManager.onAccessibilityAppSwitched("Settings", "com.android.settings")
        assertTrue(RoutineRecorderManager.state.value.capturedSteps.isEmpty())

        // Even while dispatch forwarding is active, Accessibility must not double-record
        RoutineRecorderManager.setFloatingOverlayFocused(false, releaseCooldownMs = 250L)
        RoutineRecorderManager.setManualDispatchInProgress(true)
        assertTrue(RoutineRecorderManager.isOverlaySuppressingCapture())
        RoutineRecorderManager.onAccessibilityTapCaptured(540f, 1200f, "Like", "com.google.android.youtube")
        assertTrue(RoutineRecorderManager.state.value.capturedSteps.isEmpty())
        RoutineRecorderManager.setManualDispatchInProgress(false, settleCooldownMs = 300L)

        // Advance Robolectric SystemClock past suppression cooldown
        ShadowSystemClock.advanceBy(Duration.ofMillis(700L))
        assertFalse(RoutineRecorderManager.isOverlaySuppressingCapture())

        // Manual precision capture with a containing label MUST preserve useExactCoordinates = true
        RoutineRecorderManager.recordManualTap(
            x = 412f,
            y = 1185f,
            holdDurationMs = 75L,
            labelHint = "Outer Video Card",
            packageHint = "com.google.android.youtube"
        )
        // Accessibility tap with valid bounds also defaults useExactCoordinates = true
        ShadowSystemClock.advanceBy(Duration.ofMillis(500L))
        RoutineRecorderManager.onAccessibilityTapCaptured(
            x = 780f,
            y = 1420f,
            label = "Subscribe",
            pkg = "com.google.android.youtube"
        )
        // Accessibility tap without valid bounds falls back to label matching (useExactCoordinates = false)
        ShadowSystemClock.advanceBy(Duration.ofMillis(250L))
        RoutineRecorderManager.onAccessibilityTapCaptured(
            x = 0f,
            y = 0f,
            label = "Share",
            pkg = "com.google.android.youtube"
        )

        val steps = RoutineRecorderManager.state.value.capturedSteps
        assertEquals(3, steps.size)
        assertTrue(steps[0].useExactCoordinates)
        assertEquals(412f, steps[0].x)
        assertEquals(1185f, steps[0].y)
        assertEquals("Outer Video Card", steps[0].targetLabel)

        assertTrue(steps[1].useExactCoordinates)
        assertEquals(780f, steps[1].x)
        assertEquals(1420f, steps[1].y)

        assertFalse(steps[2].useExactCoordinates)
        assertEquals("Share", steps[2].targetLabel)

        RoutineRecorderManager.hideFloatingControl()
    }

    @Test
    fun `hidden readme snapshot audits all skills permissions specs and toggles cleanly`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = NovaViewModel(app)

        assertFalse(vm.uiState.value.showHiddenReadme)
        vm.setHiddenReadmeVisible(true)
        assertTrue(vm.uiState.value.showHiddenReadme)

        val snapshot = NovaSystemDocumentation.buildSnapshot(app, vm.uiState.value)
        assertEquals(18, snapshot.skills.size)
        assertEquals(17, snapshot.skills.count { it.enabled })
        assertEquals(1, snapshot.skills.count { !it.enabled })
        assertTrue(snapshot.appSpecs.any { it.label == "Application ID" && it.value.isNotBlank() })
        assertTrue(snapshot.runtimeSpecs.any { it.label.contains("Process A") })
        assertTrue(snapshot.pipelineSpecs.any { it.label.contains("RegexFastPathEngine") })
        assertTrue(snapshot.accessItems.any { it.permissionOrService.contains("BIND_ACCESSIBILITY_SERVICE") })
        assertTrue(snapshot.accessItems.any { it.permissionOrService == android.Manifest.permission.RECORD_AUDIO })
        assertTrue(snapshot.accessItems.any { it.permissionOrService.contains("BIND_NOTIFICATION_LISTENER_SERVICE") })
        assertTrue(snapshot.queriedPackages.contains("com.spotify.music"))
        assertTrue(snapshot.features.isNotEmpty())

        val markdown = NovaSystemDocumentation.buildMarkdownExport(snapshot)
        assertTrue(markdown.contains("# Nova — Internal Specs, Access & Skills README"))
        assertTrue(markdown.contains("## App Specifications"))
        assertTrue(markdown.contains("## OS Access & Permissions Audit"))
        assertTrue(markdown.contains("## Skills Catalog (17 Active / 18 Total)"))

        vm.setHiddenReadmeVisible(false)
        assertFalse(vm.uiState.value.showHiddenReadme)

        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
    }

    @Test
    fun `notification filter and reader never speak raw com google package names`() = runTest {
        val rawPackageMessage = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "msg_raw_pkg",
                packageName = "com.google.android.apps.messaging",
                ownPackageName = "com.example",
                appLabel = "com.google.android.apps.messaging",
                category = Notification.CATEGORY_MESSAGE,
                title = "Alice",
                text = "See you at 5 via com.google.android.calendar",
                flags = 0
            )
        )
        assertNotNull(rawPackageMessage)
        assertEquals("Messages", rawPackageMessage!!.appLabel)
        assertFalse(rawPackageMessage.shortLabel.contains("com.google"))
        assertFalse(rawPackageMessage.spokenPrompt.contains("com.google"))
        assertTrue(rawPackageMessage.spokenPrompt.contains("Messages"))
        assertTrue(rawPackageMessage.body.contains("Google Calendar"))

        val app = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(app)
        fts.upsertDocument(
            docId = "stale_pkg_notif",
            category = "messages",
            title = "[Message] com.google.android.apps.messaging • Bob",
            body = "Reminder from com.google.android.apps.tasks",
            uriOrRef = "notif://com.google.android.apps.messaging/99"
        )
        val executor = DeviceActionExecutor(app, fts) { _, _, _, _ -> }
        val report = executor.execute(ToolCallRequest("read_recent_messages", emptyMap(), safetyTier = SafetyTier.GREEN))
        assertTrue(report.success)
        assertFalse(report.summary.contains("com.google"))
        assertTrue(report.summary.contains("Messages"))
        assertTrue(report.summary.contains("Google Tasks"))
        fts.close()
    }

    @Test
    fun `follow up listening waits until tts finishes speaking reply`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val modelManager = LlmModelManager(app)
        modelManager.deleteActiveModel(ModelSlot.LLM)

        val vm = NovaViewModel(app)
        vm.setFollowUpTimeoutForTest(120L)

        // Seed 2 matching notes so "search notes for Wifi" triggers a multiple-choice question awaiting follow-up
        val fts = LocalFtsIndexHelper(app)
        fts.upsertDocument("doc_w1", "notes", "Wifi Home", "Network Alpha 1234", "memory://wifi1")
        fts.upsertDocument("doc_w2", "notes", "Wifi Office", "Network Beta 5678", "memory://wifi2")

        var capturedOnDone: (() -> Unit)? = null
        var spokenReply = ""
        vm.testSpeakDispatcher = { text, onDone ->
            spokenReply = text
            capturedOnDone = onDone
            true
        }

        vm.submitCommand("what is my saved Wifi", speakResponse = true)

        val deadline = System.currentTimeMillis() + 3000L
        while (capturedOnDone == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

        assertNotNull(capturedOnDone)
        assertTrue(spokenReply.contains("Which one", ignoreCase = true))
        // Crucially: while TTS is speaking, follow-up listening MUST NOT have started yet
        assertTrue(vm.uiState.value.voiceState.isSpeakingTts)
        assertFalse(vm.uiState.value.voiceState.isAwaitingFollowUp)
        assertEquals(0L, vm.uiState.value.voiceState.followUpListenTrigger)

        Thread.sleep(60L)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.uiState.value.voiceState.isAwaitingFollowUp)

        // Once TTS completes its utterance callback, follow-up listening activates
        capturedOnDone!!.invoke()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(vm.uiState.value.voiceState.isSpeakingTts)
        assertTrue(vm.uiState.value.voiceState.isAwaitingFollowUp)
        assertTrue(vm.uiState.value.voiceState.followUpListenTrigger > 0L)

        vm.cancelFollowUpWindow()
        fts.close()
        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
    }

    @Test
    fun `screen search choices extraction and voice ordinal selection clicks target item`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val ytmComponent = android.content.ComponentName("com.google.android.apps.youtube.music", "com.google.android.apps.youtube.music.MainActivity")
        val ytmAppInfo = android.content.pm.ApplicationInfo().apply {
            packageName = "com.google.android.apps.youtube.music"
            nonLocalizedLabel = "YouTube Music"
        }
        val ytmPkgInfo = android.content.pm.PackageInfo().apply {
            packageName = "com.google.android.apps.youtube.music"
            applicationInfo = ytmAppInfo
        }
        shadowPm.installPackage(ytmPkgInfo)
        shadowPm.addActivityIfNotPresent(ytmComponent)
        val mainQueryIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            setPackage("com.google.android.apps.youtube.music")
        }
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.google.android.apps.youtube.music"
                name = "com.google.android.apps.youtube.music.MainActivity"
                applicationInfo = ytmAppInfo
                nonLocalizedLabel = "YouTube Music"
            }
        }
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(mainQueryIntent, resolveInfo)

        val controller = Robolectric.buildService(NovaAccessibilityService::class.java).create()
        val svc = controller.get()
        val onConnMethod = NovaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
        onConnMethod.isAccessible = true
        onConnMethod.invoke(svc)

        val root = android.view.accessibility.AccessibilityNodeInfo()
        root.packageName = "com.google.android.apps.youtube.music"
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        val searchBox = android.view.accessibility.AccessibilityNodeInfo()
        searchBox.text = "Search"
        searchBox.className = "android.widget.EditText"
        searchBox.isEditable = true
        searchBox.isClickable = true
        searchBox.isVisibleToUser = true
        Shadows.shadowOf(searchBox).setOnPerformActionListener { _, _ -> true }

        // Nav chrome that must be excluded from choices
        val homeTab = android.view.accessibility.AccessibilityNodeInfo()
        homeTab.text = "Home"
        homeTab.className = "android.widget.Button"
        homeTab.isClickable = true
        homeTab.isVisibleToUser = true

        val upgradeBtn = android.view.accessibility.AccessibilityNodeInfo()
        upgradeBtn.text = "Upgrade"
        upgradeBtn.className = "android.widget.Button"
        upgradeBtn.isClickable = true
        upgradeBtn.isVisibleToUser = true

        val song1 = android.view.accessibility.AccessibilityNodeInfo()
        song1.text = "Bohemian Rhapsody - Queen"
        song1.className = "android.view.ViewGroup"
        song1.isClickable = true
        song1.isVisibleToUser = true
        var song1Clicked = false
        Shadows.shadowOf(song1).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) song1Clicked = true
            true
        }

        val song2 = android.view.accessibility.AccessibilityNodeInfo()
        song2.text = "Bohemian Rhapsody Live Aid"
        song2.className = "android.view.ViewGroup"
        song2.isClickable = true
        song2.isVisibleToUser = true
        var song2Clicked = false
        Shadows.shadowOf(song2).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) song2Clicked = true
            true
        }

        Shadows.shadowOf(root).addChild(searchBox)
        Shadows.shadowOf(root).addChild(homeTab)
        Shadows.shadowOf(root).addChild(upgradeBtn)
        Shadows.shadowOf(root).addChild(song1)
        Shadows.shadowOf(root).addChild(song2)
        Shadows.shadowOf(svc).setRootInActiveWindow(root)

        val scanned = svc.scanActiveScreenNodes("com.google.android.apps.youtube.music")
        val choices = svc.extractSelectableScreenChoices(scanned, maxChoices = 4)
        assertEquals(2, choices.size)
        assertEquals("Bohemian Rhapsody - Queen", choices[0].label)
        assertEquals("Bohemian Rhapsody Live Aid", choices[1].label)

        // Wire choices as pending choices in NovaPipelineRouter and select via natural voice ("open the second one")
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        val searchExec = executor.execute(
            ToolCallRequest(
                toolName = "screen_control_app",
                args = mapOf("app_name" to "YouTube Music", "goal" to "search", "query_text" to "bohemian rhapsody"),
                safetyTier = SafetyTier.GREEN
            )
        )
        assertTrue(searchExec.success)
        assertEquals(2, searchExec.ftsResults.size)

        val searchOutcome = router.routeAndExecute("search youtube music for bohemian rhapsody") { _, _, _ -> }
        assertTrue(router.getPendingChoices().size == 2)
        assertTrue(searchOutcome.responseText.contains("Which one", ignoreCase = true))

        val followUpOutcome = router.routeAndExecute("open the second one") { _, _, _ -> }
        assertTrue(song2Clicked)
        assertFalse(song1Clicked)
        assertTrue(followUpOutcome.responseText.contains("Bohemian Rhapsody Live Aid"))

        controller.destroy()
        fts.close()
    }

    @Test
    fun `youtube music playback verifier clicks matching track when audio not active`() = runTest {
        val controller = Robolectric.buildService(NovaAccessibilityService::class.java).create()
        val svc = controller.get()
        val onConnMethod = NovaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
        onConnMethod.isAccessible = true
        onConnMethod.invoke(svc)

        val root = android.view.accessibility.AccessibilityNodeInfo()
        root.packageName = "com.google.android.apps.youtube.music"
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        val premiumBanner = android.view.accessibility.AccessibilityNodeInfo()
        premiumBanner.text = "Get Music Premium"
        premiumBanner.className = "android.widget.Button"
        premiumBanner.isClickable = true
        premiumBanner.isVisibleToUser = true

        val playTrackBtn = android.view.accessibility.AccessibilityNodeInfo()
        playTrackBtn.text = "Play Christmas In Our Hearts - Jose Mari Chan"
        playTrackBtn.className = "android.widget.Button"
        playTrackBtn.isClickable = true
        playTrackBtn.isVisibleToUser = true
        var clickedPlay = false
        Shadows.shadowOf(playTrackBtn).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) clickedPlay = true
            true
        }

        Shadows.shadowOf(root).addChild(premiumBanner)
        Shadows.shadowOf(root).addChild(playTrackBtn)
        Shadows.shadowOf(svc).setRootInActiveWindow(root)

        val result = svc.verifyAndTriggerMediaPlayback(
            targetPkg = "com.google.android.apps.youtube.music",
            queryText = "christmas in our hearts",
            appName = "YouTube Music"
        )
        assertTrue(result.success)
        assertTrue(clickedPlay)
        assertTrue(result.message.contains("Playing 'christmas in our hearts' in YouTube Music", ignoreCase = true))

        controller.destroy()
    }

    @Test
    fun `llm prompt enforces no screen access instructions and choice reading`() {
        val prompt = com.example.processB.LiteRtLmRuntimeWrapper.buildProgressivePrompt(
            query = "search youtube for cooking recipes",
            disclosedSkills = listOfNotNull(SkillCatalog.findById("app-search-and-action")),
            fewShotHints = emptyList()
        )
        assertTrue(prompt.contains("CRITICAL ACCESSIBILITY CONTEXT"))
        assertTrue(prompt.contains("NO access to the screen or touch controls"))
        assertTrue(prompt.contains("Which one would you like?"))
        assertTrue(prompt.contains("YOU are the user's hands and eyes"))
    }

    @Test
    fun `background or closed overlay delegates follow up mic capture to foreground service`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = NovaViewModel(app)
        vm.setFollowUpTimeoutForTest(5_000L)

        var serviceCaptureTimeoutMs = 0L
        var capturedCallback: ((String) -> Unit)? = null
        vm.testServiceFollowUpDispatcher = { timeoutMs, onResult ->
            serviceCaptureTimeoutMs = timeoutMs
            capturedCallback = onResult
            true
        }

        // Simulate external app (e.g., YouTube Music) active on screen while follow-up starts
        vm.setVoiceOverlayOpen(true)
        vm.setAppInForeground(false)

        vm.startFollowUpListenWindow(speakOnTimeout = false)
        assertTrue(vm.uiState.value.voiceState.isAwaitingFollowUp)
        assertTrue(vm.uiState.value.voiceState.useServiceFollowUpMic)
        assertEquals(5_000L, serviceCaptureTimeoutMs)
        assertNotNull(capturedCallback)

        // Simulate spoken reply arriving from ForegroundService mic capture
        capturedCallback!!.invoke("turn on flashlight")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(vm.uiState.value.voiceState.isAwaitingFollowUp)
        assertFalse(vm.uiState.value.voiceState.useServiceFollowUpMic)

        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
    }

    @Test
    fun `open bluetooth routes to bluetooth settings and never matches link to windows`() = runTest {
        val rawLinkProfile = AppCapabilityRegistry.InstalledAppProfile(
            appName = "Link to Windows",
            packageName = "com.microsoft.appmanager",
            categoryLabel = "System/Device",
            capabilities = listOf("launch"),
            howToUse = "launch_app"
        )
        val rawSettingsProfile = AppCapabilityRegistry.InstalledAppProfile(
            appName = "Settings",
            packageName = "com.android.settings",
            categoryLabel = "System/Device",
            capabilities = listOf("launch", "ui_screen_automation"),
            howToUse = "launch_app"
        )
        val deviceApps = listOf(rawLinkProfile, rawSettingsProfile)

        // Ensure "Link to Windows" does NOT have 2-letter stopword "to" in tags and "bluetooth" routes to Settings
        val enrichedLink = AppCapabilityRegistry.ensureEnrichedProfile(rawLinkProfile)
        assertFalse(enrichedLink.tags.contains("to"))
        val btMatch = AppCapabilityRegistry.findBestMatchFromProfiles(deviceApps, "bluetooth")
        assertEquals("com.android.settings", btMatch?.packageName)

        // Fast-path routes "open bluetooth" directly to hardware settings instead of app launch
        val fastMatch = RegexFastPathEngine.match("open bluetooth", deviceApps)
        assertNotNull(fastMatch)
        assertEquals("set_hardware_state", fastMatch?.toolCall?.toolName)
        assertEquals("bluetooth", fastMatch?.toolCall?.args?.get("feature"))
        assertEquals("settings", fastMatch?.toolCall?.args?.get("state"))

        // Even a direct launch_app("bluetooth") routes cleanly to ACTION_BLUETOOTH_SETTINGS
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val report = executor.execute(
            ToolCallRequest(
                toolName = "launch_app",
                args = mapOf("app_name" to "bluetooth"),
                safetyTier = SafetyTier.GREEN
            )
        )
        assertTrue(report.success)
        assertTrue(report.summary.contains("Bluetooth Settings", ignoreCase = true))
        val startedIntent = Shadows.shadowOf(context).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, startedIntent?.action)
        fts.close()
    }

    @Test
    fun `turn off bluetooth accessibility toggle targets master switch instead of secondary scanning switch`() = runTest {
        val controller = Robolectric.buildService(NovaAccessibilityService::class.java).create()
        val svc = controller.get()
        val onConnMethod = NovaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
        onConnMethod.isAccessible = true
        onConnMethod.invoke(svc)

        val root = android.view.accessibility.AccessibilityNodeInfo()
        root.packageName = "com.android.settings"
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        // Secondary switch that is already OFF (must NOT be mistaken as Bluetooth master OFF)
        val scanningRow = android.view.accessibility.AccessibilityNodeInfo()
        scanningRow.className = "android.widget.LinearLayout"
        val scanningLabel = android.view.accessibility.AccessibilityNodeInfo()
        scanningLabel.text = "Bluetooth scanning"
        val scanningSwitch = android.view.accessibility.AccessibilityNodeInfo()
        scanningSwitch.className = "android.widget.Switch"
        scanningSwitch.isCheckable = true
        scanningSwitch.isChecked = false
        scanningSwitch.isClickable = true
        var scanningClicked = false
        Shadows.shadowOf(scanningSwitch).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
                scanningClicked = true
                scanningSwitch.isChecked = !scanningSwitch.isChecked
            }
            true
        }
        Shadows.shadowOf(scanningRow).addChild(scanningLabel)
        Shadows.shadowOf(scanningRow).addChild(scanningSwitch)

        // Primary master switch "Use Bluetooth" currently ON
        val masterRow = android.view.accessibility.AccessibilityNodeInfo()
        masterRow.className = "android.widget.LinearLayout"
        val masterLabel = android.view.accessibility.AccessibilityNodeInfo()
        masterLabel.text = "Use Bluetooth"
        val masterSwitch = android.view.accessibility.AccessibilityNodeInfo()
        masterSwitch.className = "android.widget.Switch"
        masterSwitch.isCheckable = true
        masterSwitch.isChecked = true
        masterSwitch.isClickable = true
        var masterClicked = false
        Shadows.shadowOf(masterSwitch).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
                masterClicked = true
                masterSwitch.isChecked = false
            }
            true
        }
        Shadows.shadowOf(masterRow).addChild(masterLabel)
        Shadows.shadowOf(masterRow).addChild(masterSwitch)

        Shadows.shadowOf(root).addChild(scanningRow)
        Shadows.shadowOf(root).addChild(masterRow)
        Shadows.shadowOf(svc).setRootInActiveWindow(root)

        try {
            val toggledOff = svc.autoToggleSettingsSwitch(
                keywords = listOf("use bluetooth", "bluetooth"),
                desiredOn = false,
                targetPkg = "com.android.settings"
            )
            assertTrue(toggledOff)
            assertTrue(masterClicked)
            assertFalse(scanningClicked)
            assertFalse(masterSwitch.isChecked)
        } finally {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun `youtube and youtube music browser intent opens web url and checks results via accessibility`() = runTest {
        val searchMatch = RegexFastPathEngine.match("search youtube music via browser for jazz")
        assertNotNull(searchMatch)
        assertEquals("search_in_app", searchMatch?.toolCall?.toolName)
        assertEquals("true", searchMatch?.toolCall?.args?.get("use_browser"))

        val playMatch = RegexFastPathEngine.match("play bohemian rhapsody on youtube via browser")
        assertNotNull(playMatch)
        assertEquals("play_in_app", playMatch?.toolCall?.toolName)
        assertEquals("true", playMatch?.toolCall?.args?.get("use_browser"))

        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val chromePkg = "com.android.chrome"
        val chromeComponent = android.content.ComponentName(chromePkg, "com.google.android.apps.chrome.Main")
        val chromeAppInfo = android.content.pm.ApplicationInfo().apply {
            packageName = chromePkg
            nonLocalizedLabel = "Chrome"
        }
        shadowPm.installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = chromePkg
                applicationInfo = chromeAppInfo
            }
        )
        shadowPm.addActivityIfNotPresent(chromeComponent)
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = chromePkg
                name = "com.google.android.apps.chrome.Main"
                applicationInfo = chromeAppInfo
                nonLocalizedLabel = "Chrome"
            }
        }
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            setPackage(chromePkg)
        }
        val viewIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://music.youtube.com/search?q=jazz")
        ).apply {
            setPackage(chromePkg)
        }
        val playViewIntent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://www.youtube.com/results?search_query=bohemian%20rhapsody")
        ).apply {
            setPackage(chromePkg)
        }
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(launcherIntent, resolveInfo)
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(viewIntent, resolveInfo)
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(playViewIntent, resolveInfo)

        val controller = Robolectric.buildService(NovaAccessibilityService::class.java).create()
        val svc = controller.get()
        val onConnMethod = NovaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
        onConnMethod.isAccessible = true
        onConnMethod.invoke(svc)

        val root = android.view.accessibility.AccessibilityNodeInfo()
        root.packageName = chromePkg
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        val addrBar = android.view.accessibility.AccessibilityNodeInfo()
        addrBar.text = "Address bar"
        addrBar.isClickable = true
        addrBar.isVisibleToUser = true

        val res1 = android.view.accessibility.AccessibilityNodeInfo()
        res1.text = "Take Five - Dave Brubeck"
        res1.isClickable = true
        res1.isVisibleToUser = true

        val res2 = android.view.accessibility.AccessibilityNodeInfo()
        res2.text = "Blue in Green - Miles Davis"
        res2.isClickable = true
        res2.isVisibleToUser = true

        val resVideo = android.view.accessibility.AccessibilityNodeInfo()
        resVideo.text = "Bohemian Rhapsody Official Video"
        resVideo.isClickable = true
        resVideo.isVisibleToUser = true
        var clickedVideo = false
        Shadows.shadowOf(resVideo).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) clickedVideo = true
            true
        }

        Shadows.shadowOf(root).addChild(addrBar)
        Shadows.shadowOf(root).addChild(res1)
        Shadows.shadowOf(root).addChild(res2)
        Shadows.shadowOf(root).addChild(resVideo)
        Shadows.shadowOf(svc).setRootInActiveWindow(root)

        val fts = LocalFtsIndexHelper(context)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }

        try {
            // 1. Search YT Music via browser -> launches music.youtube.com & filters out "Address bar", reads track choices
            val searchReport = executor.execute(searchMatch!!.toolCall)
            assertTrue(searchReport.success)
            val searchStarted = Shadows.shadowOf(context).nextStartedActivity
            assertEquals("https://music.youtube.com/search?q=jazz", searchStarted?.dataString)
            assertTrue(searchReport.ftsResults.none { it.title.equals("Address bar", ignoreCase = true) })
            assertTrue(searchReport.ftsResults.any { it.title.contains("Take Five") })
            assertTrue(searchReport.summary.contains("Which one", ignoreCase = true))

            // 2. Play YouTube via browser -> launches youtube.com/results & clicks matching video via Accessibility
            val playReport = executor.execute(playMatch!!.toolCall)
            assertTrue(playReport.success)
            val playStarted = Shadows.shadowOf(context).nextStartedActivity
            assertEquals("https://www.youtube.com/results?search_query=bohemian%20rhapsody", playStarted?.dataString)
            assertTrue(clickedVideo)
        } finally {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
            fts.close()
        }
    }

    @Test
    fun `voice barge in filters tts self echo and interrupts assistant speech immediately`() {
        val currentTts = "Here are the top results for jazz. First, Take Five. Second, Blue in Green. Which one would you like?"

        // Self-echo from speakers must be ignored
        assertNull(
            NovaVoiceForegroundService.evaluateBargeInIntent(
                transcript = "Here are the top results for jazz",
                currentSpokenText = currentTts,
                isPartial = false
            )
        )

        // Explicit stop stops speaking immediately
        val stopDecision = NovaVoiceForegroundService.evaluateBargeInIntent("stop talking", currentTts, isPartial = true)
        assertEquals(NovaVoiceForegroundService.BargeInAction.STOP_ONLY, stopDecision?.action)

        // Wake or wait interrupts and opens listening
        val waitDecision = NovaVoiceForegroundService.evaluateBargeInIntent("wait", currentTts, isPartial = true)
        assertEquals(NovaVoiceForegroundService.BargeInAction.LISTEN_NOW, waitDecision?.action)

        val wakeCmdDecision = NovaVoiceForegroundService.evaluateBargeInIntent("hey nova turn off wifi", currentTts, isPartial = false)
        assertEquals(NovaVoiceForegroundService.BargeInAction.RUN_COMMAND, wakeCmdDecision?.action)
        assertEquals("turn off wifi", wakeCmdDecision?.command)

        // Direct non-echo command also interrupts and executes
        val directCmdDecision = NovaVoiceForegroundService.evaluateBargeInIntent("turn off bluetooth", currentTts, isPartial = false)
        assertEquals(NovaVoiceForegroundService.BargeInAction.RUN_COMMAND, directCmdDecision?.action)
        assertEquals("turn off bluetooth", directCmdDecision?.command)

        // Tap-to-interrupt in NovaViewModel immediately clears TTS speaking & opens listening window
        val app = ApplicationProvider.getApplicationContext<Application>()
        val modelManager = LlmModelManager(app)
        modelManager.deleteActiveModel(ModelSlot.LLM)
        val vm = NovaViewModel(app)
        vm.setFollowUpTimeoutForTest(5_000L)

        var capturedOnDone: (() -> Unit)? = null
        vm.testSpeakDispatcher = { _, onDone ->
            capturedOnDone = onDone
            true
        }
        vm.submitCommand("turn on flashlight", speakResponse = true)
        val deadline = System.currentTimeMillis() + 3000L
        while (capturedOnDone == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(vm.uiState.value.voiceState.isSpeakingTts)

        vm.interruptSpeaking(startListening = true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(vm.uiState.value.voiceState.isSpeakingTts)
        assertTrue(vm.uiState.value.voiceState.isAwaitingFollowUp)
        assertTrue(vm.uiState.value.voiceState.statusHeader.contains("INTERRUPTED"))

        vm.cancelFollowUpWindow()
        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
    }

    @Test
    fun `notification archive retains 14 days prunes older reads today first tracks read state and aligns briefing counts`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val now = System.currentTimeMillis()
        val dayMs = LocalFtsIndexHelper.DAY_MS

        // 1. Expired 16-day-old notification -> must be pruned and NOT counted
        val expiredAlert = ImportantNotificationFilter.ImportantNotificationAlert(
            id = "imp_expired_16d",
            key = "key_exp_16d",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            type = ImportantNotificationType.MESSAGE,
            title = "Ancient Sender",
            body = "From 16 days ago",
            timestampMs = now - 16L * dayMs,
            spokenPrompt = "",
            shortLabel = "WhatsApp • Ancient Sender",
            importanceScore = 60,
            importanceTier = ImportantNotificationFilter.NotificationImportanceTier.NORMAL
        )
        fts.upsertNotificationArchive(expiredAlert, nowMs = now)
        assertEquals(0, fts.runBackgroundNotificationAudit(now).totalSaved14Days)

        // 2. Old 3-day-old important missed call (unread) -> kept in 14-day archive + carry-over reminder
        val missed3d = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "key_missed_3d",
                packageName = "com.google.android.dialer",
                ownPackageName = context.packageName,
                appLabel = "Phone",
                category = Notification.CATEGORY_MISSED_CALL,
                title = "Mom",
                text = "Missed call from Mom",
                postTimeMs = now - 3L * dayMs
            )
        )!!
        fts.upsertNotificationArchive(missed3d, nowMs = now)

        // 3. Old 5-day-old already-read chat message -> kept in 14-day archive, NOT returned for "today"
        val oldRead5d = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "key_read_5d",
                packageName = "com.whatsapp",
                ownPackageName = context.packageName,
                appLabel = "WhatsApp",
                category = Notification.CATEGORY_MESSAGE,
                title = "Old Chat",
                text = "Already handled days ago",
                postTimeMs = now - 5L * dayMs
            )
        )!!
        fts.upsertNotificationArchive(oldRead5d, markRead = true, nowMs = now)

        // 4. Today's fresh message (unread, urgent -> flagged CRITICAL)
        val todayUrgent = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "key_today_msg",
                packageName = "com.google.android.apps.messaging",
                ownPackageName = context.packageName,
                appLabel = "Messages",
                category = Notification.CATEGORY_MESSAGE,
                title = "Alice",
                text = "Urgent: please confirm today's delivery code 4821",
                postTimeMs = now - 5 * 60_000L
            )
        )!!
        assertEquals(ImportantNotificationFilter.NotificationImportanceTier.CRITICAL, todayUrgent.importanceTier)
        fts.upsertNotificationArchive(todayUrgent, nowMs = now)

        val auditBefore = fts.runBackgroundNotificationAudit(now)
        assertEquals(3, auditBefore.totalSaved14Days) // missed3d + oldRead5d + todayUrgent
        assertEquals(2, auditBefore.unreadTotal)      // missed3d + todayUrgent
        assertEquals(1, auditBefore.unreadToday)      // ONLY todayUrgent
        assertEquals(1, auditBefore.last24hTotal)

        // Default "read notifications" reads ONLY today's notification + reminds of earlier unread important alert
        val todayReport = executor.execute(
            ToolCallRequest("read_recent_messages", mapOf("contact_id" to "all", "scope" to "today"), safetyTier = SafetyTier.GREEN)
        )
        assertTrue(todayReport.success)
        assertTrue(todayReport.summary.contains("Today's 1 unread"))
        assertTrue(todayReport.summary.contains("Alice"))
        assertFalse(todayReport.summary.contains("Old Chat"))
        assertFalse(todayReport.summary.contains("Ancient Sender"))
        assertTrue(todayReport.summary.contains("Reminder of an earlier unread important alert"))
        assertTrue(todayReport.summary.contains("Mom"))

        // After reading today's notification aloud, it must be tracked as READ!
        val auditAfterRead = fts.runBackgroundNotificationAudit(now)
        assertEquals(0, auditAfterRead.unreadToday)
        assertEquals(1, auditAfterRead.readToday)
        assertEquals(1, auditAfterRead.unreadTotal) // Mom missed call from 3 days ago still unread

        // Asking again immediately knows today's notification was ALREADY READ
        val repeatReport = executor.execute(
            ToolCallRequest("read_recent_messages", mapOf("contact_id" to "all", "scope" to "today"), safetyTier = SafetyTier.GREEN)
        )
        assertTrue(repeatReport.summary.contains("already read", ignoreCase = true))

        // Explicitly asking for this week's notifications still retrieves Mom & Old Chat from the 14-day archive
        val weekMatch = RegexFastPathEngine.match("read this week's notifications")
        assertEquals("week", weekMatch?.toolCall?.args?.get("scope"))
        val weekReport = executor.execute(weekMatch!!.toolCall)
        assertTrue(weekReport.summary.contains("Mom"))
        assertTrue(weekReport.summary.contains("Alice"))

        // Daily briefing reports exact 24h count (1), NOT inflated historical count (11), plus weekly context
        val briefingReport = executor.execute(
            ToolCallRequest("get_device_briefing", mapOf("topic" to "morning"), safetyTier = SafetyTier.GREEN)
        )
        assertTrue(briefingReport.success)
        assertTrue(briefingReport.summary.contains("In the last 24 hours: 1 important notification (0 unread, 1 already read)"))
        assertTrue(briefingReport.summary.contains("Past 7 days total: 3 alerts"))

        // Mark all read clears remaining unread count accurately
        val markAllMatch = RegexFastPathEngine.match("mark all notifications as read")
        assertEquals("mark_all_read", markAllMatch?.toolCall?.args?.get("action"))
        val markAllReport = executor.execute(markAllMatch!!.toolCall)
        assertTrue(markAllReport.success)
        assertEquals(0, fts.countUnreadNotifications())

        fts.close()
    }

    @Test
    fun `daily briefing switches to weekly summary when last 24h is quiet and reminds on unread critical carryover`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val now = System.currentTimeMillis()
        val dayMs = LocalFtsIndexHelper.DAY_MS

        val reminder4d = ImportantNotificationFilter.classify(
            RawNotificationSignals(
                key = "key_rem_4d",
                packageName = "com.google.android.calendar",
                ownPackageName = context.packageName,
                appLabel = "Google Calendar",
                category = Notification.CATEGORY_EVENT,
                title = "Doctor Appointment Payment",
                text = "Due this week — confirm invoice",
                postTimeMs = now - 4L * dayMs
            )
        )!!
        fts.upsertNotificationArchive(reminder4d, nowMs = now)

        val briefing = executor.execute(
            ToolCallRequest("get_device_briefing", mapOf("topic" to "morning"), safetyTier = SafetyTier.GREEN)
        )
        assertTrue(briefing.success)
        assertTrue(briefing.summary.contains("No new alerts in the last 24 hours"))
        assertTrue(briefing.summary.contains("Over the past week: 1 important notification"))
        assertTrue(briefing.summary.contains("Important reminder from"))
        assertTrue(briefing.summary.contains("Doctor Appointment Payment"))

        // Also test fast-path weekly recap phrasing
        val weeklyMatch = RegexFastPathEngine.match("what happened this week")
        assertEquals("get_device_briefing", weeklyMatch?.toolCall?.toolName)
        assertEquals("weekly", weeklyMatch?.toolCall?.args?.get("topic"))

        fts.close()
    }

    @Test
    fun `agentic memory extracts app preferences explicit corrections and user name from turns`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        var currentName = "NOVA USER"
        var currentPersona = AssistantPersonality.WARM_COMPANION
        val engine = AgenticMemoryEngine(
            ftsHelper = fts,
            personalityProvider = { currentPersona },
            userNameProvider = { currentName },
            onPersonalityChanged = { currentPersona = it },
            onUserNameChanged = { currentName = it }
        )

        val now = 1_760_000_000_000L

        // Turn 1: user plays music on Spotify -> learns music app preference + episode
        engine.learnFromTurn(
            rawQuery = "play jazz on Spotify",
            normalizedQuery = "play jazz on Spotify",
            subCommands = listOf("play jazz on Spotify"),
            toolCalls = listOf(
                ToolCallRequest("play_in_app", mapOf("app_name" to "Spotify", "query" to "jazz"), safetyTier = SafetyTier.GREEN)
            ),
            responseText = "Playing jazz on Spotify.",
            success = true,
            nowMs = now
        )
        assertEquals("Spotify", engine.getPreferredMusicApp())
        val episodes = fts.getAgenticMemories(types = setOf(AgenticMemoryType.EPISODE))
        assertTrue(episodes.isNotEmpty())

        // Turn 2: explicit user correction -> updates preferred music app to YouTube Music & stores CORRECTION
        engine.learnFromTurn(
            rawQuery = "no, use YouTube Music",
            normalizedQuery = "no, use YouTube Music",
            subCommands = listOf("no, use YouTube Music"),
            toolCalls = listOf(
                ToolCallRequest("launch_app", mapOf("app_name" to "YouTube Music"), safetyTier = SafetyTier.GREEN)
            ),
            responseText = "Opening YouTube Music.",
            success = true,
            nowMs = now + 10_000L
        )
        assertEquals("YouTube Music", engine.getPreferredMusicApp())
        val corrections = fts.getAgenticMemories(types = setOf(AgenticMemoryType.CORRECTION))
        assertTrue(corrections.any { it.content.contains("YouTube Music", ignoreCase = true) })

        // Turn 3: name & tone directive
        engine.learnFromTurn(
            rawQuery = "call me Alex",
            normalizedQuery = "call me Alex",
            subCommands = listOf("call me Alex"),
            toolCalls = emptyList(),
            responseText = "Okay, Alex.",
            success = true,
            nowMs = now + 20_000L
        )
        assertEquals("Alex", engine.friendlyUserName())
        assertEquals("ALEX", currentName)

        fts.close()
    }

    @Test
    fun `agentic memory learns sequential user workflows and recurring time of day habits`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val engine = AgenticMemoryEngine(ftsHelper = fts)

        // Pick 9:00 AM local time so bucket == MORNING
        val morningCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val t0 = morningCal.timeInMillis

        // Step A: check notifications
        engine.learnFromTurn(
            rawQuery = "read my notifications",
            normalizedQuery = "read my notifications",
            subCommands = listOf("read my notifications"),
            toolCalls = listOf(ToolCallRequest("read_recent_messages", mapOf("contact_id" to "all", "scope" to "today"), safetyTier = SafetyTier.GREEN)),
            responseText = "Today's 1 unread notification.",
            success = true,
            nowMs = t0
        )

        // Step B 2 minutes later: check calendar -> induces Morning Notifications & Schedule sequential process!
        engine.learnFromTurn(
            rawQuery = "check my calendar",
            normalizedQuery = "check my calendar",
            subCommands = listOf("check my calendar"),
            toolCalls = listOf(ToolCallRequest("query_calendar_events", emptyMap(), safetyTier = SafetyTier.GREEN)),
            responseText = "No upcoming calendar events.",
            success = true,
            nowMs = t0 + 120_000L
        )

        val morningProcs = fts.getLearnedProcesses(timeBucket = TimeOfDayBucket.MORNING)
        assertTrue(morningProcs.any { it.steps.size == 2 && it.steps.contains("read my notifications") && it.steps.contains("check my calendar") })

        // Repeat notifications on day 2 at 9am -> strengthens recurring habit process
        val tDay2 = t0 + LocalFtsIndexHelper.DAY_MS
        engine.learnFromTurn(
            rawQuery = "read my notifications",
            normalizedQuery = "read my notifications",
            subCommands = listOf("read my notifications"),
            toolCalls = listOf(ToolCallRequest("read_recent_messages", mapOf("contact_id" to "all", "scope" to "today"), safetyTier = SafetyTier.GREEN)),
            responseText = "Today's unread.",
            success = true,
            nowMs = tDay2
        )
        val afterDay2 = fts.getLearnedProcesses(timeBucket = TimeOfDayBucket.MORNING)
        assertTrue(afterDay2.any { it.processId.startsWith("proc_habit_") && it.timeBucket == TimeOfDayBucket.MORNING })

        fts.close()
    }

    @Test
    fun `personality modes personalize briefings offer learned workflow and run on yes follow up`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        var persona = AssistantPersonality.WARM_COMPANION
        var userName = "SAM"
        val engine = AgenticMemoryEngine(
            ftsHelper = fts,
            personalityProvider = { persona },
            userNameProvider = { userName },
            onPersonalityChanged = { persona = it },
            onUserNameChanged = { userName = it }
        )
        val controlUpdates = mutableListOf<Pair<String, Boolean?>>()
        val executor = DeviceActionExecutor(context, fts, agenticMemoryEngine = engine) { id, en, _, _ ->
            controlUpdates.add(id to en)
        }
        val bridge = LlmProcessBridge(context)
        val router = NovaPipelineRouter(bridge, executor, agenticMemoryEngine = engine)

        // 1. Switch personality to Witty Co-Pilot via voice command
        val switchOutcome = router.routeAndExecute("switch personality to witty") { _, _, _ -> }
        assertEquals(AssistantPersonality.WITTY_COPILOT, persona)
        assertTrue(switchOutcome.responseText.contains("Witty Co-Pilot"))

        // 2. Teach a workflow matching current time bucket with a subtle gap so it hasn't run today yet
        val bucketNow = TimeOfDayBucket.fromTimestamp(System.currentTimeMillis())
        fts.upsertLearnedProcess(
            processId = "proc_test_focus",
            name = "Start Focus",
            triggerPhrase = "start focus",
            steps = listOf("turn on flashlight", "turn off bluetooth"),
            timeBucket = bucketNow,
            baseConfidence = 0.85f,
            autoSuggest = true,
            nowMs = System.currentTimeMillis() - 3_600_000L
        )

        // 3. Ask for daily briefing -> personalized Witty Co-Pilot greeting for Sam + offers Start Focus workflow
        val briefingOutcome = router.routeAndExecute("daily briefing") { _, _, _ -> }
        assertTrue(briefingOutcome.responseText.contains("Sam"))
        assertTrue(briefingOutcome.responseText.contains("Juice level", ignoreCase = true) || briefingOutcome.responseText.contains("locked and loaded", ignoreCase = true))
        assertTrue(briefingOutcome.responseText.contains("Start Focus"))
        assertTrue(NovaPipelineRouter.shouldAwaitFollowUp(briefingOutcome))

        // 4. Reply "yes" -> executes the offered Start Focus workflow automatically
        val followUpOutcome = router.routeAndExecute("yes") { _, _, _ -> }
        assertTrue(followUpOutcome.responseText.contains("Ran 'Start Focus'"))
        assertTrue(controlUpdates.any { it.first == "ctrl_flashlight" && it.second == true })

        fts.close()
    }

    @Test
    fun `agentic memory inspect forget and llm prompt injection include personality and learned habits`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        var persona = AssistantPersonality.SHARP_EXECUTIVE
        val engine = AgenticMemoryEngine(
            ftsHelper = fts,
            personalityProvider = { persona },
            userNameProvider = { "JORDAN" },
            onPersonalityChanged = { persona = it }
        )
        val executor = DeviceActionExecutor(context, fts, agenticMemoryEngine = engine) { _, _, _, _ -> }

        // Teach process via FastPath tool call
        val teachMatch = RegexFastPathEngine.match("learn process desk setup: turn on wifi, turn off dnd")
        assertEquals("manage_agentic_memory", teachMatch?.toolCall?.toolName)
        val teachReport = executor.execute(teachMatch!!.toolCall)
        assertTrue(teachReport.success)
        assertTrue(teachReport.summary.contains("desk setup", ignoreCase = true))

        // Verify prompt builder injects both [Personality & Tone] and [Agentic Memory & Learned Processes]
        val prompt = LiteRtLmRuntimeWrapper.buildProgressivePrompt(
            query = "play relaxing music",
            disclosedSkills = SkillCatalog.enabledSkills.take(2),
            fewShotHints = emptyList(),
            memoryContext = engine.buildPromptMemoryContext("play relaxing music"),
            personalityProfile = engine.buildPersonalityProfileBlock()
        )
        assertTrue(prompt.contains("[Personality & Tone]"))
        assertTrue(prompt.contains("Sharp Executive"))
        assertTrue(prompt.contains("Jordan"))
        assertTrue(prompt.contains("[Agentic Memory & Learned Processes]"))
        assertTrue(prompt.contains("desk setup", ignoreCase = true))

        // Inspect summary reports active personality and learned workflows
        val inspectMatch = RegexFastPathEngine.match("what have you learned about me")
        assertEquals("inspect", inspectMatch?.toolCall?.args?.get("action"))
        val inspectReport = executor.execute(inspectMatch!!.toolCall)
        assertTrue(inspectReport.summary.contains("Sharp Executive"))
        assertTrue(inspectReport.summary.contains("desk setup", ignoreCase = true))

        // Forget by keyword removes the learned workflow cleanly
        val forgetMatch = RegexFastPathEngine.match("forget desk setup")
        assertEquals("forget", forgetMatch?.toolCall?.args?.get("action"))
        val forgetReport = executor.execute(forgetMatch!!.toolCall)
        assertTrue(forgetReport.success)
        assertEquals(0, fts.getLearnedProcesses().size)

        fts.close()
    }

    @Test
    fun `agentic memory audit fixes handle natural workflow triggers unknown personalities preferences and retry gating`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        var persona = AssistantPersonality.WARM_COMPANION
        val engine = AgenticMemoryEngine(
            ftsHelper = fts,
            personalityProvider = { persona },
            onPersonalityChanged = { persona = it }
        )
        val controlUpdates = mutableListOf<Pair<String, Boolean?>>()
        val executor = DeviceActionExecutor(context, fts, agenticMemoryEngine = engine) { id, en, _, _ ->
            controlUpdates.add(id to en)
        }
        val bridge = LlmProcessBridge(context)
        val router = NovaPipelineRouter(bridge, executor, agenticMemoryEngine = engine)

        // 1. Teach a workflow, then run it using "run my <name> process" AND ensure planner doesn't hijack "start my <name> process"
        val teachOutcome = router.routeAndExecute("learn process desk setup: turn on flashlight, turn off dnd") { _, _, _ -> }
        assertTrue(teachOutcome.responseText.contains("desk setup", ignoreCase = true))
        assertNull(
            com.example.engine.MultiStageActionPlanner.plan(
                com.example.engine.InputNormalizer.normalize("start my desk setup process and mute phone")
            )
        )
        val runOutcome = router.routeAndExecute("run my desk setup process") { _, _, _ -> }
        assertTrue(runOutcome.responseText.contains("Ran 'desk setup'", ignoreCase = true))
        assertTrue(controlUpdates.any { it.first == "ctrl_flashlight" && it.second == true })

        // 2. Empty delete keyword must NOT wipe tables
        assertEquals(0, fts.deleteAgenticMemoryByKeyword("   "))
        assertTrue(fts.getLearnedProcesses().isNotEmpty())

        // 3. Invalid personality must return helpful choices rather than silently defaulting to Warm Companion
        assertNull(AssistantPersonality.fromIdOrName("pirate"))
        val badPersona = router.routeAndExecute("switch personality to pirate") { _, _, _ -> }
        assertTrue(badPersona.responseText.contains("Warm Companion"))
        assertTrue(badPersona.responseText.contains("Witty Co-Pilot"))
        assertEquals(AssistantPersonality.WARM_COMPANION, persona)

        // 4. Explicit preference corrections execute deterministically without LLM & strip filler words
        val prefMusic = router.routeAndExecute("no, use YouTube Music instead please") { _, _, _ -> }
        assertTrue(prefMusic.responseText.contains("YouTube Music"))
        assertEquals("YouTube Music", engine.getPreferredMusicApp())

        val prefMaps = router.routeAndExecute("always use Waze please") { _, _, _ -> }
        assertTrue(prefMaps.responseText.contains("Waze"))
        assertEquals("Waze", engine.getPreferredMapsApp())

        // 5. Rapid retries (5 seconds apart) must NOT prematurely create a recurring single-step habit
        val morningCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 8)
            set(java.util.Calendar.MINUTE, 30)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val t0 = morningCal.timeInMillis
        engine.learnFromTurn(
            rawQuery = "read my notifications",
            normalizedQuery = "read my notifications",
            subCommands = listOf("read my notifications"),
            toolCalls = listOf(ToolCallRequest("read_recent_messages", mapOf("contact_id" to "all"), safetyTier = SafetyTier.GREEN)),
            responseText = "1 unread.",
            success = true,
            nowMs = t0
        )
        engine.learnFromTurn(
            rawQuery = "read my notifications",
            normalizedQuery = "read my notifications",
            subCommands = listOf("read my notifications"),
            toolCalls = listOf(ToolCallRequest("read_recent_messages", mapOf("contact_id" to "all"), safetyTier = SafetyTier.GREEN)),
            responseText = "1 unread.",
            success = true,
            nowMs = t0 + 5_000L
        )
        assertFalse(fts.getLearnedProcesses().any { it.processId.startsWith("proc_habit_") })

        // Distinct session (>30 mins later) promotes recurring habit properly
        engine.learnFromTurn(
            rawQuery = "read my notifications",
            normalizedQuery = "read my notifications",
            subCommands = listOf("read my notifications"),
            toolCalls = listOf(ToolCallRequest("read_recent_messages", mapOf("contact_id" to "all"), safetyTier = SafetyTier.GREEN)),
            responseText = "2 unread.",
            success = true,
            nowMs = t0 + 45L * 60_000L
        )
        assertTrue(fts.getLearnedProcesses().any { it.processId.startsWith("proc_habit_") })

        fts.close()
    }

    @Test
    fun `general knowledge questions answer cleanly without opening search links or technical labels`() = runTest {
        // 1. General knowledge question classification excludes personal/device queries
        assertTrue(com.example.engine.WebKnowledgeResolver.isGeneralKnowledgeQuestion("what is the highest mountain in the philippines"))
        assertTrue(com.example.engine.WebKnowledgeResolver.isGeneralKnowledgeQuestion("Can you tell me who invented the telephone?"))
        assertFalse(com.example.engine.WebKnowledgeResolver.isGeneralKnowledgeQuestion("what is on my calendar today"))
        assertFalse(com.example.engine.WebKnowledgeResolver.isGeneralKnowledgeQuestion("what is my wifi password"))
        assertFalse(com.example.engine.WebKnowledgeResolver.isGeneralKnowledgeQuestion("Summarize my saved notes and plan my day"))

        // 2. Semantic lookup picks general-knowledge-qa first
        val sem = SemanticLookupEngine.lookup("what is the highest mountain in the philippines")
        assertEquals("general-knowledge-qa", sem.candidateSkills.firstOrNull()?.id)

        // 3. Explicit web search command routes to answer_web_question directly
        val webFast = RegexFastPathEngine.match("search google for highest mountain in the philippines")
        assertNotNull(webFast)
        assertEquals("answer_web_question", webFast?.toolCall?.toolName)

        // 4. ScreenTextSanitizer strips SERP links, breadcrumbs, synthetic buttons, and extracts direct answer
        val serpNodes = listOf(
            UiNodeSnapshot("n01", "Skip to main content", "Button", "[0,0,400,60]", clickable = true),
            UiNodeSnapshot("n02", "Accessibility links", "Button", "[0,60,400,120]", clickable = true),
            UiNodeSnapshot("n03", "what is the highest mountain in the philippines", "EditText", "[0,120,900,200]", clickable = true, editable = true),
            UiNodeSnapshot("n04", "Button (FrameLayout)", "FrameLayout", "[900,120,1080,200]", clickable = true),
            UiNodeSnapshot("n05", "Toggle (Switch) [OFF]", "Switch", "[0,200,200,260]", clickable = true),
            UiNodeSnapshot("n06", "AI Overview", "TextView", "[0,260,300,310]", clickable = true),
            UiNodeSnapshot(
                "n07",
                "Mount Apo is the highest mountain in the Philippines, standing at 2,954 meters above sea level in Mindanao.",
                "TextView",
                "[0,320,1080,480]",
                clickable = false
            ),
            UiNodeSnapshot(
                "n08",
                "https://en.wikipedia.org › wiki › Mount_Apo Mount Apo - Wikipedia",
                "View",
                "[0,500,1080,620]",
                clickable = true
            ),
            UiNodeSnapshot("n09", "Feedback", "Button", "[800,620,1080,680]", clickable = true)
        )

        val cleanedChoices = com.example.engine.ScreenTextSanitizer.filterAndCleanChoices(serpNodes, maxChoices = 5)
        assertEquals(1, cleanedChoices.size)
        assertEquals("Mount Apo - Wikipedia", cleanedChoices[0].label)
        assertFalse(cleanedChoices.any { it.label.contains("https://") || it.label.contains("Button (") || it.label.contains("Skip to") })

        val extractedAnswer = com.example.engine.ScreenTextSanitizer.extractDirectWebAnswer(
            serpNodes,
            "what is the highest mountain in the philippines"
        )
        assertNotNull(extractedAnswer)
        assertTrue(extractedAnswer!!.contains("Mount Apo"))
        assertTrue(extractedAnswer.contains("2,954 meters"))
        assertFalse(extractedAnswer.contains("https://"))
        assertFalse(extractedAnswer.contains("Button ("))

        // 5. Router answers factual Q&A cleanly when live web resolver provides the fact
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        try {
            com.example.engine.WebKnowledgeResolver.testAnswerOverride = {
                "Mount Apo is the highest mountain in the Philippines, rising to 2,954 meters (9,692 ft) above sea level. <a href=\"https://en.wikipedia.org/wiki/Mount_Apo\">Source</a>"
            }
            val qaOutcome = router.routeAndExecute("what is the highest mountain in the philippines") { _, _, _ -> }
            assertFalse(qaOutcome.requiresModelSetup)
            assertEquals("general-knowledge-qa", qaOutcome.selectedSkills.firstOrNull())
            assertTrue(qaOutcome.responseText.contains("Mount Apo"))
            assertFalse(qaOutcome.responseText.contains("https://"))
            assertFalse(qaOutcome.responseText.contains("Which one would you like"))

            // Google Search tool call ALSO resolves to direct answer instead of opening raw choices
            val execReport = executor.execute(
                ToolCallRequest(
                    toolName = "search_in_app",
                    args = mapOf("app_name" to "Google", "query" to "what is the highest mountain in the philippines"),
                    safetyTier = SafetyTier.GREEN
                )
            )
            assertTrue(execReport.success)
            assertTrue(execReport.summary.contains("Mount Apo"))
            assertTrue(execReport.ftsResults.isEmpty())

            // Personal / planning prompt without LLM still requires model setup cleanly
            val blockedOutcome = router.routeAndExecute("Summarize my saved notes and plan my day") { _, _, _ -> }
            assertTrue(blockedOutcome.requiresModelSetup)
        } finally {
            com.example.engine.WebKnowledgeResolver.testAnswerOverride = null
            fts.close()
        }
    }

    @Test
    fun `tier1 regex formatting cleans bullets and technical tags and follow-up error cancels phantom countdown`() = runTest {
        // 1. Verify Tier-1 regex output formatting scrubs bullets, arrows, internal markers, and ensures punctuation
        val formatted = NovaPipelineRouter.formatTier1Responses(
            listOf(
                "NEEDS_LLM_LOOP:Volume set to 50%",
                "Bluetooth: ON • Wi-Fi: OFF",
                "Step A -> Step B"
            )
        )
        assertFalse(formatted.contains("NEEDS_LLM_LOOP"))
        assertFalse(formatted.contains(" • "))
        assertFalse(formatted.contains(" -> "))
        assertTrue(formatted.contains("Volume set to 50%."))
        assertTrue(formatted.contains("Bluetooth: ON, Wi-Fi: OFF."))
        assertTrue(formatted.contains("Step A to Step B."))

        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        // Compound Tier-1 regex turn formats cleanly without requiring LLM model setup
        val compound = router.routeAndExecute("set volume to 40% and pause music") { _, _, _ -> }
        assertEquals(RoutingTier.TIER_1_REGEX, compound.routingTier)
        assertFalse(compound.responseText.contains(" • "))
        assertFalse(compound.responseText.contains("dispatched", ignoreCase = true))
        assertTrue(compound.responseText.contains("40%."))
        assertTrue(compound.responseText.contains("Paused media playback", ignoreCase = true))
        fts.close()

        // 2. Verify follow-up listen window resets stale cancelTrigger and cancels countdown if mic fails
        val vm = NovaViewModel(context)
        vm.setFollowUpTimeoutForTest(5_000L)
        vm.startFollowUpListenWindow(speakOnTimeout = false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(vm.uiState.value.voiceState.isAwaitingFollowUp)
        assertTrue(vm.uiState.value.voiceState.followUpListenTrigger > 0L)
        assertEquals(0L, vm.uiState.value.voiceState.followUpCancelTrigger)

        vm.handleFollowUpMicErrorFallback("Microphone speech service busy (error 8)")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(vm.uiState.value.voiceState.isAwaitingFollowUp)
        assertFalse(vm.uiState.value.voiceState.isListening)
        assertTrue(vm.uiState.value.voiceState.showKeyboardInput)
        assertEquals("TAP MIC OR TYPE REPLY", vm.uiState.value.voiceState.statusHeader)

        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
    }

    @Test
    fun `follow up questions internet checking and music resolution work end to end and handle offline gracefully`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val browserPkg = "com.android.chrome"
        val browserAppInfo = android.content.pm.ApplicationInfo().apply {
            packageName = browserPkg
            nonLocalizedLabel = "Chrome"
        }
        shadowPm.installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = browserPkg
                applicationInfo = browserAppInfo
            }
        )
        val watchResolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = browserPkg
                name = "com.google.android.apps.chrome.Main"
                applicationInfo = browserAppInfo
                nonLocalizedLabel = "Chrome"
            }
        }
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            ),
            watchResolveInfo
        )

        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        try {
            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = true
            com.example.engine.WebKnowledgeResolver.testAnswerOverride = { q ->
                when {
                    q.contains("christmas in our hearts", ignoreCase = true) && q.contains("who sang", ignoreCase = true) ->
                        "Christmas in Our Hearts was sung and composed by Jose Mari Chan and his daughter Liza Chan in 1990."
                    q.contains("christmas in our hearts", ignoreCase = true) ->
                        "Christmas in Our Hearts is the best-selling Filipino Christmas ballad by Jose Mari Chan released in 1990. It remains a holiday classic across the Philippines."
                    else -> null
                }
            }
            com.example.engine.WebKnowledgeResolver.testMusicResolverOverride = { q ->
                if (q.contains("christmas in our hearts", ignoreCase = true)) {
                    com.example.engine.WebKnowledgeResolver.ResolvedMusicTrack(
                        title = "Christmas in Our Hearts",
                        artist = "Jose Mari Chan",
                        album = "Christmas in Our Hearts",
                        youtubeVideoId = "dQw4w9WgXcQ"
                    )
                } else null
            }

            // 1. Unknown song question checks online when available & remembers topic
            val turn1 = router.routeAndExecute("what is christmas in our hearts") { _, _, _ -> }
            assertFalse(turn1.requiresModelSetup)
            assertTrue(turn1.responseText.contains("Jose Mari Chan"))

            // 2. Pronoun follow-up resolves topic cleanly ("who sang it?")
            val turn2 = router.routeAndExecute("who sang it?") { _, _, _ -> }
            assertFalse(turn2.requiresModelSetup)
            assertTrue(turn2.responseText.contains("Liza Chan"))

            // 3. Elaboration follow-up ("tell me more") expands using saved topic
            val turn3 = router.routeAndExecute("tell me more") { _, _, _ -> }
            assertFalse(turn3.requiresModelSetup)
            assertTrue(turn3.responseText.contains("holiday classic"))

            // 4. Action pronoun follow-up ("play it") resolves to direct YouTube watch URL with track + artist
            val turn4 = router.routeAndExecute("play it") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, turn4.routingTier)
            val started = Shadows.shadowOf(context).nextStartedActivity
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", started?.dataString)
            assertTrue(turn4.responseText.contains("Christmas in Our Hearts"))
            assertTrue(turn4.responseText.contains("Jose Mari Chan"))

            // 5. Direct "play christmas in our hearts" also resolves resolved title/artist online
            val directPlay = router.routeAndExecute("play christmas in our hearts") { _, _, _ -> }
            assertTrue(directPlay.responseText.contains("Jose Mari Chan"))

            // 6. Single-choice affirmative follow-up ("yes" / "open it") selects item cleanly
            val singleChoice = listOf(
                com.example.data.LocalIndexDocument(
                    docId = "n_only",
                    category = "notes",
                    title = "Holiday Packing List",
                    body = "Sweaters, gifts, charger",
                    uriOrRef = "memory://n_only"
                )
            )
            assertEquals("Holiday Packing List", NovaPipelineRouter.matchPendingChoice("yes", singleChoice)?.title)
            assertEquals("Holiday Packing List", NovaPipelineRouter.matchPendingChoice("open it", singleChoice)?.title)

            // 7. Offline check: informational question explains no internet cleanly (NOT "AI model needed")
            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = false
            val offlineTurn = router.routeAndExecute("who is the president of japan") { _, _, _ -> }
            assertFalse(offlineTurn.requiresModelSetup)
            assertTrue(offlineTurn.responseText.contains("no internet", ignoreCase = true))

            val offlinePlay = router.routeAndExecute("play christmas in our hearts") { _, _, _ -> }
            assertTrue(offlinePlay.responseText.contains("internet", ignoreCase = true))
        } finally {
            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = null
            com.example.engine.WebKnowledgeResolver.testAnswerOverride = null
            com.example.engine.WebKnowledgeResolver.testMusicResolverOverride = null
            fts.close()
        }
    }

    @Test
    fun `direct nova wake variants barge in and idle overlay wake loop work reliably`() = runTest {
        // 1. Calling "Nova ..." without "Hey" and minor ASR variants extract cleanly
        assertEquals(
            "who sang christmas in our hearts",
            NovaVoiceForegroundService.extractWakeCommand("Nova, who sang christmas in our hearts")
        )
        assertEquals(
            "what is the time",
            NovaVoiceForegroundService.extractWakeCommand("Nova what is the time")
        )
        assertEquals(
            "turn on bluetooth",
            NovaVoiceForegroundService.extractWakeCommand("Hey Noah turn on bluetooth")
        )
        assertEquals("", NovaVoiceForegroundService.extractWakeCommand("Nova"))
        // Must not false-trigger on regular English words containing "nova" phrases
        assertNull(NovaVoiceForegroundService.extractWakeCommand("nova scotia weather"))
        assertNull(NovaVoiceForegroundService.extractWakeCommand("nova launcher settings"))

        // 2. ViewModel resumes wake word after TTS completes even when VoiceOverlayScreen is open
        val app = ApplicationProvider.getApplicationContext<Application>()
        val svcController = Robolectric.buildService(NovaVoiceForegroundService::class.java).create()
        val fgs = svcController.get()
        val binderField = NovaViewModel::class.java.getDeclaredField("voiceService")
        binderField.isAccessible = true

        val vm = NovaViewModel(app)
        binderField.set(vm, fgs)
        fgs.setOverlayListeningPaused(true)
        vm.setVoiceOverlayOpen(true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Because VoiceOverlay is open and idle (not actively listening/speaking), wake loop is unpaused
        assertFalse(fgs.isPausedForOverlayForTest())

        // While TTS is speaking, status header surfaces interrupt affordance
        var capturedOnDone: (() -> Unit)? = null
        vm.testSpeakDispatcher = { _, onDone ->
            capturedOnDone = onDone
            true
        }
        vm.submitCommand("turn on bluetooth", speakResponse = true)
        val deadline = System.currentTimeMillis() + 3000L
        while (capturedOnDone == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(vm.uiState.value.voiceState.isSpeakingTts)
        assertTrue(vm.uiState.value.voiceState.statusHeader.contains("TAP OR SAY STOP"))

        capturedOnDone?.invoke()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.uiState.value.voiceState.isSpeakingTts)
        assertFalse(fgs.isPausedForOverlayForTest())

        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
        svcController.destroy()
    }

    @Test
    fun `nova pauses wake word during active music and skips confirmation tts when launching playback`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        // Install Chrome to handle YouTube watch link during "play christmas in our hearts"
        val shadowPm = Shadows.shadowOf(app.packageManager)
        val browserPkg = "com.android.chrome"
        val browserAppInfo = android.content.pm.ApplicationInfo().apply {
            packageName = browserPkg
            nonLocalizedLabel = "Chrome"
        }
        shadowPm.installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = browserPkg
                applicationInfo = browserAppInfo
            }
        )
        val watchResolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = browserPkg
                name = "com.google.android.apps.chrome.Main"
                applicationInfo = browserAppInfo
                nonLocalizedLabel = "Chrome"
            }
        }
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.youtube.com/watch?v=testSong123")
            ),
            watchResolveInfo
        )

        val svcController = Robolectric.buildService(NovaVoiceForegroundService::class.java).create()
        val fgs = svcController.get()
        val binderField = NovaViewModel::class.java.getDeclaredField("voiceService")
        binderField.isAccessible = true
        val vm = NovaViewModel(app)
        binderField.set(vm, fgs)

        try {
            // 1. Default concurrent mode (pauseWakeDuringMedia=false) keeps wake loop ready during music
            fgs.setPauseWakeDuringMedia(false)
            fgs.testMediaPlayingOverride = true
            fgs.setWakeWordListening(true) {}
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(NovaVoiceForegroundService.externalMediaPlaying.value)
            assertTrue(
                com.example.data.WakeWordStatus.PAUSED_FOR_MEDIA != NovaVoiceForegroundService.wakeWordStatus.value
            )

            // 2. When pauseWakeDuringMedia=true, wake word pauses to PAUSED_FOR_MEDIA during active music
            fgs.setPauseWakeDuringMedia(true)
            fgs.refreshExternalMediaStateForTest()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                com.example.data.WakeWordStatus.PAUSED_FOR_MEDIA,
                NovaVoiceForegroundService.wakeWordStatus.value
            )
            assertFalse(fgs.isWakeRecognizerActiveForTest())
            assertTrue(NovaVoiceForegroundService.externalMediaPlaying.value)

            // 3. When music stops, externalMediaPlaying updates to false and PAUSED_FOR_MEDIA releases
            fgs.testMediaPlayingOverride = false
            fgs.refreshExternalMediaStateForTest()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(NovaVoiceForegroundService.externalMediaPlaying.value)
            fgs.testMediaPlayingOverride = null

            // 4. Launching music playback via Nova skips redundant confirmation TTS
            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = true
            com.example.engine.WebKnowledgeResolver.testMusicResolverOverride = {
                com.example.engine.WebKnowledgeResolver.ResolvedMusicTrack(
                    title = "Christmas in Our Hearts",
                    artist = "Jose Mari Chan",
                    album = "Christmas in Our Hearts",
                    youtubeVideoId = "testSong123"
                )
            }
            var ttsCallCount = 0
            vm.testSpeakDispatcher = { _, onDone ->
                ttsCallCount++
                onDone()
                true
            }

            // In default concurrent mode (pauseWakeDuringMedia = false), wake stays ready & header invites speech
            fgs.setPauseWakeDuringMedia(false)
            vm.setPauseWakeDuringMedia(false)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.submitCommand("play christmas in our hearts", speakResponse = true)
            val deadline1 = System.currentTimeMillis() + 3000L
            while (!vm.uiState.value.voiceState.assistantResponse.contains("Christmas in Our Hearts") &&
                System.currentTimeMillis() < deadline1
            ) {
                Thread.sleep(20L)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.uiState.value.voiceState.assistantResponse.contains("Christmas in Our Hearts"))
            assertEquals(0, ttsCallCount)
            assertEquals("PLAYING MEDIA • SAY HEY NOVA OR TAP MIC", vm.uiState.value.voiceState.statusHeader)
            assertTrue(
                com.example.data.WakeWordStatus.PAUSED_FOR_MEDIA != NovaVoiceForegroundService.wakeWordStatus.value
            )

            // When user explicitly enables Pause Wake While Music Plays, playback pauses wake to PAUSED_FOR_MEDIA
            fgs.setPauseWakeDuringMedia(true)
            vm.setPauseWakeDuringMedia(true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.submitCommand("play christmas in our hearts", speakResponse = true)
            val deadline2 = System.currentTimeMillis() + 3000L
            while (vm.uiState.value.voiceState.statusHeader != "PLAYING MEDIA • MIC PAUSED" &&
                System.currentTimeMillis() < deadline2
            ) {
                Thread.sleep(20L)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, ttsCallCount)
            assertEquals("PLAYING MEDIA • MIC PAUSED", vm.uiState.value.voiceState.statusHeader)
            assertEquals(
                com.example.data.WakeWordStatus.PAUSED_FOR_MEDIA,
                NovaVoiceForegroundService.wakeWordStatus.value
            )
        } finally {
            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = null
            com.example.engine.WebKnowledgeResolver.testMusicResolverOverride = null
            fgs.testMediaPlayingOverride = null
            val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
            onClearedMethod.isAccessible = true
            onClearedMethod.invoke(vm)
            svcController.destroy()
        }
    }

    @Test
    fun `multi slot asr and tts model importing zip extraction switching and validation work`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val modelManager = LlmModelManager(context)
        modelManager.deleteActiveModel(ModelSlot.ASR)
        modelManager.deleteActiveModel(ModelSlot.TTS)

        // Create real temporary model files & a Kokoro .zip bundle in cacheDir
        val tmpDir = java.io.File(context.cacheDir, "test_imports_${System.currentTimeMillis()}").apply { mkdirs() }
        val whisperFile = java.io.File(tmpDir, "whisper-tiny.onnx").apply {
            writeBytes(ByteArray(4096) { 0x42 })
        }
        val moonshineFile = java.io.File(tmpDir, "moonshine-tiny.onnx").apply {
            writeBytes(ByteArray(6144) { 0x24 })
        }
        val kokoroZip = java.io.File(tmpDir, "kokoro-v1.0-bundle.zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(kokoroZip)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("model.onnx"))
            zos.write(ByteArray(8192) { 0x55 })
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("tokens.txt"))
            zos.write("a b c d e f g\n".repeat(40).toByteArray())
            zos.closeEntry()
        }
        val htmlFakeFile = java.io.File(tmpDir, "broken.onnx").apply {
            writeText("<!DOCTYPE html><html><body>404 Not Found</body></html>" + " ".repeat(2048))
        }
        val tinyFile = java.io.File(tmpDir, "empty.onnx").apply {
            writeBytes(ByteArray(128))
        }

        try {
            // 1. Import two ASR models -> both preserved, latest active, can switch active cleanly
            val r1 = modelManager.importLocalModel(ModelSlot.ASR, android.net.Uri.fromFile(whisperFile), "whisper-tiny.onnx")
            assertTrue(r1.isSuccess)
            val r2 = modelManager.importLocalModel(ModelSlot.ASR, android.net.Uri.fromFile(moonshineFile), "moonshine-tiny.onnx")
            assertTrue(r2.isSuccess)

            val asrModels = modelManager.listInstalledModels(ModelSlot.ASR)
            assertEquals(2, asrModels.size)
            assertEquals("moonshine-tiny.onnx", modelManager.getActiveModelFile(ModelSlot.ASR)?.name)

            assertTrue(modelManager.setActiveModel(ModelSlot.ASR, "whisper-tiny.onnx"))
            assertEquals("whisper-tiny.onnx", modelManager.getActiveModelFile(ModelSlot.ASR)?.name)

            // Deleting one model preserves the other and promotes it
            assertTrue(modelManager.deleteModel(ModelSlot.ASR, "whisper-tiny.onnx"))
            assertEquals(1, modelManager.listInstalledModels(ModelSlot.ASR).size)
            assertEquals("moonshine-tiny.onnx", modelManager.getActiveModelFile(ModelSlot.ASR)?.name)

            // 2. Import Kokoro .zip bundle into TTS slot -> safely extracted as bundle directory
            val rZip = modelManager.importLocalModel(ModelSlot.TTS, android.net.Uri.fromFile(kokoroZip), "kokoro-v1.0-bundle.zip")
            assertTrue(rZip.isSuccess)
            val ttsActive = modelManager.getActiveModelFile(ModelSlot.TTS)
            assertNotNull(ttsActive)
            assertTrue(ttsActive!!.isDirectory)
            assertEquals("kokoro-v1.0-bundle", ttsActive.name)
            assertTrue(java.io.File(ttsActive, "model.onnx").exists())
            assertTrue(java.io.File(ttsActive, "tokens.txt").exists())
            assertTrue(modelManager.getActiveModelSizeBytes(ModelSlot.TTS) > 8192L)

            // 3. Reject HTML error pages & tiny files
            val rHtml = modelManager.importLocalModel(ModelSlot.TTS, android.net.Uri.fromFile(htmlFakeFile), "broken.onnx")
            assertTrue(rHtml.isFailure)
            assertTrue(modelManager.downloadState.value is com.example.data.ModelDownloadState.Failed)

            val rTiny = modelManager.importLocalModel(ModelSlot.ASR, android.net.Uri.fromFile(tinyFile), "empty.onnx")
            assertTrue(rTiny.isFailure)

            // 4. Presets include Kokoro-82M, Piper VITS, Whisper, and Moonshine
            assertTrue(modelManager.ttsPresets.any { it.displayName.contains("Kokoro", ignoreCase = true) })
            assertTrue(modelManager.ttsPresets.any { it.displayName.contains("Piper", ignoreCase = true) })
            assertTrue(modelManager.asrPresets.any { it.displayName.contains("Whisper", ignoreCase = true) })
            assertTrue(modelManager.asrPresets.any { it.displayName.contains("Moonshine", ignoreCase = true) })

            // 5. ViewModel ASR & TTS engine switches update ProcessHealthState cleanly
            val vm = NovaViewModel(context)
            vm.selectAsrEngine(com.example.data.AsrEngineOption.ANDROID_ON_DEVICE)
            vm.selectTtsEngine(com.example.data.TtsEngineOption.KOKORO_ONNX)
            vm.setTtsSpeechRate(1.15f)
            vm.setPauseWakeDuringMedia(false)
            assertEquals(com.example.data.AsrEngineOption.ANDROID_ON_DEVICE, vm.uiState.value.processHealth.asrEngine)
            assertEquals(com.example.data.TtsEngineOption.KOKORO_ONNX, vm.uiState.value.processHealth.ttsEngine)
            assertEquals(1.15f, vm.uiState.value.processHealth.ttsSpeechRate)
            assertFalse(vm.uiState.value.processHealth.pauseWakeDuringMedia)

            val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
            onClearedMethod.isAccessible = true
            onClearedMethod.invoke(vm)
        } finally {
            tmpDir.deleteRecursively()
            modelManager.deleteActiveModel(ModelSlot.ASR)
            modelManager.deleteActiveModel(ModelSlot.TTS)
        }
    }

    @Test
    fun `current notifications query reconciles live active notifications against saved archive and reads them`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)
        val now = System.currentTimeMillis()

        val waAlert = com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
            id = "live_wa_1",
            key = "0|com.whatsapp|1|null|10001",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.MESSAGE,
            title = "Maya",
            body = "Are we still on for dinner tonight?",
            timestampMs = now - 5 * 60_000L,
            spokenPrompt = "Message from Maya on WhatsApp",
            shortLabel = "Message • Maya"
        )
        val smsReadAlert = com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
            id = "live_sms_read",
            key = "0|com.google.android.apps.messaging|2|null|10002",
            packageName = "com.google.android.apps.messaging",
            appLabel = "Messages",
            type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.MESSAGE,
            title = "Bank Alert",
            body = "Your OTP is 482910",
            timestampMs = now - 15 * 60_000L,
            spokenPrompt = "Message from Bank Alert",
            shortLabel = "Message • Bank Alert"
        )
        val dismissedAlert = com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
            id = "dismissed_earlier",
            key = "0|com.whatsapp|3|null|10003",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.MESSAGE,
            title = "Leo",
            body = "Cleared from shade earlier",
            timestampMs = now - 25 * 60_000L,
            spokenPrompt = "Message from Leo on WhatsApp",
            shortLabel = "Message • Leo"
        )
        val newGmailAlert = com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
            id = "live_gmail_new",
            key = "0|com.google.android.gm|4|null|10004",
            packageName = "com.google.android.gm",
            appLabel = "Gmail",
            type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.GENERAL,
            title = "Flight Confirmation",
            body = "Your boarding pass for PR 102 is ready.",
            timestampMs = now - 60_000L,
            spokenPrompt = "Notification from Gmail: Flight Confirmation",
            shortLabel = "Notification • Gmail"
        )

        try {
            // Seed archive: 1 already-saved unread WhatsApp alert + 1 already-read SMS alert + 1 dismissed alert no longer in shade
            fts.upsertNotificationArchive(waAlert, markRead = false, nowMs = now)
            fts.upsertNotificationArchive(smsReadAlert, markRead = true, nowMs = now)
            fts.upsertNotificationArchive(dismissedAlert, markRead = false, nowMs = now)

            // Live status bar currently has: 1 brand-new Gmail email (GENERAL) + the already-saved unread WhatsApp + already-read SMS
            val liveShade = listOf(newGmailAlert, waAlert, smsReadAlert)
            com.example.processA.NovaNotificationListenerService.testActiveNotificationsProvider = { includeGeneral ->
                if (includeGeneral) liveShade else liveShade.filter {
                    it.type != com.example.engine.ImportantNotificationFilter.ImportantNotificationType.GENERAL
                }
            }

            // 1. Regex fast-path routes "read the current notification" and "can you read my current notifications" to scope="current"
            val matchSingle = RegexFastPathEngine.match("read the current notification")
            assertNotNull(matchSingle)
            assertEquals("read_recent_messages", matchSingle!!.toolCall.toolName)
            assertEquals("current", matchSingle.toolCall.args["scope"])

            val matchPolite = RegexFastPathEngine.match("can you read my current notifications please")
            assertNotNull(matchPolite)
            assertEquals("read_recent_messages", matchPolite!!.toolCall.toolName)
            assertEquals("current", matchPolite.toolCall.args["scope"])

            // 2. Execution reconciles live shade vs saved archive, saves the brand-new Gmail notification, and reads all 3 live alerts
            val result = router.routeAndExecute("read the current notification") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, result.routingTier)
            assertTrue(result.responseText.contains("3 current notifications", ignoreCase = true))
            assertTrue(result.responseText.contains("1 new", ignoreCase = true))
            assertTrue(result.responseText.contains("1 already saved unread", ignoreCase = true))
            assertTrue(result.responseText.contains("1 already read", ignoreCase = true))
            assertTrue(result.responseText.contains("Flight Confirmation"))
            assertTrue(result.responseText.contains("Maya"))
            assertFalse(result.responseText.contains("Cleared from shade earlier"))

            // 3. Brand-new Gmail alert was persisted to SQLite and marked read after delivery
            val stateMap = fts.getArchivedNotificationStateMap()
            assertEquals(true, stateMap["live_gmail_new"])
            assertEquals(true, stateMap["live_wa_1"])
            assertEquals(true, stateMap["live_sms_read"])
            // Unread alert that was cleared from shade remains tracked in archive
            assertEquals(false, stateMap["dismissed_earlier"])

            // 4. General active filter skips ongoing/foreground/media/Nova notifications
            val ongoingDecision = com.example.engine.ImportantNotificationFilter.classifyActiveForUserQuery(
                com.example.engine.ImportantNotificationFilter.RawNotificationSignals(
                    key = "0|com.google.android.apps.youtube.music|99|null|1",
                    packageName = "com.google.android.apps.youtube.music",
                    ownPackageName = context.packageName,
                    appLabel = "YouTube Music",
                    category = android.app.Notification.CATEGORY_TRANSPORT,
                    title = "Now Playing",
                    text = "Christmas in Our Hearts",
                    flags = android.app.Notification.FLAG_ONGOING_EVENT,
                    hasMediaSession = true
                )
            )
            assertNull(ongoingDecision)
            val generalEmailDecision = com.example.engine.ImportantNotificationFilter.classifyActiveForUserQuery(
                com.example.engine.ImportantNotificationFilter.RawNotificationSignals(
                    key = "0|com.google.android.gm|100|null|1",
                    packageName = "com.google.android.gm",
                    ownPackageName = context.packageName,
                    appLabel = "Gmail",
                    category = android.app.Notification.CATEGORY_EMAIL,
                    title = "Flight Confirmation",
                    text = "Boarding pass ready"
                )
            )
            assertEquals(
                com.example.engine.ImportantNotificationFilter.ImportantNotificationType.GENERAL,
                generalEmailDecision?.type
            )
        } finally {
            com.example.processA.NovaNotificationListenerService.testActiveNotificationsProvider = null
            fts.close()
        }
    }

    @Test
    fun `tonights briefing and evening briefing route to device briefing and never open youtube`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        try {
            val eveningPhrases = listOf(
                "provide me the tonights briefing",
                "provide me with tonight's briefing",
                "can you give me tonight's briefing",
                "tonights briefing",
                "evening briefing",
                "what is my briefing tonight"
            )
            for (phrase in eveningPhrases) {
                val match = RegexFastPathEngine.match(phrase)
                assertNotNull("Expected Tier-1 briefing match for '$phrase'", match)
                assertEquals("get_device_briefing", match!!.toolCall.toolName)
                assertEquals("evening", match.toolCall.args["topic"])
            }

            val afternoonMatch = RegexFastPathEngine.match("provide me the afternoon briefing")
            assertNotNull(afternoonMatch)
            assertEquals("get_device_briefing", afternoonMatch!!.toolCall.toolName)
            assertEquals("afternoon", afternoonMatch.toolCall.args["topic"])

            val result = router.routeAndExecute("provide me the tonights briefing") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, result.routingTier)
            assertTrue(result.responseText.contains("Battery", ignoreCase = true))
            // Must NEVER launch YouTube or Chrome search for "tonights briefing"
            assertNull(Shadows.shadowOf(context).nextStartedActivity)
        } finally {
            fts.close()
        }
    }

    @Test
    fun `play a christmas song and play christmas in our hearts open youtube music directly when installed`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = Shadows.shadowOf(context.packageManager)

        // Install YouTube Music + Chrome (so native YouTube Music is present alongside browser)
        val ytmPkg = "com.google.android.apps.youtube.music"
        val ytmAppInfo = android.content.pm.ApplicationInfo().apply {
            packageName = ytmPkg
            nonLocalizedLabel = "YouTube Music"
            enabled = true
        }
        shadowPm.installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = ytmPkg
                applicationInfo = ytmAppInfo
            }
        )
        val ytmResolveInfo = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = ytmPkg
                name = "com.google.android.apps.youtube.music.activities.MusicActivity"
                applicationInfo = ytmAppInfo
                nonLocalizedLabel = "YouTube Music"
            }
        }
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(
            android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            },
            ytmResolveInfo
        )
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://music.youtube.com/watch?v=xmasRand999")
            ).setPackage(ytmPkg),
            ytmResolveInfo
        )
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://music.youtube.com/watch?v=xmasHeart11")
            ).setPackage(ytmPkg),
            ytmResolveInfo
        )

        val browserPkg = "com.android.chrome"
        val browserAppInfo = android.content.pm.ApplicationInfo().apply {
            packageName = browserPkg
            nonLocalizedLabel = "Chrome"
            enabled = true
        }
        shadowPm.installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = browserPkg
                applicationInfo = browserAppInfo
            }
        )

        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        try {
            // 1. RandomSongCatalog maps "a christmas song" to a real Christmas track (NOT literal "christmas song")
            val (pickedChristmasTrack, wasRand) = com.example.engine.RandomSongCatalog.resolveEffectiveSongQuery("a christmas song")
            assertTrue(wasRand)
            assertTrue(pickedChristmasTrack in com.example.engine.RandomSongCatalog.tracksForTheme("christmas"))

            // 2. HTML parser handles plain JSON, escaped JSON, and mobile hex-escaped \x22videoId\x22
            assertEquals(
                "dQw4w9WgXcQ",
                com.example.engine.WebKnowledgeResolver.extractYouTubeVideoIdFromHtml(
                    """{"videoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"runs":[{"text":"Test"}]}}}"""
                )
            )
            assertEquals(
                "AbCdEf12345",
                com.example.engine.WebKnowledgeResolver.extractYouTubeVideoIdFromHtml(
                    """var ytInitialData = '\x7b\x22videoRenderer\x22:\x7b\x22videoId\x22:\x22AbCdEf12345\x22\x7d\x7d';"""
                )
            )

            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = true
            com.example.engine.WebKnowledgeResolver.testMusicResolverOverride = { q ->
                if (q.contains("christmas in our hearts", ignoreCase = true)) {
                    com.example.engine.WebKnowledgeResolver.ResolvedMusicTrack(
                        title = "Christmas in Our Hearts",
                        artist = "Jose Mari Chan",
                        album = "Christmas in Our Hearts",
                        youtubeVideoId = "xmasHeart11"
                    )
                } else {
                    com.example.engine.WebKnowledgeResolver.ResolvedMusicTrack(
                        title = q.substringBefore(" by ").ifBlank { q },
                        artist = "Holiday Artist",
                        youtubeVideoId = "xmasRand999"
                    )
                }
            }

            // 3. "play a christmas song" opens YouTube Music directly with a real random Christmas track (NOT browser search!)
            val resRandom = router.routeAndExecute("play a christmas song") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, resRandom.routingTier)
            val startedRandom = Shadows.shadowOf(context).nextStartedActivity
            assertNotNull("Expected YouTube Music intent to be launched", startedRandom)
            assertEquals(ytmPkg, startedRandom?.`package`)
            assertEquals("https://music.youtube.com/watch?v=xmasRand999", startedRandom?.dataString)
            assertTrue(resRandom.responseText.contains("YouTube Music"))

            // 4. "play christmas in our hearts" opens YouTube Music directly with resolved watch URL (NOT browser search!)
            val resSpecific = router.routeAndExecute("play christmas in our hearts") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, resSpecific.routingTier)
            val startedSpecific = Shadows.shadowOf(context).nextStartedActivity
            assertNotNull("Expected YouTube Music intent to be launched", startedSpecific)
            assertEquals(ytmPkg, startedSpecific?.`package`)
            assertEquals("https://music.youtube.com/watch?v=xmasHeart11", startedSpecific?.dataString)
            assertTrue(resSpecific.responseText.contains("Christmas in Our Hearts"))
            assertTrue(resSpecific.responseText.contains("Jose Mari Chan"))
            assertTrue(resSpecific.responseText.contains("YouTube Music"))
        } finally {
            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = null
            com.example.engine.WebKnowledgeResolver.testMusicResolverOverride = null
            fts.close()
        }
    }

    @Test
    fun `notification queries deduplicate by key never misroute to briefing or single letter and briefing omits wifi bluetooth`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)
        val now = System.currentTimeMillis()

        try {
            // 1. Repeated updates for the SAME Android notif_key collapse into 1 archive row (NOT 25 or 111!)
            for (i in 1..25) {
                val update = com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
                    id = "imp_same_key_v$i",
                    key = "0|com.whatsapp|42|chat_thread|10055",
                    packageName = "com.whatsapp",
                    appLabel = "WhatsApp",
                    type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.MESSAGE,
                    title = "Group Chat",
                    body = "$i new messages in project chat",
                    timestampMs = now - (30 - i) * 1000L,
                    spokenPrompt = "Message from Group Chat",
                    shortLabel = "Message • Group Chat",
                    importanceScore = 60,
                    importanceTier = com.example.engine.ImportantNotificationFilter.NotificationImportanceTier.NORMAL
                )
                fts.upsertNotificationArchive(update, markRead = false, nowMs = now)
            }
            val dedupAudit = fts.runBackgroundNotificationAudit(now)
            assertEquals("Same notif_key updates must stay deduplicated to 1 row", 1, dedupAudit.totalSaved14Days)

            // 2. Live undismissed shade has 1 normal Gmail alert + 1 CRITICAL missed call from Doctor
            val normalLive = com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
                id = "live_promo_order",
                key = "0|com.google.android.gm|7|null|10055",
                packageName = "com.google.android.gm",
                appLabel = "Gmail",
                type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.GENERAL,
                title = "Order Shipped",
                body = "Your headphones are out for delivery.",
                timestampMs = now - 30_000L,
                spokenPrompt = "Notification from Gmail: Order Shipped",
                shortLabel = "Notification • Gmail",
                importanceScore = 65,
                importanceTier = com.example.engine.ImportantNotificationFilter.NotificationImportanceTier.NORMAL
            )
            val criticalLive = com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
                id = "live_missed_doctor",
                key = "0|com.google.android.dialer|8|null|10055",
                packageName = "com.google.android.dialer",
                appLabel = "Phone",
                type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.MISSED_CALL,
                title = "Dr. Santos (Urgent)",
                body = "Missed call from Dr. Santos regarding lab results",
                timestampMs = now - 120_000L,
                spokenPrompt = "Missed call from Dr. Santos",
                shortLabel = "Missed Call • Dr. Santos",
                importanceScore = 96,
                importanceTier = com.example.engine.ImportantNotificationFilter.NotificationImportanceTier.CRITICAL
            )
            com.example.processA.NovaNotificationListenerService.testActiveNotificationsProvider = { includeGeneral ->
                if (includeGeneral) listOf(normalLive, criticalLive) else listOf(criticalLive)
            }

            // 3. "read the notifications for me" routes on Tier 1 and reads active undismissed alerts (never "I" or briefing)
            val resForMe = router.routeAndExecute("read the notifications for me") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, resForMe.routingTier)
            assertTrue(resForMe.responseText.contains("Order Shipped"))
            assertTrue(resForMe.responseText.contains("Dr. Santos"))
            assertFalse(resForMe.responseText.contains("Battery", ignoreCase = true))

            // 4. "read me the most important notification currently" reads the #1 CRITICAL live alert (never briefing or 111)
            val resMostImp = router.routeAndExecute("read me the most important notification currently") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, resMostImp.routingTier)
            assertTrue(resMostImp.responseText.contains("most important current notification", ignoreCase = true))
            assertTrue(resMostImp.responseText.contains("Dr. Santos"))
            assertFalse(resMostImp.responseText.contains("Order Shipped"))
            assertFalse(resMostImp.responseText.contains("Battery", ignoreCase = true))

            // 5. Bare "notifications" / "give me my notifications" read notifications, NOT briefing
            for (phrase in listOf("notifications", "my notifications", "give me my notifications")) {
                val r = router.routeAndExecute(phrase) { _, _, _ -> }
                assertEquals("Expected Tier-1 notification match for '$phrase'", RoutingTier.TIER_1_REGEX, r.routingTier)
                assertFalse("'$phrase' must not trigger briefing", r.responseText.contains("Battery", ignoreCase = true))
                assertTrue("'$phrase' should read active alerts", r.responseText.contains("Dr. Santos"))
            }

            // 6. markNotificationsReadByKey marks dismissed key read cleanly
            val markedByKey = fts.markNotificationsReadByKey("0|com.whatsapp|42|chat_thread|10055", now)
            assertEquals(1, markedByKey)

            // 7. Single-letter truncated LLM fragment "I" is rejected by sanitizeAssistantReply
            val sanitizedI = com.example.processB.LiteRtLmRuntimeWrapper.sanitizeAssistantReply(
                parsedReply = "I",
                rawFallback = "I",
                hasToolCalls = false
            )
            assertFalse(sanitizedI == "I")
            assertTrue(sanitizedI.contains("rephrasing", ignoreCase = true))

            // 8. Daily/evening briefing no longer repeats Wi-Fi / Bluetooth status (reserved for connectivity status)
            val eveningBriefing = router.routeAndExecute("provide me the tonights briefing") { _, _, _ -> }
            assertFalse(eveningBriefing.responseText.contains("Wi-Fi", ignoreCase = true))
            assertFalse(eveningBriefing.responseText.contains("Bluetooth", ignoreCase = true))

            val connectivityCheck = router.routeAndExecute("check connectivity status") { _, _, _ -> }
            assertTrue(connectivityCheck.responseText.contains("Wi-Fi", ignoreCase = true))
            assertTrue(connectivityCheck.responseText.contains("Bluetooth", ignoreCase = true))
        } finally {
            com.example.processA.NovaNotificationListenerService.testActiveNotificationsProvider = null
            fts.close()
        }
    }

    @Test
    fun testNotificationCleanFormattingAndOpenChoiceCapability() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        val now = System.currentTimeMillis()
        val alerts = listOf(
            com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
                id = "fmt_gmail_1",
                key = "0|com.google.android.gm|1|tag|1000",
                packageName = "com.google.android.gm",
                appLabel = "Gmail",
                type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.GENERAL,
                title = "Flight Confirmation",
                body = "Your boarding pass is ready.",
                timestampMs = now - 60_000L,
                spokenPrompt = "Gmail Flight Confirmation",
                shortLabel = "Gmail • Flight Confirmation",
                importanceScore = 88,
                importanceTier = com.example.engine.ImportantNotificationFilter.NotificationImportanceTier.CRITICAL
            ),
            com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
                id = "fmt_wa_2",
                key = "0|com.whatsapp|2|tag|1000",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.MESSAGE,
                title = "Maya",
                body = "Are you coming to dinner?",
                timestampMs = now - 180_000L,
                spokenPrompt = "WhatsApp Maya",
                shortLabel = "WhatsApp • Maya",
                importanceScore = 76,
                importanceTier = com.example.engine.ImportantNotificationFilter.NotificationImportanceTier.HIGH
            ),
            com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
                id = "fmt_cal_3",
                key = "0|com.google.android.calendar|3|tag|1000",
                packageName = "com.google.android.calendar",
                appLabel = "Calendar",
                type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.REMINDER,
                title = "Standup",
                body = "Standup",
                timestampMs = now - 300_000L,
                spokenPrompt = "Reminder Standup",
                shortLabel = "Reminder • Standup",
                importanceScore = 72,
                importanceTier = com.example.engine.ImportantNotificationFilter.NotificationImportanceTier.NORMAL
            ),
            com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
                id = "fmt_phone_4",
                key = "0|com.google.android.dialer|4|tag|1000",
                packageName = "com.google.android.dialer",
                appLabel = "Phone",
                type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.MISSED_CALL,
                title = "Alex",
                body = "Missed call at 3:10 PM",
                timestampMs = now - 420_000L,
                spokenPrompt = "Missed call from Alex",
                shortLabel = "Missed Call • Alex",
                importanceScore = 92,
                importanceTier = com.example.engine.ImportantNotificationFilter.NotificationImportanceTier.CRITICAL
            ),
            com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert(
                id = "fmt_slack_5",
                key = "0|com.Slack|5|tag|1000",
                packageName = "com.Slack",
                appLabel = "Slack",
                type = com.example.engine.ImportantNotificationFilter.ImportantNotificationType.MESSAGE,
                title = "Slack",
                body = "Deployment finished successfully",
                timestampMs = now - 540_000L,
                spokenPrompt = "Slack Deployment finished",
                shortLabel = "Slack • Deployment",
                importanceScore = 65,
                importanceTier = com.example.engine.ImportantNotificationFilter.NotificationImportanceTier.NORMAL
            )
        )

        val openedCalls = mutableListOf<Pair<String, String?>>()
        try {
            com.example.processA.NovaNotificationListenerService.testActiveNotificationsProvider = { _ -> alerts }
            com.example.processA.NovaNotificationListenerService.testOpenNotificationOverride = { alertId, pkg ->
                openedCalls.add(alertId to pkg)
                true
            }

            // 1. Formatted response uses clean numbered lines without double periods, bullets, or robotic "Unread" tags
            val readOutcome = router.routeAndExecute("read my current notifications") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, readOutcome.routingTier)
            assertTrue("Should preserve multi-line list formatting", readOutcome.responseText.contains("\n1. "))
            assertTrue("Should number all 5 alerts cleanly", readOutcome.responseText.contains("\n5. "))
            assertFalse("Should never contain double periods", readOutcome.responseText.contains(".."))
            assertFalse("Should never contain '?.' punctuation artifact", readOutcome.responseText.contains("?."))
            assertFalse("Should not contain raw bullet characters", readOutcome.responseText.contains("•"))
            assertFalse("Should not duplicate app name 'Slack — Slack'", readOutcome.responseText.contains("Slack — Slack"))
            assertFalse("Should not repeat identical body 'Standup: Standup'", readOutcome.responseText.contains("Standup: Standup"))
            assertTrue(
                "Should await follow-up choice when multiple notifications are listed",
                NovaPipelineRouter.shouldAwaitFollowUp(readOutcome, router.getPendingChoices().isNotEmpty())
            )
            assertEquals(5, router.getPendingChoices().size)

            // 2. Follow-up "number 5" opens the 5th notification via PendingIntent/package
            val openFive = router.routeAndExecute("number 5") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, openFive.routingTier)
            assertEquals(1, openedCalls.size)
            assertEquals("fmt_slack_5", openedCalls[0].first)
            assertEquals("com.Slack", openedCalls[0].second)
            assertTrue(openFive.responseText.contains("Opening Slack", ignoreCase = true))

            // 3. Follow-up by natural app/sender name ("open gmail", "open the maya one") works
            router.routeAndExecute("read my current notifications") { _, _, _ -> }
            val openGmail = router.routeAndExecute("open gmail") { _, _, _ -> }
            assertEquals("fmt_gmail_1", openedCalls.last().first)
            assertEquals("com.google.android.gm", openedCalls.last().second)
            assertTrue(openGmail.responseText.contains("Opening Gmail", ignoreCase = true))

            // 4. Works even after live shade is cleared (archived notification uses notif://<pkg>/<id>)
            router.routeAndExecute("read my current notifications") { _, _, _ -> }
            com.example.processA.NovaNotificationListenerService.testActiveNotificationsProvider = { emptyList() }
            val openArchivedMaya = router.routeAndExecute("open the maya one") { _, _, _ -> }
            assertEquals("fmt_wa_2", openedCalls.last().first)
            assertEquals("com.whatsapp", openedCalls.last().second)
            assertTrue(openArchivedMaya.responseText.contains("Opening WhatsApp", ignoreCase = true))

            // 5. Declining the follow-up ("no thanks") clears choices cleanly on Tier 1
            com.example.processA.NovaNotificationListenerService.testActiveNotificationsProvider = { _ -> alerts }
            router.routeAndExecute("read my current notifications") { _, _, _ -> }
            val decline = router.routeAndExecute("no thanks") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, decline.routingTier)
            assertTrue(router.getPendingChoices().isEmpty())
            assertTrue(decline.responseText.contains("Okay", ignoreCase = true))
        } finally {
            com.example.processA.NovaNotificationListenerService.testActiveNotificationsProvider = null
            com.example.processA.NovaNotificationListenerService.testOpenNotificationOverride = null
            fts.close()
        }
    }

    @Test
    fun `play christmas in our hearts routes to installed music app and never browser or false app match`() = runTest {
        val fakeApps = listOf(
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Samsung Health",
                packageName = "com.sec.android.app.shealth",
                categoryLabel = "Health",
                capabilities = listOf("launch"),
                howToUse = "launch_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Heart Rate",
                packageName = "com.example.heartrate",
                categoryLabel = "Health",
                capabilities = listOf("launch"),
                howToUse = "launch_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Chrome",
                packageName = "com.android.chrome",
                categoryLabel = "Browser",
                capabilities = listOf("launch", "web_search"),
                howToUse = "launch_app"
            ),
            AppCapabilityRegistry.InstalledAppProfile(
                appName = "Samsung Music",
                packageName = "com.sec.android.app.music",
                categoryLabel = "Audio/Music",
                capabilities = listOf("launch", "play_media", "search_in_app"),
                howToUse = "play_in_app"
            )
        )

        // 1. Song titles containing "in" must NOT be split into app_name="our hearts"
        val cleanMatch = RegexFastPathEngine.match("play christmas in our hearts", fakeApps)
        assertNotNull(cleanMatch)
        assertEquals("play_in_app", cleanMatch?.toolCall?.toolName)
        assertEquals("", cleanMatch?.toolCall?.args?.get("app_name"))
        assertEquals("christmas in our hearts", cleanMatch?.toolCall?.args?.get("query"))

        // 2. Typo phrasing from user prompt also preserves full song query and skips false app match
        val typoMatch = RegexFastPathEngine.match("play cvhristmas in our hearts", fakeApps)
        assertNotNull(typoMatch)
        assertEquals("play_in_app", typoMatch?.toolCall?.toolName)
        assertEquals("", typoMatch?.toolCall?.args?.get("app_name"))
        assertEquals("cvhristmas in our hearts", typoMatch?.toolCall?.args?.get("query"))

        // 3. When Chrome + Samsung Music are installed (even if preferred app "YouTube Music" is absent),
        //    Nova MUST launch the installed music app and NEVER fall back to Chrome browser
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val chromePkg = "com.android.chrome"
        val samsungMusicPkg = "com.sec.android.app.music"

        fun installMockApp(pkg: String, cls: String, label: String, includeMediaSearch: Boolean = false) {
            val comp = android.content.ComponentName(pkg, cls)
            val appInfo = android.content.pm.ApplicationInfo().apply {
                packageName = pkg
                nonLocalizedLabel = label
            }
            shadowPm.installPackage(
                android.content.pm.PackageInfo().apply {
                    packageName = pkg
                    applicationInfo = appInfo
                }
            )
            shadowPm.addActivityIfNotPresent(comp)
            val ri = android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply {
                    packageName = pkg
                    name = cls
                    applicationInfo = appInfo
                    nonLocalizedLabel = label
                }
            }
            val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                setPackage(pkg)
            }
            @Suppress("DEPRECATION")
            shadowPm.addResolveInfoForIntent(launcher, ri)
            val allLauncher = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            @Suppress("DEPRECATION")
            shadowPm.addResolveInfoForIntent(allLauncher, ri)
            if (includeMediaSearch) {
                val mediaSearch = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage(pkg)
                }
                @Suppress("DEPRECATION")
                shadowPm.addResolveInfoForIntent(mediaSearch, ri)
            }
        }

        installMockApp(chromePkg, "com.google.android.apps.chrome.Main", "Chrome", includeMediaSearch = false)
        installMockApp(samsungMusicPkg, "com.sec.android.app.music.Main", "Samsung Music", includeMediaSearch = true)

        val preferred = AppCapabilityRegistry.findPreferredMediaApp(context)
        assertNotNull("Should detect installed Samsung Music as preferred media app", preferred)
        assertEquals(samsungMusicPkg, preferred?.packageName)
        assertFalse("Must never treat Chrome as a music app", AppCapabilityRegistry.isBrowserApp(preferred!!))

        val fts = LocalFtsIndexHelper(context)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        try {
            val report = executor.execute(cleanMatch!!.toolCall)
            assertTrue(report.success)
            val launched = Shadows.shadowOf(context).nextStartedActivity
            assertNotNull("Should launch music app intent", launched)
            assertEquals("Must target Samsung Music instead of browser", samsungMusicPkg, launched?.`package`)
            assertFalse(
                "Summary must not claim browser fallback when music app exists",
                report.summary.contains("browser", ignoreCase = true)
            )
        } finally {
            fts.close()
        }
    }

    @Test
    fun `available routines and step by step routine listing resolve instantly on tier 1 with full detail`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val bridge = LlmProcessBridge(context, modelManager)
        val recordedStore = RecordedRoutineStore(context)
        val executor = DeviceActionExecutor(context, fts, routineStore = recordedStore) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)
        val customRoutine = RecordedRoutine(
            id = "test_routine_night_winddown",
            name = "Night Wind Down",
            scope = RoutineScope.GLOBAL,
            steps = listOf(
                RoutineStep(
                    actionType = RoutineActionType.HARDWARE_TOGGLE,
                    title = "Enable Do Not Disturb",
                    targetLabel = "dnd",
                    textValue = "on",
                    delayBeforeMs = 0L
                ),
                RoutineStep(
                    actionType = RoutineActionType.HARDWARE_TOGGLE,
                    title = "Dim screen brightness",
                    targetLabel = "brightness",
                    textValue = "low",
                    delayBeforeMs = 0L
                )
            )
        )

        try {
            recordedStore.saveRoutine(customRoutine)

            // 1. Listing available routines must resolve on Tier 1 with all built-in + custom routines
            val listOutcome = router.routeAndExecute("if what are the available routines") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, listOutcome.routingTier)
            assertTrue(listOutcome.responseText.contains("Morning", ignoreCase = true))
            assertTrue(listOutcome.responseText.contains("Focus", ignoreCase = true))
            assertTrue(listOutcome.responseText.contains("Bedtime", ignoreCase = true))
            assertTrue(listOutcome.responseText.contains("Phone Info Sync", ignoreCase = true))
            assertTrue(listOutcome.responseText.contains("Night Wind Down", ignoreCase = true))

            // 2. Requesting step-by-step list of routines resolves on Tier 1 without LLM loading stall
            val allStepsOutcome = router.routeAndExecute("provide me with list of steps in the routines") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, allStepsOutcome.routingTier)
            assertTrue("Should list Morning routine steps", allStepsOutcome.responseText.contains("Morning Routine", ignoreCase = true))
            assertTrue("Should list Focus routine steps", allStepsOutcome.responseText.contains("Focus Routine", ignoreCase = true))
            assertTrue("Should list Bedtime routine steps", allStepsOutcome.responseText.contains("Bedtime Routine", ignoreCase = true))
            assertTrue("Should list custom recorded routine steps", allStepsOutcome.responseText.contains("Enable Do Not Disturb", ignoreCase = true))
            assertTrue("Should contain numbered steps", allStepsOutcome.responseText.contains("1."))

            // 3. Requesting steps for a single routine returns that routine's detailed numbered steps
            val morningSteps = router.routeAndExecute("what are the steps in the morning routine") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, morningSteps.routingTier)
            assertTrue(morningSteps.responseText.contains("Morning Routine", ignoreCase = true))
            assertTrue(morningSteps.responseText.contains("calendar", ignoreCase = true))
            assertTrue(morningSteps.responseText.contains("1."))

            // 4. LLM knowledge summary exposes built-in and custom routine steps
            val knowledge = executor.buildRoutineKnowledgeSummary()
            assertTrue(knowledge.contains("Morning", ignoreCase = true))
            assertTrue(knowledge.contains("Bedtime", ignoreCase = true))
            assertTrue(knowledge.contains("Night Wind Down", ignoreCase = true))
            assertTrue(knowledge.contains("Enable Do Not Disturb", ignoreCase = true))
        } finally {
            recordedStore.deleteRoutine("test_routine_night_winddown")
            fts.close()
        }
    }

    @Test
    fun `wake word phonetic matching and personalized voice calibration aliases trigger wake reliably`() {
        try {
            // 1. Broadened built-in phonetic variants match common mic/accent mis-transcriptions
            assertEquals("turn on wifi", NovaVoiceForegroundService.extractWakeCommand("hey noba turn on wifi"))
            assertEquals("what time is it", NovaVoiceForegroundService.extractWakeCommand("ey nava what time is it"))
            assertEquals("play music", NovaVoiceForegroundService.extractWakeCommand("hey nora play music"))
            assertEquals("", NovaVoiceForegroundService.extractWakeCommand("hey nola"))

            // 2. Personalized 3-sample calibration learns user-specific ASR transcripts
            NovaVoiceForegroundService.setCustomWakeAliasesForTest(emptyList())
            val learned = NovaVoiceForegroundService.recordCalibrationTranscripts(
                listOf("he over", "Hey Nova!", "a nova")
            )
            assertTrue(learned.contains("he over"))
            assertTrue(learned.contains("a nova"))
            assertEquals("turn on flashlight", NovaVoiceForegroundService.extractWakeCommand("he over turn on flashlight"))
            assertEquals("", NovaVoiceForegroundService.extractWakeCommand("a nova"))
            assertEquals(
                "open spotify",
                NovaVoiceForegroundService.extractWakeCommand("uh he over, open spotify")
            )
            assertNull(NovaVoiceForegroundService.extractWakeCommand("what is the weather today"))
        } finally {
            NovaVoiceForegroundService.setCustomWakeAliasesForTest(emptyList())
        }
    }

    @Test
    fun `turn off do not display resolves to hardware dnd and never misroutes to media playback`() {
        val raw = "Turn off do not display"
        val normalized = InputNormalizer.normalize(raw)

        // 1. Semantic lookup must use word boundaries so "disPLAY" never boosts Media Playback
        val semantic = SemanticLookupEngine.lookup(normalized.cleanedFull)
        assertEquals(
            "Semantic lookup must select device-hardware-control instead of media-playback-control",
            "device-hardware-control",
            semantic.candidateSkills.first().id
        )
        assertFalse(
            "Media playback must not be the top semantic skill for 'do not display'",
            semantic.candidateSkills.first().id == "media-playback-control"
        )

        // 2. Tier-1 regex fast path catches common ASR typo "do not display" as Do Not Disturb
        val fastMatch = RegexFastPathEngine.match(normalized.cleanedFull)
        assertNotNull("Expected Tier-1 hardware reflex match for 'Turn off do not display'", fastMatch)
        assertEquals("set_hardware_state", fastMatch!!.toolCall.toolName)
        assertEquals("dnd", fastMatch.toolCall.args["feature"])
        assertEquals("off", fastMatch.toolCall.args["state"])
    }

    @Test
    fun `browser music preference is blocked, song title with 'in' does not split, and learned process step deletes cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val memory = AgenticMemoryEngine(fts)
        try {
            // 1. Stale or learned browser music preference must never be returned as preferred music app
            fts.upsertAgenticMemory(
                memoryId = "pref_music_app",
                memoryType = AgenticMemoryType.PREFERENCE,
                topic = "pref_music_app",
                content = "Prefers Chrome for music playback",
                confidence = 0.90f,
                timeBucket = TimeOfDayBucket.ANY
            )
            assertNull(
                "getPreferredMusicApp must ignore browser targets",
                memory.getPreferredMusicApp()
            )

            // Learning from a browser-fallback play_in_app turn must not store a browser preference
            memory.learnFromTurn(
                rawQuery = "play christmas in our hearts",
                normalizedQuery = "play christmas in our hearts",
                subCommands = listOf("play christmas in our hearts"),
                toolCalls = listOf(
                    ToolCallRequest(
                        toolName = "play_in_app",
                        args = mapOf(
                            "app_name" to "Chrome",
                            "query" to "christmas in our hearts",
                            "use_browser" to "true"
                        ),
                        shortRefId = "p01",
                        safetyTier = SafetyTier.GREEN
                    )
                ),
                responseText = "Opened in Chrome",
                success = true
            )
            assertNull(
                "Browser fallback must not be saved as preferred music app",
                memory.getPreferredMusicApp()
            )

            // 2. MultiStageActionPlanner must not split "play christmas in our hearts" using fuzzy app matching
            val fakeApps = listOf(
                AppCapabilityRegistry.InstalledAppProfile(
                    appName = "Chrome",
                    packageName = "com.android.chrome",
                    categoryLabel = "Browser/Web",
                    capabilities = listOf("Search web"),
                    howToUse = "Open Chrome to browse the web"
                ),
                AppCapabilityRegistry.InstalledAppProfile(
                    appName = "Heart Rate Monitor",
                    packageName = "com.example.heartrate",
                    categoryLabel = "Health & Fitness",
                    capabilities = listOf("Open"),
                    howToUse = "Open Heart Rate Monitor"
                )
            )
            val planned = MultiStageActionPlanner.plan(
                InputNormalizer.normalize("play christmas in our hearts"),
                installedApps = fakeApps
            )
            assertNull(
                "Song title containing 'in our hearts' must not split into a multi-stage app plan",
                planned
            )

            // 3. Learned process individual step deletion & auto-delete when last step is removed
            val procId = "test_proc_delete_steps"
            fts.upsertLearnedProcess(
                processId = procId,
                name = "Evening Chill",
                triggerPhrase = "evening chill",
                steps = listOf("turn on do not disturb", "dim brightness"),
                preferredApp = "Spotify",
                timeBucket = TimeOfDayBucket.EVENING,
                baseConfidence = 0.85f
            )
            assertTrue(fts.deleteLearnedProcessStep(procId, 0))
            val afterFirstDelete = fts.getLearnedProcesses(limit = 50).firstOrNull { it.processId == procId }
            assertNotNull("Process should still exist with 1 remaining step", afterFirstDelete)
            assertEquals(listOf("dim brightness"), afterFirstDelete!!.steps)

            assertTrue(fts.deleteLearnedProcessStep(procId, 0))
            val afterLastDelete = fts.getLearnedProcesses(limit = 50).firstOrNull { it.processId == procId }
            assertNull("Process should be removed once its final step is deleted", afterLastDelete)
        } finally {
            fts.deleteLearnedProcess("test_proc_delete_steps")
            fts.close()
        }
    }

    @Test
    fun `router recovers cleanly without hanging when tier 2 inference cannot complete`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val modelManager = LlmModelManager(context)
        val tempModelFile = java.io.File(modelManager.llmDir, "test_recovery_stub.litertlm")
        tempModelFile.writeBytes(ByteArray(2048) { 1 })
        val bridge = LlmProcessBridge(context, modelManager)
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)
        val observedModes = mutableListOf<OrbVisualMode>()
        try {
            assertTrue("Test model file should make modelPresent true", bridge.state.value.modelPresent)
            // Query that misses Tier-1 regex and enters Tier-2 skill disclosure while Process B is unbound
            val outcome = router.routeAndExecute(
                "compare the thermal efficiency of Stirling engines versus Carnot cycles"
            ) { _, orbMode, _ ->
                observedModes.add(orbMode)
            }
            assertEquals(RoutingTier.TIER_2_SKILL_DISCLOSURE, outcome.routingTier)
            assertEquals(OrbVisualMode.ERROR_ABORTED, outcome.orbMode)
            assertTrue(outcome.responseText.isNotBlank())
            assertEquals(
                "Final UI status callback must transition out of THINKING/WORKING into ERROR_ABORTED",
                OrbVisualMode.ERROR_ABORTED,
                observedModes.lastOrNull()
            )
        } finally {
            tempModelFile.delete()
            fts.close()
        }
    }

    @Test
    fun `personal chat and jokes bypass web trivia and use fast conversational mode`() {
        val web = com.example.engine.WebKnowledgeResolver

        // Personal/creative assistant queries must NOT be hijacked by Wikipedia/DuckDuckGo trivia
        assertTrue(web.isConversationalOrPersonalChat("what is your favorite food?"))
        assertFalse(web.isGeneralKnowledgeQuestion("what is your favorite food?"))
        assertFalse(web.isInformationalWebFallbackCandidate("what is your favorite food?"))

        assertTrue(web.isConversationalOrPersonalChat("tell me a joke"))
        assertFalse(web.isGeneralKnowledgeQuestion("tell me a joke"))
        assertFalse(web.isInformationalWebFallbackCandidate("tell me a joke"))

        assertTrue(web.isConversationalOrPersonalChat("how are you doing today?"))
        assertFalse(web.isGeneralKnowledgeQuestion("how are you doing today?"))

        // Real factual questions still resolve via web knowledge
        assertFalse(web.isConversationalOrPersonalChat("what is the capital of Japan?"))
        assertTrue(web.isGeneralKnowledgeQuestion("what is the capital of Japan?"))
        assertFalse(web.isConversationalOrPersonalChat("do you know who sang Christmas in Our Hearts?"))
        assertTrue(web.isGeneralKnowledgeQuestion("do you know who sang Christmas in Our Hearts?"))

        // Runtime switches to lean conversational mode for chat/jokes, and keeps tool mode for actions
        assertTrue(
            LiteRtLmRuntimeWrapper.isPureConversationalTurn(
                query = "what is your favorite food?",
                candidateSkillIds = listOf("general-knowledge-qa")
            )
        )
        assertTrue(
            LiteRtLmRuntimeWrapper.isPureConversationalTurn(
                query = "tell me a joke",
                candidateSkillIds = listOf("general-knowledge-qa")
            )
        )
        assertFalse(
            LiteRtLmRuntimeWrapper.isPureConversationalTurn(
                query = "open YouTube Music and play jazz",
                candidateSkillIds = listOf("app-media-streaming")
            )
        )
    }

    @Test
    fun `malformed underscore token glitches and contractions in partial json replies are cleaned properly`() {
        // 1. SentencePiece markers and underscore-joined prose words are normalized cleanly
        val glitched = "Why did the scarecrow_fail_at_his_job? Because he\u2581was outstanding in his field!"
        assertEquals(
            "Why did the scarecrow fail at his job? Because he was outstanding in his field!",
            LiteRtLmRuntimeWrapper.cleanMalformedTokenArtifacts(glitched)
        )

        // 2. Partial/unclosed JSON reply containing apostrophe contractions must NOT truncate at "'"
        val truncatedWithContraction = """{"calls":[],"reply":"Why did the scarecrow win an award? Because he's outstanding in his field!"""
        val parsed = LiteRtLmRuntimeWrapper.parseModelTurnText(truncatedWithContraction)
        assertNotNull("Partial JSON reply with contraction should still recover cleanly", parsed)
        assertEquals(
            "Why did the scarecrow win an award? Because he's outstanding in his field!",
            parsed!!.second
        )
    }

    @Test
    fun `llm presets expose gemma 4, gemma 3n, lfm25, and qwen with accurate vision detection`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = LlmModelManager(context)
        val ids = manager.presetsFor(ModelSlot.LLM).map { it.id }

        assertTrue(ids.contains("gemma_4_e2b"))
        assertTrue(ids.contains("gemma_3n_e2b_int4"))
        assertTrue(ids.contains("lfm25_1_2b_int4_gpu"))
        assertTrue(ids.contains("qwen25_1_5b_q8"))
        assertTrue(ids.contains("lfm2_700m_int4"))

        assertTrue(LiteRtLmRuntimeWrapper.isLikelyVisionModel("gemma-4-E2B-it.litertlm"))
        assertTrue(LiteRtLmRuntimeWrapper.isLikelyVisionModel("gemma-3n-E2B-it-int4.litertlm"))
        assertFalse(LiteRtLmRuntimeWrapper.isLikelyVisionModel("LFM2.5-1.2B-Instruct_int4_gpu.litertlm"))
        assertFalse(LiteRtLmRuntimeWrapper.isLikelyVisionModel("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"))
    }

    @Test
    fun `current time now and natural time date phrasings resolve on Tier 1 and never return trivia`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val bridge = LlmProcessBridge(context, LlmModelManager(context))
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        // Pin clock to Dec 20, 2026 14:30:00 UTC for deterministic assertions
        val fixedCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(2026, java.util.Calendar.DECEMBER, 20, 14, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        com.example.engine.AlexaUtilityEngine.testNowMillisOverride = fixedCal.timeInMillis
        com.example.engine.WebKnowledgeResolver.testAnswerOverride = {
            "TRIVIA_TRAP: Wikipedia article about Time"
        }

        try {
            val timePhrasings = listOf(
                "what is the current time now",
                "current time now",
                "what's the time right now?",
                "what time is it right now",
                "can you tell me the current time?",
                "please check the time",
                "what is time and date"
            )
            for (q in timePhrasings) {
                val res = router.routeAndExecute(q) { _, _, _ -> }
                assertEquals("Expected Tier 1 for '$q'", RoutingTier.TIER_1_REGEX, res.routingTier)
                assertFalse("Must never return trivia for '$q': ${res.responseText}", res.responseText.contains("TRIVIA_TRAP"))
                assertTrue("Expected live time output for '$q': ${res.responseText}", res.responseText.contains(":"))
                assertFalse("WebKnowledgeResolver must reject '$q'", com.example.engine.WebKnowledgeResolver.isGeneralKnowledgeQuestion(q))
            }

            val dateRes = router.routeAndExecute("what is today's date?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, dateRes.routingTier)
            assertTrue("Expected 2026 in date reply: ${dateRes.responseText}", dateRes.responseText.contains("2026"))

            val dayRes = router.routeAndExecute("what day is today?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, dayRes.routingTier)
            assertTrue("Expected Today is in day reply: ${dayRes.responseText}", dayRes.responseText.startsWith("Today is"))

            val tomorrowRes = router.routeAndExecute("what day is tomorrow") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, tomorrowRes.routingTier)
            assertTrue("Expected Tomorrow is: ${tomorrowRes.responseText}", tomorrowRes.responseText.startsWith("Tomorrow is"))

            val countdownRes = router.routeAndExecute("how many days until Christmas?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, countdownRes.routingTier)
            assertTrue("Expected Christmas countdown: ${countdownRes.responseText}", countdownRes.responseText.contains("Christmas"))

            val worldRes = router.routeAndExecute("what time is it in Tokyo?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, worldRes.routingTier)
            assertTrue("Expected Tokyo time: ${worldRes.responseText}", worldRes.responseText.contains("Tokyo"))
            assertTrue("Expected formatted clock in Tokyo reply: ${worldRes.responseText}", worldRes.responseText.contains(":"))
        } finally {
            com.example.engine.AlexaUtilityEngine.testNowMillisOverride = null
            com.example.engine.WebKnowledgeResolver.testAnswerOverride = null
            fts.close()
        }
    }

    @Test
    fun `alexa everyday utilities handle weather, math, tips, conversions, games, spelling, and greetings without breaking routines or chat`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        val bridge = LlmProcessBridge(context, LlmModelManager(context))
        val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
        val router = NovaPipelineRouter(bridge, executor)

        com.example.engine.AlexaUtilityEngine.testWeatherOverride = { city ->
            val place = city.ifBlank { "Manila" }
            "In $place, it's currently 31°C and partly cloudy."
        }

        try {
            // Weather (live + city)
            val weatherManila = router.routeAndExecute("what's the weather in Manila?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, weatherManila.routingTier)
            assertTrue(weatherManila.responseText.contains("Manila"))
            assertTrue(weatherManila.responseText.contains("31°C"))

            val rainCheck = router.routeAndExecute("will it rain today?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, rainCheck.routingTier)
            assertTrue(rainCheck.responseText.contains("31°C"))

            // Offline weather message
            com.example.engine.AlexaUtilityEngine.testWeatherOverride = null
            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = false
            val offlineWeather = router.routeAndExecute("what's the weather?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, offlineWeather.routingTier)
            assertTrue(offlineWeather.responseText.contains("offline", ignoreCase = true))
            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = null

            // Math, percentages, tips, bill split, square root
            val mathRes = router.routeAndExecute("what is 25 times 14?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, mathRes.routingTier)
            assertTrue("Expected 350 in '${mathRes.responseText}'", mathRes.responseText.contains("350"))

            val tipRes = router.routeAndExecute("15% tip on 80") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, tipRes.routingTier)
            assertTrue("Expected 12.00 tip and 92.00 total: ${tipRes.responseText}", tipRes.responseText.contains("12.00") && tipRes.responseText.contains("92.00"))

            val splitRes = router.routeAndExecute("split 1200 by 4") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, splitRes.routingTier)
            assertTrue("Expected 300.00 each: ${splitRes.responseText}", splitRes.responseText.contains("300.00"))

            val pctRes = router.routeAndExecute("what is 20 percent of 250?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, pctRes.routingTier)
            assertTrue("Expected 50: ${pctRes.responseText}", pctRes.responseText.contains("50"))

            val sqrtRes = router.routeAndExecute("square root of 144") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, sqrtRes.routingTier)
            assertTrue("Expected 12: ${sqrtRes.responseText}", sqrtRes.responseText.contains("12"))

            // Unit conversions & everyday kitchen questions
            val tempRes = router.routeAndExecute("convert 100 fahrenheit to celsius") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, tempRes.routingTier)
            assertTrue("Expected 37.78°C: ${tempRes.responseText}", tempRes.responseText.contains("37.78"))

            val distRes = router.routeAndExecute("5 miles in km") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, distRes.routingTier)
            assertTrue("Expected 8.05 km: ${distRes.responseText}", distRes.responseText.contains("8.05"))

            val wtRes = router.routeAndExecute("70 kg to pounds") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, wtRes.routingTier)
            assertTrue("Expected 154.32 pounds: ${wtRes.responseText}", wtRes.responseText.contains("154.32"))

            val kitchenRes = router.routeAndExecute("how many ounces in a cup?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, kitchenRes.routingTier)
            assertTrue("Expected 8 fluid ounces: ${kitchenRes.responseText}", kitchenRes.responseText.contains("8") && kitchenRes.responseText.contains("fluid ounces"))

            // Coin flip, dice, random number, RPS, spelling
            val coinRes = router.routeAndExecute("flip a coin") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, coinRes.routingTier)
            assertTrue(coinRes.responseText.contains("Heads") || coinRes.responseText.contains("Tails"))

            val diceRes = router.routeAndExecute("roll two dice") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, diceRes.routingTier)
            assertTrue(diceRes.responseText.contains("rolled 2 dice"))

            val randRes = router.routeAndExecute("pick a random number between 1 and 50") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, randRes.routingTier)
            assertTrue(randRes.responseText.contains("between 1 and 50"))

            val rpsRes = router.routeAndExecute("play rock paper scissors") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, rpsRes.routingTier)
            assertTrue(rpsRes.responseText.contains("Rock") || rpsRes.responseText.contains("Paper") || rpsRes.responseText.contains("Scissors"))

            val spellRes = router.routeAndExecute("how do you spell accommodation?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, spellRes.routingTier)
            assertTrue(spellRes.responseText.contains("A - C - C - O - M - M - O - D - A - T - I - O - N"))

            // Greetings, thanks, identity, and "what can you do"
            val hiRes = router.routeAndExecute("hello") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, hiRes.routingTier)
            assertTrue(hiRes.responseText.contains("Good"))

            val capsRes = router.routeAndExecute("what can you do?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, capsRes.routingTier)
            assertTrue(capsRes.responseText.contains("time") && capsRes.responseText.contains("weather"))

            // Ensure routines & conversational LLM queries are NOT hijacked by AlexaUtilityEngine
            val goodMorning = RegexFastPathEngine.match("good morning")
            assertEquals("get_routine_steps", goodMorning?.toolCall?.toolName)
            assertNull("Jokes must still go to conversational LLM", RegexFastPathEngine.match("tell me a joke"))
            assertNull("Personal chat must still go to conversational LLM", RegexFastPathEngine.match("what is your favorite food?"))
        } finally {
            com.example.engine.AlexaUtilityEngine.testWeatherOverride = null
            com.example.engine.AlexaUtilityEngine.testNowMillisOverride = null
            com.example.engine.WebKnowledgeResolver.testNetworkAvailableOverride = null
            fts.close()
        }
    }

    @Test
    fun `themed wedding song requests pick varied wedding tracks and accessibility check ignores unrelated previous music`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val picks = (1..24).map {
            val (q, wasRand) = com.example.engine.RandomSongCatalog.resolveEffectiveSongQuery("a wedding song")
            assertTrue("Expected themed query 'a wedding song' to be marked randomized", wasRand)
            q
        }.toSet()
        assertTrue("Expected multiple distinct wedding songs from catalog, got: $picks", picks.size >= 3)
        assertTrue(
            "Wedding song selection must never fall back to Christmas songs: $picks",
            picks.none { it.contains("christmas", ignoreCase = true) || it.contains("jingle", ignoreCase = true) }
        )
        assertTrue(com.example.engine.RandomSongCatalog.isDescriptiveGenreOrMoodQuery("a wedding song"))
        assertTrue(com.example.engine.RandomSongCatalog.isDescriptiveGenreOrMoodQuery("something upbeat for a workout"))
        assertFalse(com.example.engine.RandomSongCatalog.isDescriptiveGenreOrMoodQuery("Christmas in Our Hearts"))

        // Verify verifyAndTriggerMediaPlayback(..., wasAlreadyPlaying = true) clicks the requested wedding song,
        // NOT an unrelated previous Christmas miniplayer button.
        val controller = Robolectric.buildService(NovaAccessibilityService::class.java).create()
        val svc = controller.get()
        val onConnMethod = NovaAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
        onConnMethod.isAccessible = true
        onConnMethod.invoke(svc)

        val root = android.view.accessibility.AccessibilityNodeInfo()
        root.packageName = "com.google.android.apps.youtube.music"
        root.className = "android.widget.FrameLayout"
        root.isVisibleToUser = true

        val wrongMiniplayerBtn = android.view.accessibility.AccessibilityNodeInfo()
        wrongMiniplayerBtn.text = "Play Christmas In Our Hearts - Jose Mari Chan"
        wrongMiniplayerBtn.className = "android.widget.Button"
        wrongMiniplayerBtn.isClickable = true
        wrongMiniplayerBtn.isVisibleToUser = true
        var clickedWrong = false
        Shadows.shadowOf(wrongMiniplayerBtn).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) clickedWrong = true
            true
        }

        val weddingTrackBtn = android.view.accessibility.AccessibilityNodeInfo()
        weddingTrackBtn.text = "Play All of Me - John Legend"
        weddingTrackBtn.className = "android.widget.Button"
        weddingTrackBtn.isClickable = true
        weddingTrackBtn.isVisibleToUser = true
        var clickedWedding = false
        Shadows.shadowOf(weddingTrackBtn).setOnPerformActionListener { action, _ ->
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) clickedWedding = true
            true
        }

        Shadows.shadowOf(root).addChild(wrongMiniplayerBtn)
        Shadows.shadowOf(root).addChild(weddingTrackBtn)
        Shadows.shadowOf(svc).setRootInActiveWindow(root)

        val result = svc.verifyAndTriggerMediaPlayback(
            targetPkg = "com.google.android.apps.youtube.music",
            queryText = "All of Me John Legend",
            appName = "YouTube Music",
            wasAlreadyPlaying = true
        )
        assertTrue(result.success)
        assertFalse("Must NOT click the old Christmas miniplayer", clickedWrong)
        assertTrue("Should click the matching wedding track result", clickedWedding)
        controller.destroy()
    }

    @Test
    fun `embodied self status and device hardware sensors report live telemetry in first person`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val fts = LocalFtsIndexHelper(context)
        try {
            com.example.engine.DeviceSensorTelemetry.testSnapshotOverride = {
                com.example.engine.DeviceSensorTelemetry.TelemetrySnapshot(
                    batteryPercent = 82,
                    isCharging = true,
                    chargeSource = "USB",
                    batteryTempCelsius = 31.4f,
                    batteryHealthLabel = "good",
                    thermalLabel = "cool",
                    availableRamMb = 3200L,
                    totalRamMb = 8192L,
                    freeStorageGb = 48.5,
                    lightLux = 210f,
                    proximityCm = 5.0f,
                    proximityMaxRangeCm = 5.0f,
                    accelMagnitudeG = 1.00f,
                    devicePosture = "resting face up",
                    isMoving = false,
                    gyroRadPerSec = 0.01f,
                    pressureHpa = 1013.2f,
                    ambientTempCelsius = null,
                    magneticMicroTesla = 45f,
                    availableSensorNames = listOf("Accelerometer", "Gyroscope", "Ambient Light", "Proximity", "Barometer"),
                    musicActive = false,
                    wakeWordStatus = com.example.data.WakeWordStatus.LISTENING
                )
            }
            val bridge = LlmProcessBridge(context, LlmModelManager(context))
            val executor = DeviceActionExecutor(context, fts) { _, _, _, _ -> }
            val router = NovaPipelineRouter(bridge, executor)

            // 1. Embodied self-status check
            val statusRes = router.routeAndExecute("what is your current status?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, statusRes.routingTier)
            assertTrue("Expected first-person cool status: ${statusRes.responseText}", statusRes.responseText.contains("cool"))
            assertTrue("Expected battery pct: ${statusRes.responseText}", statusRes.responseText.contains("82%"))
            assertTrue("Expected comfortable indoor light reading: ${statusRes.responseText}", statusRes.responseText.contains("210 lux"))
            assertTrue("Expected pressure reading: ${statusRes.responseText}", statusRes.responseText.contains("1013 hPa"))

            // 2. Explicit sensor focus queries
            val allSensorsRes = router.routeAndExecute("check your sensors") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, allSensorsRes.routingTier)
            assertTrue(allSensorsRes.responseText.contains("Accelerometer") && allSensorsRes.responseText.contains("Barometer"))

            val thermalRes = router.routeAndExecute("how warm are you?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, thermalRes.routingTier)
            assertTrue(thermalRes.responseText.contains("31.4°C"))

            val lightRes = router.routeAndExecute("how bright is it?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, lightRes.routingTier)
            assertTrue(lightRes.responseText.contains("210 lux"))

            val motionRes = router.routeAndExecute("are you moving right now?") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, motionRes.routingTier)
            assertTrue(motionRes.responseText.contains("resting still"))

            // 3. Ensure existing connectivity briefing and conversational chit-chat are preserved
            val connRes = router.routeAndExecute("check connectivity status") { _, _, _ -> }
            assertEquals(RoutingTier.TIER_1_REGEX, connRes.routingTier)
            assertTrue(connRes.responseText.contains("Bluetooth is") && connRes.responseText.contains("Wi-Fi is"))

            val chatMatch = RegexFastPathEngine.match("how are you doing today?")
            assertNull("Conversational greeting should still route beyond regex telemetry", chatMatch)
        } finally {
            com.example.engine.DeviceSensorTelemetry.testSnapshotOverride = null
            fts.close()
        }
    }

    @Test
    fun `concurrent mic session manages ducking audio focus safely during active media`() = runTest {
        val svcController = Robolectric.buildService(NovaVoiceForegroundService::class.java).create()
        val fgs = svcController.get()
        try {
            fgs.setPauseWakeDuringMedia(false)
            fgs.testMediaPlayingOverride = true
            fgs.beginConcurrentMicSession()
            fgs.endConcurrentMicSession(resumeInterruptedMediaIfNeeded = true)
            // Also ensure default pauseWakeDuringMedia=false preference preserves concurrent voice readiness
            fgs.setWakeWordListening(true) {}
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                com.example.data.WakeWordStatus.PAUSED_FOR_MEDIA != NovaVoiceForegroundService.wakeWordStatus.value
            )
        } finally {
            fgs.testMediaPlayingOverride = null
            svcController.destroy()
        }
    }
}







