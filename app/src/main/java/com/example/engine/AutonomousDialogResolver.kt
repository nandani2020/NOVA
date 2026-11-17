package com.example.engine

import com.example.data.DeviceAccountHelper
import com.example.data.UiNodeSnapshot

/**
 * Evaluates an active screen's UI nodes and determines whether Nova can autonomously
 * proceed past a non-destructive blocker (popup, permission prompt, app chooser/dispatch,
 * or single/recorded account confirmation) without troubling the user.
 */
object AutonomousDialogResolver {

    enum class ResolutionKind {
        ACCOUNT_SELECTION,
        APP_DISPATCH,
        PERMISSION_OR_POPUP
    }

    data class AutoResolutionDecision(
        val targetNode: UiNodeSnapshot,
        val kind: ResolutionKind,
        val stepSummary: String,
        val detectedAccount: String? = null
    )

    private val emailRegex = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

    private val accountHeaderKeywords = listOf(
        "choose an account",
        "select an account",
        "select account",
        "pick an account",
        "use this account",
        "continue as",
        "sign in with",
        "confirm your account",
        "confirm account",
        "add account"
    )

    private val benignPopupLabelsOrdered = listOf(
        "while using the app",
        "only this time",
        "allow all",
        "allow",
        "just once",
        "continue",
        "accept & continue",
        "accept and continue",
        "accept all",
        "got it",
        "ok",
        "okay",
        "yes, i'm in",
        "i agree",
        "agree",
        "next",
        "done",
        "skip",
        "not now",
        "no thanks",
        "maybe later",
        "dismiss",
        "close"
    )

    private val chooserIgnoreLabels = setOf(
        "open with",
        "complete action using",
        "share with",
        "choose an app",
        "just once",
        "always",
        "cancel",
        "use a different app"
    )

    fun evaluateScreen(
        nodes: List<UiNodeSnapshot>,
        windowPackage: String?,
        recordedAccount: String?,
        appHint: String? = null
    ): AutoResolutionDecision? {
        if (nodes.isEmpty()) return null

        val pkgLower = windowPackage.orEmpty().lowercase()
        val combinedText = nodes.joinToString(" ") { it.label.lowercase() }

        // Guard: Never auto-dismiss screens that contain genuine Tier Red risky actions
        // (unless this is strictly an OS permission/resolver/account dialog).
        val isOsSystemDialog = pkgLower.contains("permissioncontroller") ||
            pkgLower.contains("packageinstaller") ||
            pkgLower.contains("intentresolver") ||
            pkgLower == "android" ||
            pkgLower.contains("google.android.gms")

        val hasRiskyAction = nodes.any { n ->
            n.clickable && DeferredToolRegistry.isRedButtonLabel(n.label) && !isSafeConfirmationLabel(n.label)
        }
        if (hasRiskyAction && !isOsSystemDialog) {
            return null
        }

        // 1. Account selection or simple account confirmation
        val emailNodes = nodes.mapNotNull { node ->
            val match = emailRegex.find(node.label)?.value
            if (match != null) node to match else null
        }
        val looksLikeAccountScreen = accountHeaderKeywords.any { combinedText.contains(it) } ||
            (pkgLower.contains("gms") && emailNodes.isNotEmpty())

        if (looksLikeAccountScreen || emailNodes.size == 1 && hasClickableButton(nodes, setOf("continue", "ok", "confirm"))) {
            // Pick matching recorded account, or auto-pick when there's 1 account (or first visible account)
            val matchedRecorded = if (!recordedAccount.isNullOrBlank()) {
                emailNodes.firstOrNull { (_, email) -> email.equals(recordedAccount, ignoreCase = true) }
            } else null

            val chosenAccountPair = matchedRecorded ?: emailNodes.firstOrNull()
            if (chosenAccountPair != null && chosenAccountPair.first.clickable) {
                return AutoResolutionDecision(
                    targetNode = chosenAccountPair.first,
                    kind = ResolutionKind.ACCOUNT_SELECTION,
                    stepSummary = "Selected account ${chosenAccountPair.second}",
                    detectedAccount = DeviceAccountHelper.extractCleanAccount(chosenAccountPair.second)
                )
            }

            // Account is already shown (not clickable row itself), click Continue / OK / Confirm
            val confirmBtn = findMatchingButton(nodes, listOf("continue as", "continue", "ok", "confirm", "yes", "done"))
            if (confirmBtn != null) {
                return AutoResolutionDecision(
                    targetNode = confirmBtn,
                    kind = ResolutionKind.ACCOUNT_SELECTION,
                    stepSummary = "Confirmed account automatically",
                    detectedAccount = emailNodes.firstOrNull()?.second
                )
            }
        }

        // 2. System App Dispatch / Resolver Chooser ("Open with" / "Just once" / "Always")
        val isChooser = pkgLower == "android" ||
            pkgLower.contains("intentresolver") ||
            pkgLower.contains("resolver") ||
            combinedText.contains("open with") ||
            combinedText.contains("complete action using")

        if (isChooser) {
            val justOnce = findMatchingButton(nodes, listOf("just once", "continue", "open"))
            if (justOnce != null) {
                return AutoResolutionDecision(
                    targetNode = justOnce,
                    kind = ResolutionKind.APP_DISPATCH,
                    stepSummary = "Proceeded with app dispatch"
                )
            }
            val candidateApps = nodes.filter { node ->
                node.clickable &&
                    !node.editable &&
                    chooserIgnoreLabels.none { node.label.lowercase() == it || node.label.lowercase().startsWith("$it ") }
            }
            val preferredApp = if (!appHint.isNullOrBlank()) {
                candidateApps.firstOrNull { it.label.contains(appHint, ignoreCase = true) }
            } else null
            val chosenApp = preferredApp ?: candidateApps.firstOrNull()
            if (chosenApp != null) {
                return AutoResolutionDecision(
                    targetNode = chosenApp,
                    kind = ResolutionKind.APP_DISPATCH,
                    stepSummary = "Picked ${chosenApp.label}"
                )
            }
        }

        // 3. OS Permission Prompts & Benign Informational Pop-Ups
        val looksLikePopupOrPermission = isOsSystemDialog ||
            combinedText.contains("allow ") ||
            combinedText.contains("turn on") ||
            combinedText.contains("cookie") ||
            combinedText.contains("whats new") ||
            combinedText.contains("what's new") ||
            combinedText.contains("welcome") ||
            combinedText.contains("got it") ||
            combinedText.contains("notifications") ||
            combinedText.contains("update available")

        if (looksLikePopupOrPermission) {
            val btn = findMatchingButton(nodes, benignPopupLabelsOrdered)
            if (btn != null) {
                return AutoResolutionDecision(
                    targetNode = btn,
                    kind = ResolutionKind.PERMISSION_OR_POPUP,
                    stepSummary = "Handled prompt (${btn.label})"
                )
            }
        }

        return null
    }

    private fun hasClickableButton(nodes: List<UiNodeSnapshot>, labels: Set<String>): Boolean =
        nodes.any { n ->
            n.clickable && labels.any { lbl -> cleanLabel(n.label) == lbl || cleanLabel(n.label).startsWith("$lbl ") }
        }

    private fun findMatchingButton(
        nodes: List<UiNodeSnapshot>,
        preferredOrdered: List<String>
    ): UiNodeSnapshot? {
        val clickables = nodes.filter { it.clickable && !it.editable }
        for (keyword in preferredOrdered) {
            val exact = clickables.firstOrNull { cleanLabel(it.label) == keyword }
            if (exact != null && isSafeConfirmationLabel(exact.label)) return exact

            val prefix = clickables.firstOrNull {
                val c = cleanLabel(it.label)
                c.startsWith("$keyword ") || (keyword.length >= 6 && c.contains(keyword))
            }
            if (prefix != null && isSafeConfirmationLabel(prefix.label)) return prefix
        }
        return null
    }

    private fun cleanLabel(label: String): String =
        label.replace(Regex("\\[(ON|OFF|[^\\]]+)\\]"), "").trim().lowercase()

    private fun isSafeConfirmationLabel(label: String): Boolean {
        val lower = label.lowercase()
        val dangerousWords = listOf(
            "pay", "purchase", "order", "checkout", "transfer", "send money",
            "delete", "erase", "remove account", "unsubscribe", "charge"
        )
        return dangerousWords.none { lower.contains(it) }
    }
}
