# LinkedIn Post Tracker — Maintenance Guide

Tracks posts you engage with on LinkedIn so you can find them later. Runs as an Epupp userscript on `https://www.linkedin.com/*`.

## What It Does

The tracker silently observes your LinkedIn feed and records posts you interact with — likes, comments, reposts, and "See more" expansions. A star icon in LinkedIn's nav bar opens a side panel where you can search, filter, and revisit tracked posts. Posts are always sorted by most recently engaged first. You can also pin important posts to protect them from automatic pruning.

### User Capabilities

- **Automatic tracking** — Engage with a post (like, comment, repost, expand) and it's captured with author info, text preview, media type, and timestamps
- **Pin posts** — Star button injected next to each post's overflow menu; pinned posts survive pruning
- **Search** — Case-insensitive substring match across author name, headline, and post text
- **Filter** — Toggle by engagement type
- **Navigate** — Click any tracked post to open it on LinkedIn in a new tab
- **Panel controls** — Toggle via nav button, close with Escape or click-outside

## Architecture

### Design Principles

1. **Data-oriented** — Flat, namespaced state in a centralized atom. EDN serialization. Pure transform functions. The namespaced keywords (`:tracker/posts`, `:ui/panel-open?`, `:post/media-type`, `:engaged/liked`, etc.) are self-documenting — inspect `!state`'s `defonce` for the full shape.
2. **Imperative shell, functional core** — A single scraping boundary converts DOM into `raw/*` data maps. Everything downstream is pure Clojure: `raw/*` → `post/*` snapshots via pure transforms, state mutation functions that return new state, and view functions that are pure hiccup.
3. **Resilient to change** — Selector fallback chains, pattern-matching on semantic text (aria-labels), and storage key migration.

### Two-Atom Separation

`!state` holds domain data and UI state — the serializable truth that gets persisted to localStorage. `!resources` holds live JS objects (DOM nodes, MutationObserver, event handler functions, timer IDs) that can't be serialized and are rebuilt on `init!`. This separation keeps persistence clean: save/load never touches `!resources`.

### Code Organization

The file flows top-to-bottom through logical layers: state definitions → selector registry → DOM query helpers → scraping boundary (the one impure data-extraction point) → pure transforms → engagement pattern-matching → state mutation functions (pure, return new state) → persistence → DOM observation → navigation detection → Replicant UI components (pure hiccup) → event handler wiring → lifecycle (`init!`/`teardown!`).

## Design Decisions & Rationale

### Why Aria-Label Pattern Matching for Engagement Detection

Instead of inspecting button classes or IDs (fragile), the script pattern-matches on `aria-label` text like `"React"`, `"Comment"`, `"Repost"`. Accessibility labels are semantically stable — they change less often than CSS classes. The click-patterns data structure makes adding new engagement types a one-line addition.

### Why an Iframe for localStorage

LinkedIn overrides `Storage.prototype` methods to intercept and potentially restrict access. The script creates a hidden iframe to get a clean window context, captures the *original* `setItem`/`getItem`/`removeItem` from the unmodified `Storage.prototype`, and uses `.call()` to invoke them on `js/localStorage`. Without this, tracked posts may silently fail to persist.

### Why Replicant for Some UI but Manual DOM for Others

Replicant is used for UI the script fully owns (nav button, tracker panel) — these are declarative, state-driven, and rendered into dedicated mount points. Pin buttons are injected into LinkedIn's own post elements using manual DOM manipulation because they must blend with existing event handlers and layout without Replicant taking over the container.

### Why Both URL Polling and Popstate

LinkedIn is a partial SPA. Browser back/forward fires `popstate`, but LinkedIn's internal client-side routing often doesn't. Polling `window.location` every 2s catches the rest. Both converge on the same navigation handler that resets the page-view state and rescans.

### Why MutationObserver → RAF → setTimeout

`MutationObserver` fires for every DOM node added — LinkedIn renders 20+ nodes per post. If we scanned on every callback, we'd process the feed 100+ times per second during scrolling. The pipeline collapses this: RAF batches callbacks to the next paint frame, then a 150ms timeout lets LinkedIn's lazy-load logic finish. Result: ~2 scans per feed refresh instead of hundreds.

### Why Storage Key Migration Exists

The storage key evolved from `"epupp:linkedin-tracker"` to the namespaced `"epupp:linkedin-tracker/posts"`. On load, if the old key exists, data is migrated and the old key is deleted. This pattern enables future schema changes without data loss — check the load function for the current migration logic.

## Known Fragilities

- **LinkedIn DOM changes** — The biggest maintenance burden. The `selectors` map centralizes all CSS selectors with fallback chains. When LinkedIn ships a DOM change, fallback selectors absorb the impact temporarily, but console warnings (`[epupp:tracker] Fell to secondary selector`) signal that primaries need updating.
- **Timing assumptions** — The nav bar might not exist at `document-idle` on slow connections. The 1.5s delay after navigation and 150ms mutation debounce are tuned for typical LinkedIn rendering speed. If LinkedIn's rendering pipeline changes significantly, these may need adjustment.
- **Promoted post detection** — Relies on URN format (`urn:li:activity:*`) and "promot" keyword in timestamp text. If LinkedIn changes how it labels promoted content, sponsored posts could leak into the tracker.

## Maintenance Recipes

### When LinkedIn Changes Its DOM

1. Run `(selector-health-check!)` in the REPL — it logs which selectors match and which are missing
2. Inspect the page to find updated CSS selectors
3. Update the `selectors` map — insert the new selector at position 0, keep old ones as fallbacks
4. Verify: `(q js/document :sel/affected-key)`

### Adding a New Engagement Type

1. Add an entry to `click-patterns` — specify `:source` (`:btn-aria` or `:text`), `:pattern` (regex), and `:engagement` keyword
2. Add the display label to `engagement-labels`
3. Everything else (tracking, persistence, filtering, panel display) flows through automatically

### Adding a New Media Type

1. Add detection logic to `detect-media-type` — order matters, first match wins
2. Add the display label to `media-labels`
3. Optionally add a rendering case to `media-thumbnail`
4. Add CSS selector(s) to the `selectors` map if new DOM elements need querying

### Evolving the Storage Schema

Follow the existing pattern: change the storage key constant, add a fallback read from the old key in the load function, migrate data on first load, delete the old key. This keeps existing users' data intact across upgrades.

## Debugging

**Pin buttons not appearing?**
Run `(selector-health-check!)`. Check if the post passes activity URN validation and promoted-post filtering. Inspect `(:nav/seen-urns @!state)` to see if the post was already processed this page load.

**Engagements not being tracked?**
Click a button, then check `(:tracker/index @!state)` — the URN should appear. If not, the click-pattern regex may not match. Test: click a button and inspect what aria-label the button has.

**State changes but UI doesn't update?**
Verify the atom watch is firing — `(teardown!)` then `(init!)` to re-attach. Check that the panel container element is still in the DOM.

**General diagnostics:**
- `@!state` — full state snapshot
- `@!resources` — which listeners/observers are attached
- `(teardown!)` then `(init!)` — full restart without page reload
- Console logs are prefixed with `[epupp:tracker]`
