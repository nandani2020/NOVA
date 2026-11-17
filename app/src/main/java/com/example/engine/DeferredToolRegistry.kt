package com.example.engine

import com.example.data.SafetyTier
import com.example.data.ToolCallRequest
import com.example.data.ToolDefinition

/**
 * Tier 3: Deferred Tool Registry & Safety Tier Guardrail.
 * Enforces the strict <= 3 parameters per tool rule across all 12 skills,
 * and classifies dynamic UI clicks / external sends into Tier Red when sensitive.
 */
object DeferredToolRegistry {

    private val toolsByName: Map<String, ToolDefinition> =
        SkillCatalog.allSkills
            .flatMap { it.tools }
            .associateBy { it.name }

    private val redActionKeywords = setOf(
        "pay now", "pay ", "pay$", "checkout", "confirm order", "place order",
        "transfer", "send money", "confirm payment", "send payment",
        "delete", "erase", "send email", "purchase", "remove account", "factory reset"
    )

    private val benignAutoLabels = setOf(
        "allow", "while using the app", "only this time", "allow all",
        "just once", "always", "continue", "got it", "ok", "okay",
        "next", "done", "skip", "not now", "no thanks", "maybe later",
        "dismiss", "close", "accept & continue", "accept and continue", "accept all"
    )

    fun getAllRegisteredTools(): List<ToolDefinition> = toolsByName.values.toList()

    fun getToolsForSkills(skillIds: List<String>): List<ToolDefinition> {
        return skillIds
            .mapNotNull { SkillCatalog.findById(it) }
            .flatMap { it.tools }
            .distinctBy { it.name }
    }

    fun validateToolCall(call: ToolCallRequest): ToolCallRequest {
        val def = toolsByName[call.toolName]
            ?: throw IllegalArgumentException("Unknown deferred tool: ${call.toolName}")
        require(call.args.size <= 3) {
            "Tool '${call.toolName}' violated max 3 arguments cap"
        }
        val requiredParams = def.parameters.filter { it.required }.map { it.name }
        for (req in requiredParams) {
            require(call.args.containsKey(req)) {
                "Tool '${call.toolName}' missing required arg '$req'"
            }
        }
        val dynamicTier = evaluateSafetyTier(call, def.safetyTier)
        return call.copy(safetyTier = dynamicTier)
    }

    fun evaluateSafetyTier(call: ToolCallRequest, baseTier: SafetyTier): SafetyTier {
        val joinedArgs = call.args.values.joinToString(" ").lowercase()
        if (call.toolName == "ui_act") {
            val action = call.args["action"]?.uppercase()
            val textArg = call.args["text"].orEmpty()
            if (action == "CLICK" && (isRedButtonLabel(textArg) || isRedButtonLabel(joinedArgs))) {
                return SafetyTier.RED
            }
            return SafetyTier.GREEN
        }
        if (call.toolName == "ui_open_and_scan" || call.toolName == "launch_app" || call.toolName == "list_installed_apps") {
            return SafetyTier.GREEN
        }
        if (redActionKeywords.any { joinedArgs.contains(it) }) {
            return SafetyTier.RED
        }
        return baseTier
    }

    fun isRedButtonLabel(label: String): Boolean {
        val clean = label.replace(Regex("\\[(ON|OFF|[^\\]]+)\\]"), "").trim().lowercase()
        if (clean.isEmpty()) return false
        if (clean in benignAutoLabels || clean.contains("@")) return false
        if (clean == "pay") return true
        return redActionKeywords.any { clean.contains(it) }
    }
}
