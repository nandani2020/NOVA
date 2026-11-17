# Local AI Phone Assistant — Specific Skills Catalog (`SKILL.md` Specifications)

This catalog defines the modular skills loaded on-demand by **Gemma 4 E2B** via progressive disclosure.  
**Design Rules Applied Across All Skills:**
* Every tool declares **$\le 3$ arguments** to maintain peak structured tool-calling accuracy on Gemma 4 E2B.
* High-frequency commands include `fast_triggers` (Tier 1 Regex) or high-confidence embedding templates (Tier 1.5) for `<30 ms` execution.
* Skills requiring multi-step planning set `requires_thinking: true`; all others keep `<|think|>` `false` for sub-second routing.

---

## Category A: Instant Device & Hardware Reflexes (Tier 1 / 1.5)

### 1. `device-hardware-control`
* **Description:** Toggle Wi-Fi, Bluetooth, Flashlight, Airplane Mode, Do Not Disturb, brightness, or volume.
* **Requires Thinking:** `false`
* **Fast Triggers:** `^(turn on|turn off|toggle) (flashlight|torch|wifi|bluetooth|dnd|do not disturb)$`, `^set (volume|brightness) to (\d+)%?$`, `^(mute|unmute) phone$`
* **Tools (`<= 3 args`):**
  * `set_hardware_state(feature, state)`
  * `set_level(target, percent)`
* **Safety Tier:** Green (Auto-execute)

### 2. `clock-alarms-timers`
* **Description:** Set, cancel, check, or snooze alarms, countdown timers, and stopwatches.
* **Requires Thinking:** `false`
* **Fast Triggers:** `^set (?:a )?timer for (.+)$`, `^set (?:an )?alarm for (.+)$`, `^(stop|snooze) (alarm|timer)$`
* **Tools (`<= 3 args`):**
  * `create_timer(duration_sec, label)`
  * `create_alarm(time_24h, label)`
  * `modify_clock_item(item_id, action)`
* **Safety Tier:** Green (Auto-execute)

### 3. `media-playback-control`
* **Description:** Play, pause, skip, rewind, or search music, podcasts, and videos across active media apps.
* **Requires Thinking:** `false`
* **Fast Triggers:** `^(play|pause|resume|next|skip|previous)(?: track| song| music)?$`
* **Tools (`<= 3 args`):**
  * `media_transport(command)`
  * `play_media_query(query, app_name)`
* **Safety Tier:** Green (Auto-execute)

---

## Category B: Universal Local Search & Personal Memory

### 4. `local-device-search`
* **Description:** Search across all local files, PDFs, screenshots, downloads, notes, contacts, and call history on the phone.
* **Requires Thinking:** `false`
* **Tools (`<= 3 args`):**
  * `search_local_index(query, category)` — searches SQLite FTS5 + `EmbeddingGemma 2` local index (`files`, `contacts`, `notes`, `all`).
  * `read_file_snippet(file_uri, page_or_offset)` — extracts text or passes image URI to Gemma 4 E2B vision encoder.
* **Example Queries:** *"Find that PDF receipt from Cebu Pacific last week"*, *"What's the Wi-Fi password I took a screenshot of?"*
* **Safety Tier:** Green (Auto-execute)

### 5. `personal-memory-vault`
* **Description:** Save and recall personal facts, parking spots, locker codes, preferences, and household reminders locally.
* **Requires Thinking:** `false`
* **Tools (`<= 3 args`):**
  * `save_memory_note(key, value)`
  * `recall_memory_note(query)`
* **Example Queries:** *"Remember I parked on Basement 2 pillar B4"*, *"Where did I park?"*
* **Safety Tier:** Green (Auto-execute)

---

## Category C: Communications & Scheduling

### 6. `messaging-omni-send`
* **Description:** Read incoming notifications and send SMS, WhatsApp, Messenger, Viber, or Telegram messages.
* **Requires Thinking:** `false`
* **Tools (`<= 3 args`):**
  * `search_contacts(query)`
  * `read_recent_messages(contact_id, limit)`
  * `send_message(recipient_id, message_body, app_package)`
* **Safety Guardrail:** Tier Yellow for known contacts; Tier Red confirmation if sending attachments or messaging an unknown number.

### 7. `phone-calls-manager`
* **Description:** Place voice/video calls, answer or decline incoming calls on speakerphone, and check missed calls.
* **Requires Thinking:** `false`
* **Fast Triggers:** `^call (.+)$`, `^(answer|decline|reject) call$`
* **Tools (`<= 3 args`):**
  * `search_contacts(query)`
  * `place_call(contact_id, speakerphone)`
  * `handle_incoming_call(action)`
* **Safety Tier:** Green (Auto-execute)

### 8. `calendar-schedule-manager`
* **Description:** Check daily schedule, find free slots, and create or reschedule local calendar events.
* **Requires Thinking:** `false`
* **Tools (`<= 3 args`):**
  * `query_calendar_events(start_iso, end_iso)`
  * `create_calendar_event(title, start_iso, duration_min)`
* **Safety Tier:** Green (Read) / Yellow (Create)

---

## Category D: Autonomous Cross-App UI Automation (Any App Control)

### 9. `ui-autonomous-navigator`
* **Description:** Open any installed app and autonomously inspect, tap, scroll, and type on screen to complete tasks inside apps without APIs.
* **Requires Thinking:** `true`
* **Tools (`<= 3 args`):**
  * `launch_app_and_inspect(app_name)` — opens app and returns pruned Accessibility UI node tree (`[id, label, type, clickable]`).
  * `ui_interact(action_type, target_node_id, input_text)` — executes `CLICK`, `TYPE`, `SCROLL_DOWN`, `SCROLL_UP`, or `BACK` and returns updated screen tree.
  * `ui_vision_fallback(question)` — captures screenshot and queries Gemma 4 E2B's built-in vision encoder when UI nodes lack text labels.
* **Safety Guardrail:** Automatically pauses and triggers the **Screen 4 Confirmation Sheet** (Tier Red) before clicking any button labeled *Pay*, *Send*, *Confirm Order*, *Delete*, or *Transfer*.

### 10. `screen-context-assistant`
* **Description:** Read, summarize, translate, or extract information from whatever app is currently open on the phone screen.
* **Requires Thinking:** `false`
* **Fast Triggers:** `^(what's on my screen|summarize this screen|read this page|translate this screen)$`
* **Tools (`<= 3 args`):**
  * `capture_current_screen(include_screenshot)` — grabs active Accessibility text tree + optional downscaled screenshot for Gemma 4 E2B multimodal analysis.
* **Safety Tier:** Green (Auto-execute)

---

## Category E: Multi-Step Workflows & Smart Home

### 11. `smart-home-controller`
* **Description:** Control local smart-home devices (lights, AC temperature/mode/swing, TV, router) via local Matter/HTTP bridges or companion apps.
* **Requires Thinking:** `false`
* **Fast Triggers:** `^turn (on|off) (?:the )?(lights?|ac|air conditioner|tv|router)(?: in (?:the )?(.+))?$`
* **Tools (`<= 3 args`):**
  * `control_smart_device(device_name, command, value)`
  * `get_device_status(device_name)`
* **Safety Tier:** Green (Auto-execute)

### 12. `compound-routine-orchestrator`
* **Description:** Execute multi-step personal routines combining local search, app navigation, messaging, and device settings.
* **Requires Thinking:** `true`
* **Tools (`<= 3 args`):**
  * `get_routine_steps(routine_name)`
  * `store_turn_artifact(key, value)`
* **Example Workflows:**
  * *"Good morning"* → Unmute phone + read today's calendar + summarize overnight unread messages + turn on coffee maker/lights.
  * *"Find my latest electricity bill PDF and send the total due to Dad"* → `local-device-search` → extract amount → `messaging-omni-send`.
* **Safety Tier:** Inherits highest risk tier of sub-steps (Tier Red if any step sends money/external emails).
