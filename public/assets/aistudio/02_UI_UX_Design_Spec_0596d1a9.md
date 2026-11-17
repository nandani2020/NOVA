# Local AI Phone Assistant — UI/UX Design Specification
**Form Factor:** Dedicated Snapdragon 8+ Gen 1 Android Phone (Always-On Desk/Bedside Stand & Handheld Mode)  
**Visual Theme:** Atmospheric Dark Mode, Glassmorphism, Ambient Violet-Indigo Palette

---

## 1. Design Philosophy & Dual-Mode Ergonomics

Because this dedicated phone acts as both a **hands-free smart speaker (Alexa-style)** and a **full-control personal phone assistant**, the interface adapts automatically to context:
1. **Ambient Stand Mode (Docked / Charging / Far-Field Voice):** High-contrast, large typography readable from 2–3 meters away, glowing voice orb state feedback, and zero clutter.
2. **Handheld Control Mode (Touch + Voice Hybrid):** Rich frosted-glass cards, live autonomous UI action badges, quick-action chips, and step-by-step confirmation sheets.

---

## 2. Visual Design System

### Color Palette
* **Primary Canvas Background:** Deep Charcoal-to-Indigo Vertical Gradient (`#12131F` → `#262346`)
* **Surface Cards (Glassmorphism):** `rgba(255, 255, 255, 0.06)` with `1px solid rgba(255, 255, 255, 0.12)` border and `20px` backdrop blur
* **Primary Accent (Active States & Voice Glow):** Electric Violet (`#8B5CF6`) to Soft Lavender (`#A78BFA`)
* **Secondary Highlights:** Magenta-Pink (`#EC4899`) inside the 3D voice orb; Cyan-Blue (`#38BDF8`) for local search/data metrics
* **Semantic Status Colors:**
  * Safe / Ready: Emerald (`#10B981`)
  * Confirmation / Caution (Tier Red Action): Amber (`#F59E0B`)
  * Abort / Error: Coral Red (`#EF4444`)

### Typography & Corner Geometry
* **Font Family:** Clean geometric sans-serif (`Plus Jakarta Sans` or `Inter`) with tabular numerals for timers, temperature dials, and system metrics.
* **Card Corner Radius:** `20px` for primary dashboard cards; `999px` (pill) for suggestion chips, mode selectors, and status badges.

---

## 3. Core Screen Flows & Layouts

### Screen 1: Home Assistant & Device Hub
* **Top Header:** Personalized greeting (*"HI, ANASTACIA!"* / *"Good Morning"*) with a circular user avatar and a subtle green `"LOCAL AI READY • 0ms"` status pill.
* **Quick Status Summary Card:** Translucent wide glass card showing key daily context (e.g., next calendar event, unread priority messages, or smart-home energy usage) with a pill-shaped *"View Details"* button.
* **Category Tabs:** Horizontal scrollable tabs (*"FAVOURITES"*, *"PHONE APPS"*, *"SMART HOME"*, *"ROUTINES"*).
* **2-Column Action & Device Grid:** Rounded `20px` glass tiles with minimalist vector icons, live status text, and glowing violet toggle switches.
* **Bottom Navigation Bar:** Frosted dock anchored by a prominent, glowing circular **Microphone Trigger Button** in the center.

### Screen 2: Active Voice & Autonomous Action Overlay
Triggered by wake-word (*"Hey Nova"*), hardware button, or tapping the center mic:
* **Top Quick-Action Chips:** Horizontally scrollable translucent pills showing contextual suggestions (*"Summarize unread texts"*, *"Find my flight PDF"*, *"Order usual coffee"*).
* **Prompt State Header:** Centered uppercase micro-label (`LISTENING...`, `THINKING...`, `NAVIGATING WHATSAPP...`).
* **Luminous 3D Voice Orb:** Centerpiece fluid purple-pink-indigo glowing petal/wave orb that reacts dynamically to voice amplitude and processing tier:
  * *Idle / Listening:* Gentle pulsing violet-lavender wave.
  * *Fast-Path Reflex (<30ms):* Crisp electric flash + instant checkmark.
  * *Autonomous UI Clicking:* Orbiting light ring indicating active screen navigation.
* **Live Transcription & Step Feed:** Clean centered white transcript of the user's speech, followed by compact live step pills when clicking across apps (e.g., `1. Opened Messages ✓` → `2. Found Mom ✓` → `3. Drafting text...`).
* **Bottom Controls:** Concentric glowing mic button flanked by a **Keyboard Input** icon (left) and an immediate **Stop / Cancel (`X`)** kill-switch button (right).

### Screen 3: Precision Control & Detail View (Smart Controls / Timers / Media)
* **Header:** Back chevron, active control title (e.g., *"Air Conditioner"*, *"Focus Timer"*, or *"System Routines"*), and master violet toggle switch.
* **Central Radial Dial:** Large circular interactive dial with glowing white-to-violet progress arc, tick marks, and bold center readout (`- 20°C +` or `25:00`).
* **Mode Pill Selector:** Horizontal pill bar (`Automatic`, `Cool`, `Fan`, `Dry`) with the active option highlighted in solid `#8B5CF6` violet.
* **3-Column Intensity / Preset Cards:** Three rounded cards (`Low`, `Medium`, `High`) where the selected preset illuminates in solid violet with a subtle outer glow.

### Screen 4: Autonomous UI Transparency & Confirmation Sheet (Safety UX)
Whenever the assistant controls a third-party app or approaches a **Tier Red** action (payments, deletions, external sends):
* **Picture-in-Picture Live Mini-View:** Shows a thumbnail of the app screen the assistant is currently tapping, highlighted with a glowing violet bounding box around the target button.
* **Confirmation Card:** Plain-English summary (*"Ready to send ₱450 to Alex via GCash?"* or *"Send email to 3 recipients?"*) with two large thumb-friendly buttons: **Approve (`Confirm`)** and **Abort (`Cancel`)**, plus voice confirmation support (*"Yes, send it"*).

---

## 4. Micro-Interactions & Multimodal Feedback

* **Barge-In Interrupt:** Speaking while the assistant is talking immediately ducks TTS audio within `<100 ms`, morphs the 3D orb back to listening mode, and cancels pending background tool calls.
* **Deterministic Reflex vs. LLM Visual Distinction:** Instant commands (`<30 ms`) skip the thinking animation entirely and display an immediate toast/card update so the UI feels instantaneous.
* **Haptic Signature:** Subtle crisp tick on wake-word detection; double-pulse haptic before requesting Tier Red confirmation.

---

## 5. UI Generation Prompt (For Prototyping / Image Generation)

> **Prompt:**
> Design a sleek, dark-mode mobile AI assistant and phone control interface across three cohesive screens on a deep charcoal-to-indigo gradient background (`#12131F` to `#262346`) with soft frosted-glass cards and vibrant violet-purple accents (`#8B5CF6` to `#A78BFA`).
> 1. **Home Hub Screen:** Personalized greeting (*"HI, ANASTACIA!"*, *"Good Morning"*) with circular avatar, translucent summary card with pill button (*"View Details"*), category tabs (*"FAVOURITES"*, *"PHONE APPS"*), and a 2-column grid of rounded `20px` dark glass cards with line icons and glowing purple toggle switches, anchored by a floating circular purple mic button in a frosted bottom nav bar.
> 2. **Active Voice Assistant Overlay:** Atmospheric indigo-violet backdrop, scrollable top suggestion chips, centered status label (*"SAY SOMETHING"*), a luminous 3D glowing purple-pink petal wave orb in the center, live transcribed voice text below, and a glowing concentric mic button flanked by keyboard and close (`X`) icons.
> 3. **Radial Control Detail Screen:** Clean header with back chevron and master purple toggle, large circular radial dial with glowing white-to-violet arc, horizontal pill mode selector, 3-column intensity cards with solid violet active fill, and persistent bottom nav bar.
