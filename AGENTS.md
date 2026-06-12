# Onion Product Concept

## One-Line Concept

Onion is a native Android app for making small apps inside the app: users describe what they want, a local LLM generates an HTML app, and Onion runs it instantly in a WebView like a real app.

## Product Identity

Onion is not a code editor first. It is a personal app workshop.

The core feeling should be: "I can make a tiny useful app for myself in a few minutes." Users should not feel like they are configuring a developer tool. They should feel like they are talking to a practical, calm assistant that turns ideas into working mini-apps.

Onion should focus on small, complete, personal utilities:

- habit trackers
- calculators
- planners
- checklists
- journaling tools
- flashcards
- simple games
- dashboards
- personal templates

The app should make creation feel approachable, but the output should feel solid enough to use repeatedly.

## Core User Flow

1. User describes the app they want to make.
2. Onion asks concise follow-up questions only when needed.
3. Local LLM generates a single HTML app with embedded CSS and JavaScript.
4. Onion previews the generated app in a WebView.
5. User can ask for changes in natural language.
6. Onion regenerates or patches the HTML.
7. User saves the mini-app locally.
8. Later, saved apps can be launched from the Onion home screen.

Server-based sharing is a future feature. The current product should be designed so sharing can be added later without making the first version dependent on accounts, feeds, or cloud storage.

## App Personality

Onion should feel:

- calm, clear, and helpful
- creative without being childish
- maker-friendly without exposing unnecessary technical complexity
- lightweight and fast
- private by default
- optimistic about iteration

Avoid making Onion feel like:

- a heavy IDE
- a chatbot wrapper with a preview bolted on
- a template marketplace too early
- a social app before the creation loop is excellent
- a generic AI assistant

The user should always understand what state they are in: describing, generating, previewing, editing, or saving.

## Design Concept

Onion's design metaphor is layers.

An onion has layers, and so does the product:

- idea layer: what the user wants
- generation layer: what the model creates
- preview layer: what the user can touch
- saved app layer: what becomes part of the user's personal toolkit
- future community layer: what can be shared, remixed, and improved

Use this metaphor subtly. Do not overuse onion illustrations, onion jokes, or food-themed UI. The product should feel modern and useful, not novelty-driven.

## Visual Direction

The UI should be quiet, tactile, and focused.

Recommended visual traits:

- warm neutral base colors with fresh green accents
- high contrast for reading and editing
- rounded but not overly soft surfaces
- clear separation between chat, preview, and saved apps
- compact controls for repeated use
- motion that communicates progress, generation, and layer transitions

Avoid:

- loud gradients as the main identity
- excessive purple AI styling
- decorative cards everywhere
- mascot-heavy UI
- cluttered code-editor aesthetics

Suggested palette direction:

- background: warm off-white or very light gray
- surface: white or near-white
- primary accent: onion green
- secondary accent: muted yellow-green
- text: near-black charcoal
- status/error colors: conventional, accessible red/yellow/green

## Main Screens

### Home

Purpose: launch saved mini-apps and start creating a new one.

Should include:

- saved app list or grid
- prominent create action
- recent apps
- local-only/private cue
- future-ready area for shared or discovered apps, hidden until needed

### Create

Purpose: turn intent into an app.

Should include:

- natural-language prompt input
- optional structured suggestions
- generation state
- minimal model/status feedback
- clear cancel/retry path

The create screen should not expose raw model parameters in the normal path.

### Preview

Purpose: run the generated HTML app in a WebView.

Should include:

- full app preview
- edit/request-change entry point
- save action
- reset/regenerate action
- safe error state if generated code fails

The preview should feel like using the created app, not looking at a screenshot.

### App Detail

Purpose: manage one saved mini-app.

Should include:

- launch
- rename
- edit prompt/history
- duplicate
- delete
- export/share later

## MVP Product Boundary

The first useful version should prioritize:

- local prompt-to-HTML generation
- WebView preview and execution
- local save/load of generated apps
- iterative natural-language changes
- basic app metadata: title, description, created date, updated date
- safe handling of broken generated HTML

Defer:

- user accounts
- server sync
- public gallery
- payments
- collaborative editing
- complex project file trees
- native-code generation

## Local LLM Principle

Local generation is part of the product promise. Onion should feel private and immediate.

The app should be designed around the constraints of local models:

- prompts should be concise and structured
- generated apps should be small and self-contained
- the system should support regeneration and repair
- failures should be treated as normal iteration, not catastrophic errors
- model loading and generation progress should be visible but not noisy

## Generated App Format

For MVP, each generated app should be stored as a self-contained HTML document:

- HTML, CSS, and JavaScript in one file/string
- no required network access
- no external CDN dependency by default
- responsive layout for phone screens
- persistent data through browser storage only when needed

The generated HTML should behave well in Android WebView.

## Safety And Permissions

Generated apps should run in a constrained WebView environment.

Default stance:

- no arbitrary native permissions
- no automatic external navigation
- no hidden network calls
- no file-system access from generated HTML
- clear user consent before future sharing or exporting

If generated HTML fails, Onion should show a friendly repair path and preserve the user's original request.

## Future Sharing Direction

Later, Onion can become a community of small useful apps.

Future server-backed features may include:

- publish a mini-app
- browse shared apps
- remix an app
- version history
- creator profiles
- ratings or saves
- syncing personal apps across devices

This should grow from the creation loop, not replace it. The first product must be valuable even when fully offline.

## Product Principles

- Make the first creation fast.
- Keep the user close to the running result.
- Prefer iteration over configuration.
- Treat generated apps as personal tools, not disposable demos.
- Make privacy obvious.
- Keep the native shell simple and reliable.
- Let sharing arrive after local creation feels excellent.

## Engineering Notes

This repository is expected to become a native Android project.

Likely architecture direction:

- Kotlin-first Android app
- Jetpack Compose for native UI
- Android WebView for generated app runtime
- local persistence for saved app metadata and HTML
- local LLM integration behind a replaceable generation interface
- future server integration behind repository/service boundaries

Do not let the generated-app runtime leak into the native app architecture. The native shell owns storage, navigation, model orchestration, and safety. The generated HTML owns only the mini-app user experience inside the WebView.

