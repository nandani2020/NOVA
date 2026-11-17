# Local AI Phone Assistant — System Architecture
**Target Hardware:** Dedicated Snapdragon 8+ Gen 1 Android Device (8–12 GB LPDDR5 RAM, Adreno 730 GPU)  
**Core Runtime:** Google LiteRT-LM (`gemma-4-E2B-it.litertlm`, 2.58 GB) + `EmbeddingGemma 2` (270M, 165 MB)

---

## 1. Executive Summary & Hardware Tuning (Snapdragon 8+ Gen 1)
This document defines a fully on-device, always-on personal AI assistant modeled after a dedicated smart speaker (like Amazon Alexa) but running natively on a dedicated **Snapdragon 8+ Gen 1** Android smartphone. Unlike cloud assistants, it possesses deep OS-level access to local files, notifications, contacts, and installed apps, and can autonomously navigate and tap any UI screen on the device.

### Why Snapdragon 8+ Gen 1 Fits This Workload
* **TSMC 4nm Thermal Efficiency:** Sustained always-on voice listening and frequent LLM prefill bursts run cool without aggressive thermal throttling.
* **Adreno 730 GPU / Hexagon DSP:** Runs Gemma 4 E2B (`2.58 GB`) with ~250–350 ms prompt prefill and ~25–35 tokens/sec decode, keeping end-to-end voice turn latency under 1.5 seconds.
* **RAM Budget Allocation (8 GB / 12 GB Devices):**
  * **Android OS & Background Services:** ~2.2 GB
  * **Process A (Always-On Voice, Reflexes, Accessibility, Embeddings):** ~280 MB
  * **Process B (`:llm` Isolated LiteRT-LM Engine):** ~2.4 GB (GPU-accelerated)
  * **Headroom for Target Apps (Browser, Maps, Messaging, Media):** ~3.1 GB – 7.1 GB

---

## 2. Two-Process Keep-Alive Architecture

To prevent Android background restrictions and eliminate cold-start delays, the assistant is split into two isolated OS processes:

### Process A: Always-On Reflex & OS Control (`~280 MB RAM`)
* **Core Android Components:**
  * `VoiceInteractionService` (registered as the system's default Digital Assistant).
  * `ForegroundService` (`microphone` + `specialUse` types) for continuous wake-word detection and barge-in without OS 6-hour timeouts.
  * `AccessibilityService` (`canRetrieveWindowContent="true"`, `canPerformGestures="true"`) for full cross-app screen reading, UI tree parsing, and autonomous clicking/scrolling/typing.
  * `NotificationListenerService` for real-time incoming message awareness and quick replies.
* **Zero-Latency Readiness (`T+0 ms`):** Wake-word detection, Tier 1 Regex commands, and Tier 1.5 semantic matching work immediately upon boot—even while Process B is still loading model weights.

### Process B: Isolated AI Engine (`android:process=":llm"`, `~2.4 GB RAM`)
* **Core Components:**
  * Hosts the LiteRT-LM `Engine` (`gemma-4-E2B-it.litertlm`) and a Priority-Aware Serialized Session Mutex.
  * Initializes at `T+500 ms` with a 1-token dummy prefill (`isLlmReady = true`) so steady-state voice queries experience **0 ms cold-start delay**.
* **Fault Tolerance & Memory Management:**
  * **Boot Warmup Queue:** Queries arriving during the 3–5 second initial model load trigger an immediate audio cue (*"Warming up—give me two seconds"*) and auto-execute as soon as `isLlmReady` becomes `true`.
  * **Auto-Recovery (`IBinder.DeathRecipient`):** If extreme RAM pressure kills `:llm`, Process A stays alive, continues handling fast commands, and silently respawns `:llm` once memory stabilizes.

---

## 3. Four-Tier Request & Autonomy Pipeline

```
[ Voice / Wake-Word / Barge-In / Screen Trigger ]
                       │
                       ▼
┌──────────────────────────────────────────────────────────┐
│ TIER 0: Input Normalization & Compound Splitter (Proc A) │
│ • Strips self-corrections ("wait not X, do Y")           │
│ • Splits chained commands ("screenshot and send to Mom") │
│ • Detects conditional logic ("if X then Y" -> LLM)       │
└──────────────────────┬───────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────┐
│ TIER 1: Deterministic Regex Fast-Path (< 5 ms, Proc A)   │
│ • Anchored regex matches (`^...$`) for instant hardware  │
│   toggles, volume, alarms, timers, and media controls    │
└──────────────────────┬───────────────────────────────────┘
                       │ (Miss)
                       ▼
┌──────────────────────────────────────────────────────────┐
│ TIER 1.5: Semantic Lookup (~20 ms, Process A)            │
│ • EmbeddingGemma 2 (270M) encodes user intent            │
│ • Score > 0.92 (single-step) ─► Direct Cache Execution   │
│ • Score 0.40–0.92 ───────────► Top 1–2 Candidate Skills  │
│                                + Top 3 Few-Shot Examples │
└──────────────────────┬───────────────────────────────────┘
                       │ (Proceed to `:llm` Process B)
                       ▼
┌──────────────────────────────────────────────────────────┐
│ TIER 2: Progressive Skill Disclosure (~250 ms, GPU)      │
│ • Injects ONLY top 1–2 candidate skill summaries         │
│ • `<|think|>` disabled by default for instant routing    │
│ • Gemma 4 selects skill(s) or replies conversationally   │
└──────────────────────┬───────────────────────────────────┘
                       │ (Skill Selected)
                       ▼
┌──────────────────────────────────────────────────────────┐
│ TIER 3: Deferred Tool & UI Automation Loop (~1.1–1.5 s)  │
│ • Loads full `SKILL.md` + narrow tools (<= 3 args/tool)  │
│ • Executes Native Android Intents/ContentProviders OR     │
│   Autonomous UI Action Loop (Inspect -> Tap/Type -> Wait)│
│ • Vision Fallback: passes screenshot to Gemma 4 E2B if   │
│   Accessibility node tree lacks labels (games/WebViews)  │
└──────────────────────────────────────────────────────────┘
```

---

## 4. Universal Phone Access & Autonomous UI Engine

To give the assistant full control over every app and file on the phone without relying solely on third-party app APIs, it uses a three-layer action hierarchy:

### Layer 1: Direct OS ContentProviders & Intents (Fastest, Headless)
* **Local Search Indexer:** Background worker indexes on-device contacts, calendar events, SMS/call logs, MediaStore files (PDFs, images, downloads), and installed app packages into a local SQLite FTS5 + vector store.
* **Direct Intents:** Launches apps, dials numbers, sets system alarms, toggles radios, and shares files directly via Android Intent APIs.

### Layer 2: Autonomous Accessibility UI Agent (Any App Control)
When an app has no public Intent API (e.g., ordering food, changing in-app settings, navigating third-party chats, liking/posting), the assistant drives the UI like a human:
1. **Screen State Capture:** `AccessibilityService` dumps a compressed, pruned JSON/XML tree of visible interactive nodes (`[id, text, contentDesc, className, bounds, clickable, editable]`).
2. **Action Execution (`dispatchGesture` & `performAction`):**
   * `ui_click(node_id)` / `ui_tap_xy(x, y)`
   * `ui_input_text(node_id, text)`
   * `ui_scroll(direction)`
   * `ui_global_action(BACK | HOME | RECENTS)`
3. **Closed-Loop Verification:** After every UI action, the agent waits for `TYPE_WINDOW_CONTENT_CHANGED` (with a 1.5s timeout), re-reads the screen state, and decides the next tap until the goal is achieved.

### Layer 3: Multimodal Vision Safety Net
* If an app renders custom OpenGL/Canvas views or unlabeled icons where the Accessibility tree is empty, the agent captures a downscaled `768x768` screenshot via `takeScreenshot()` and feeds it directly into **Gemma 4 E2B's built-in vision encoder** to locate coordinates or read screen text.

---

## 5. Snapdragon 8+ Gen 1 & LiteRT-LM Reliability Guardrails

| Risk / Bottleneck | Architectural Mitigation |
| :--- | :--- |
| **Session Contention (`A session already exists`)** | Priority-aware serialized mutex awaits `conversation.close()` completion before starting a new turn; voice barge-in preempts background tasks. |
| **Tool Accuracy Drop on 4+ Parameters** | Hard schema rule: every tool in `DeferredToolRegistry` exposes **1–3 parameters max**. Complex flows are split into sequential atomic calls. |
| **Adreno FP16 Numeric/Contraction Quirks** | Use short alphanumeric node/entity IDs (e.g., `n14`) instead of long integers; sanitize parsed numbers in Kotlin; normalize contractions before string matching. |
| **Silent PEG Parser `bad_json` Drops** | Empty-turn retry guard automatically re-prompts once with a compact JSON schema reminder if a tool call fails to parse. |
| **Thermal Throttling on Dedicated Stand** | Keep `<|think|>` mode `false` by default; cap background indexing to charging/idle windows; run `EmbeddingGemma 2` on Hexagon/CPU to leave Adreno 730 free for Gemma 4. |

---

## 6. Security, Privacy & Human-in-the-Loop Guardrails

Because the assistant can read all personal data and tap any button on the phone:
* **100% Local Execution:** Zero audio, screen frames, embeddings, or personal index records ever leave the device.
* **Action Risk Tiers:**
  * **Tier Green (Auto-Execute):** Read queries, local search, media playback, navigation, timers, opening apps.
  * **Tier Yellow (Announce & Execute):** Sending routine messages to known contacts, adding calendar events.
  * **Tier Red (Mandatory Voice/Tap Confirmation):** Financial transfers, deleting files/messages, sending emails to external recipients, changing security settings, or placing orders/purchases.
* **Instant Hardware/Voice Kill Switch:** Saying *"Stop"*, *"Cancel"*, or pressing Volume Down immediately aborts any autonomous UI click loop.
