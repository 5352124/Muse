<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="app/src/main/res/drawable/ic_muse_logo.png">
    <img src="app/src/main/res/drawable/ic_muse_logo.png" width="96" height="96" alt="Muse">
  </picture>
</p>

<h1 align="center">Muse</h1>

<p align="center">
  <b>Your AI Companion That Remembers</b><br>
  <i>4-tier memory · Multi-model · Offline-first · Extensible</i>
</p>

<p align="center">
  <a href="README.md">中文</a> · <b>English</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(minSdk%2026)-brightgreen" alt="Min SDK">
  <img src="https://img.shields.io/badge/Kotlin-2.4-purple" alt="Kotlin">
  <img src="https://img.shields.io/badge/Compose-Material%203-ff69b4" alt="Compose">
  <a href="https://github.com/5352124/Muse/actions/workflows/ci.yml"><img src="https://github.com/5352124/Muse/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/5352124/Muse/releases/latest"><img src="https://img.shields.io/github/v/release/5352124/Muse?include_prereleases" alt="Latest release"></a>
</p>

<p align="center">
  <a href="#what-is-muse">What is Muse</a> ·
  <a href="#screenshots">Screenshots</a> ·
  <a href="#features">Features</a> ·
  <a href="#quick-start">Quick Start</a> ·
  <a href="#license">License</a>
</p>

---

## What is Muse

Most AI apps treat every conversation like meeting a stranger -- no memory, no continuity, no personality. Muse is different.

Muse is a local-first AI companion app built for Android. It builds a persistent relationship with you through a 4-tier progressive memory system that remembers your preferences, your history, and what matters to you. It lets you choose the underlying model freely (OpenAI, Anthropic, Gemini, DeepSeek, or any OpenAI-compatible endpoint). It speaks, searches the web, executes tools, and even proactively starts a conversation when it hasn't heard from you in a while.

Muse has a unique capability: before each reply, it writes an "inner monologue" (Mood) with four dimensions -- Vibe, Sparks, Reflections, and Will. These thoughts are not shown in the main reply (collapsed by default), but you can expand them to glimpse the AI's genuine thinking process: its current feeling, passing associations, self-doubts, and what it wants to do next.

Everything works offline by default. No account required. No data leaves your device unless you explicitly enable it.

---

## Screenshots

| Conversation | Memory System | Theme Picker |
|:-----------:|:-------------:|:------------:|
| ![Conversation](screenshots/%E5%AF%B9%E8%AF%9D.jpg) | ![Memory](screenshots/%E8%AE%B0%E5%BF%86%E7%B3%BB%E7%BB%9F.jpg) | ![Theme](screenshots/%E4%B8%BB%E9%A2%98%E7%B3%BB%E7%BB%9F.jpg) |

| Knowledge Base | Stickers | Settings |
|:--------------:|:--------:|:--------:|
| ![Knowledge](screenshots/%E7%9F%A5%E8%AF%86%E5%BA%93.jpg) | ![Stickers](screenshots/%E8%A1%A8%E6%83%85%E5%8C%85.jpg) | ![Settings](screenshots/%E8%AE%BE%E7%BD%AE.jpg) |

| Assistant Resources | Data Import |
|:------------------:|:-----------:|
| ![Resources](screenshots/%E5%8A%A9%E6%89%8B%E8%B5%84%E6%BA%90.jpg) | ![Import](screenshots/%E6%95%B0%E6%8D%AE%E5%AF%BC%E5%85%A5.jpg) |

---

## Features

### Memory System

Muse has a 4-tier memory architecture, progressing from short-term conversation to long-term deep processing.

```
Conversation --> Fact Extraction --> Rolling Summary --> Compile & Aggregate --> Deep Processing
```

- **Critical facts never decay**: medical info, financial data, core identity -- facts with importance >= 2 are protected and never fade over time
- **Routine info expires naturally**: ordinary preferences and chit-chat decay automatically as usage frequency drops
- **Traceable origin**: every memory is tagged with source session and ingestion time
- **Fully controllable**: adjust importance, delete, and filter memories manually in the memory panel

### Multi-Provider Models

20+ built-in providers across three categories:

| Category | Providers |
|----------|-----------|
| International | OpenAI, Anthropic, Gemini, Groq, Together, Mistral, OpenRouter, DeepInfra, Fireworks |
| Chinese providers | DeepSeek, Qwen, GLM (Zhipu), Moonshot, Doubao, Baichuan, Lingyi, StepFun |
| Proxy | OpenCode, API2D, AIHubMix, DeepBricks + custom templates |

Model IDs are fetched dynamically from each provider's `/models` endpoint -- no hardcoded lists, no app updates needed when new models launch.

### Mood System -- The Four Dimensions of Thought

Before each reply, Muse generates a `mood` block -- its "inner monologue." These thoughts are not the final reply but the raw thinking that produces it. In the interface, mood is collapsed into a card by default. Expanding it reveals four dimensions:

- **Vibe**: The AI's immediate feeling and emotional state. Light, sharp, contemplative? A single sentence capturing the current mood.
- **Sparks**: Spontaneous associations and imagery that surface naturally. These sparks diverge in direction -- a metaphor, a memory, a new angle, an unexpected connection. Each spark charts a different path.
- **Reflections**: Self-doubts, uncertainties, and insights worth questioning. Not final answers, but the hesitation and curiosity during the thinking process.
- **Will**: The intent and desire crystallized after feeling (Vibe), diverging (Sparks), and reflecting (Reflections). Where the AI wants to go, what it wants to do.

These four dimensions progress from intuition to action: feel first (Vibe), then diverge (Sparks), then reflect (Reflections), finally crystallize into intent (Will). Every reply becomes more than text generation -- it is a complete thought process made visible.

### Multi-Agent Collaboration

Create multiple assistants with different personalities and specialties, delegating tasks anytime during a conversation:

- Type `@assistant-name` in the input bar to delegate
- Task cards visualize the execution status of each delegation step
- Team mode supports multi-assistant round-robin collaboration

### Skill System + MCP Protocol

- 20+ built-in tools: file I/O, web search, knowledge base, calendar, clipboard, calculator, SMS, alarms, stickers, and more
- `.skill.json` import: create and share custom Skills with parameter schemas
- MCP Protocol: connect external MCP Servers to dynamically extend tool capabilities (OAuth auth, SSE transport, auto-discovery)

### Interaction & Media

- **Streaming voice recognition**: DashScope Paraformer / Step Whisper API; real-time transcription, long-press to record, swipe up to cancel, live waveform
- **Multimodal input**: ML Kit offline Chinese OCR; PDF parsing; auto-detects TXT/DOCX/EPUB; built-in DALL-E / Gemini image generation
- **Web search**: Jina AI Reader (Markdown summaries), Bing (Jsoup structured extraction), SearXNG/Tavily/custom endpoints
- **Proactive messaging**: sends when you have been out of touch; continuously adjustable send interval, time-window control, Agent-session only
- **Text-to-speech**: system TTS / cloud TTS (OpenAI/MiniMax/Edge); per-assistant rate/pitch/language; routing to speaker/earpiece/Bluetooth

### Theme System

6 complete themes, each with light and dark variants:

| Theme | Light | Dark |
|:------|:-----:|:----:|
| Warm Paper (default) | Yes | Yes |
| Sakura | Yes | Yes |
| Ocean | Yes | Yes |
| Spring | Yes | Yes |
| Autumn | Yes | Yes |
| AMOLED | Yes | Yes |

Plus 8 colorblind-friendly palettes for custom themes. Every theme fully defines all Material 3 color roles.

### Platform Capabilities

- Home screen widgets: Glance Compose -- one-tap new conversation
- Embedded web server: Ktor + JWT + mDNS -- local network API access
- Config import: one-tap migration from CherryStudio / Chatbox
- Backup & restore: local file + S3 / WebDAV cloud sync
- Full-text search: Room FTS5 -- instant conversation history retrieval
- Sticker library: import zip archives, auto-categorize, probability-based auto-send
- Markdown rich text rendering: code highlighting (20+ languages), KaTeX math formulas, Mermaid diagrams

### Security & Privacy

- App PIN lock (exponential backoff: 5 failures -> 30s lockout); Deep Links blocked during lockout
- Sensitive config encrypted via Android Keystore (AES-256-GCM)
- Cloud backup encrypted with user-defined password (PBKDF2 + AES-256-GCM)
- WebView sanitizes LLM output -- strips iframe, form, javascript: pseudo-protocols
- All conversations/memories/knowledge base stored in local Room database -- no telemetry, no analytics, no data collection
- Network features off by default, opt-in only
- Crash logs stored locally only -- exportable via email in safe mode

---

## Quick Start

> Want to try it first? Just [download the latest APK](https://github.com/5352124/Muse/releases/latest) and install -- no need to build yourself.

### Prerequisites

- Android 8.0 (API 26) or later device
- An API key for any AI provider (OpenAI / Gemini / DeepSeek, etc.)

### Build & Install (for developers)

```bash
git clone https://github.com/5352124/Muse.git
cd Muse

# Debug build
./gradlew :app:assembleDebug

# Install to a connected device
./gradlew :app:installDebug

# Release build
./gradlew :app:assembleRelease
```

APK output: `app/build/outputs/apk/release/app-{abi}-release.apk`

### First Run

1. Open the app -- onboarding introduces core features
2. Set your name and your assistant's name
3. Add an AI provider API key (use a built-in template for quick setup)
4. Start chatting -- from now on, Muse remembers everything

---

## License

Muse is licensed under the **GNU General Public License v3** (GPL v3). Full license text in [LICENSE](LICENSE). Third-party dependency licenses in [NOTICE](NOTICE).