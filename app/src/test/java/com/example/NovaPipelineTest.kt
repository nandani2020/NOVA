package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.DeviceAccountHelper
import com.example.data.DeviceActionExecutor
import com.example.data.LlmModelManager
import com.example.data.LocalFtsIndexHelper
import com.example.data.ModelSlot
import com.example.data.OrbVisualMode
import com.example.data.RoutingTier
import com.example.data.SafetyTier
import com.example.data.ToolCallRequest
import com.example.data.UiNodeSnapshot
import com.example.engine.AppCapabilityRegistry
import com.example.engine.AutonomousDialogResolver
import com.example.engine.DeferredToolRegistry
import com.example.engine.InputNormalizer
import com.example.engine.NovaPipelineRouter
import com.example.engine.RegexFastPathEngine
import com.example.engine.SemanticLookupEngine
import com.example.engine.SkillCatalog
import com.example.processA.EmbeddingGemmaRuntime
import com.example.processA.LlmProcessBridge
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
        assertEquals(16, SkillCatalog.allSkills.size)
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
    fun `model manager presets include only Gemma 4 E2B and EmbeddingGemma 2`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = LlmModelManager(context)
        val presetIds = modelManager.presets.map { it.id }
        assertEquals(listOf("gemma_4_e2b", "embedding_gemma_2_270m"), presetIds)
        assertFalse(modelManager.presets.any { it.id.contains("qwen", ignoreCase = true) })
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
        assertEquals("media_transport", genericPlay?.toolCall?.toolName)

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
}
