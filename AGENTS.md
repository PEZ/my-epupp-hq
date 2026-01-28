# AI Agent Instructions for Epupp Users

You are assisting a user who is working with **Epupp**, a browser extension for tampering with web pages using ClojureScript via Scittle.

## Essential Knowledge

**Read this first:** [epupp-user-guide.md](epupp-user-guide.md)

Epupp is NOT standard ClojureScript - it runs **Scittle** in the browser. Key differences:
- Runs in browser page context, not Node.js or JVM
- Limited to bundled Scittle libraries (see library table below)
- No macros beyond what Scittle provides
- Direct DOM access via `js/` interop

## What Users Do Here

This template project is for:

1. **Live tampering** - Connect editor REPL to browser, modify pages interactively
2. **Userscript development** - Write scripts that auto-run on matching sites
3. **Script management** - Version control userscripts, sync via FS API

## Quick Reference Tables

### Manifest Keys

| Key | Required | Example |
|-----|----------|---------|
| `:epupp/script-name` | Yes | `"github_tweaks.cljs"` |
| `:epupp/auto-run-match` | Yes | `"https://github.com/*"` or `["url1" "url2"]` |
| `:epupp/description` | No | `"Enhance GitHub UI"` |
| `:epupp/run-at` | No | `"document-idle"` (default), `"document-start"`, `"document-end"` |
| `:epupp/inject` | No | `["scittle://reagent.js"]` |

### Available Libraries

| URL | Namespaces |
|-----|------------|
| `scittle://pprint.js` | `cljs.pprint` |
| `scittle://promesa.js` | `promesa.core` |
| `scittle://replicant.js` | `replicant.core` |
| `scittle://js-interop.js` | `applied-science.js-interop` |
| `scittle://reagent.js` | `reagent.core`, `reagent.dom` |
| `scittle://re-frame.js` | `re-frame.core` (includes Reagent) |
| `scittle://cljs-ajax.js` | `cljs-http.client` |

### FS API Functions

| Function | Purpose |
|----------|---------|
| `(epupp.fs/ls)` | List scripts with metadata |
| `(epupp.fs/show "name.cljs")` | Get script code |
| `(epupp.fs/save! code-string)` | Create/update script |
| `(epupp.fs/mv! "old" "new")` | Rename script |
| `(epupp.fs/rm! "name.cljs")` | Delete script |

> **Note:** Write operations require **FS REPL Sync** enabled in popup Settings.

## Helping Users

### Writing Userscript Manifests

Always include a manifest map at the top:

```clojure
{:epupp/script-name "my_script.cljs"
 :epupp/auto-run-match "https://example.com/*"
 :epupp/description "What it does"}

(ns my-script)
;; code here
```

**Validation rules:**
- Script names are normalized to `snake_case.cljs`
- Cannot use `epupp/` prefix (reserved)
- `:epupp/auto-run-match` can be string or vector of strings

### Choosing Script Timing

| Timing | When to Recommend |
|--------|-------------------|
| `document-idle` (default) | Most scripts - DOM ready, page loaded |
| `document-start` | Need to intercept globals, block scripts, polyfill APIs |
| `document-end` | Need DOM before images/iframes finish |

**`document-start` warning:** At this timing, `document.body` is null. User code must wait for DOMContentLoaded if it needs DOM access.

### REPL Troubleshooting Checklist

When users report REPL issues:

1. **Is relay running?** Check terminal for `bb -Sdeps ...` command
2. **Do ports match?** Default is 12345 (nREPL) and 12346 (WebSocket)
3. **Is it a scriptable page?** Cannot script `chrome://`, `about:`, or extension pages
4. **Is popup showing "Connected"?** If not, click Connect button
5. **Did page reload?** Reconnect from popup after page navigation

### FS Sync Workflows

**User wants to push local file to Epupp:**
```clojure
(epupp.fs/save! (slurp "/path/to/script.cljs"))
```

**User wants to pull script from Epupp:**
```clojure
(spit "/path/to/script.cljs" (epupp.fs/show "script_name.cljs"))
```

**User gets "FS REPL Sync is disabled" error:**
- Direct them to popup Settings, enable "FS REPL Sync"

## Common Patterns

### Adding a Floating Element

```clojure
(let [el (js/document.createElement "div")]
  (set! (.-id el) "my-widget")
  (set! (.. el -style -cssText)
        "position: fixed; bottom: 10px; right: 10px; z-index: 99999;
         padding: 12px; background: #1e293b; color: white; border-radius: 8px;")
  (set! (.-innerHTML el) "<strong>My Widget</strong>")
  (.appendChild js/document.body el))
```

### Using Reagent

```clojure
{:epupp/inject ["scittle://reagent.js"]}

(ns my-app
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]))

(defonce !state (r/atom {:count 0}))

(defn component []
  [:div "Count: " (:count @!state)])

(let [container (js/document.createElement "div")]
  (.appendChild js/document.body container)
  (rdom/render [component] container))
```

### Loading Libraries at Runtime (REPL)

```clojure
(epupp/manifest! {:epupp/inject ["scittle://pprint.js"]})
(require '[cljs.pprint :as pprint])
```

### Intercepting Before Page Scripts

```clojure
{:epupp/run-at "document-start"}

;; This runs BEFORE any page JavaScript
(let [original js/window.fetch]
  (set! js/window.fetch
        (fn [url & args]
          (js/console.log "Intercepted:" url)
          (apply original url args))))
```

## Project Structure

```
my-epupp-hq/
  epupp-user-guide.md    # Read this for Epupp documentation
  userscripts/           # Userscripts with manifests
    hq/                  # Example namespace folder
      hello_world.cljs
  live-tampers/          # Ad-hoc tampering code (experiments)
```

- `userscripts/` - Scripts ready to sync to Epupp
- `live-tampers/` - Temporary experiments (not synced)

## What NOT to Do

- **Don't use `epupp/` prefix** in script names - reserved for system scripts
- **Don't assume DOM exists at `document-start`** - it doesn't
- **Don't use standard ClojureScript patterns** that require macros or JVM features
- **Don't suggest npm packages** - only bundled Scittle libraries are available

## Documentation Index

| Topic | Section in Guide |
|-------|------------------|
| REPL connection | [REPL Workflows](epupp-user-guide.md#repl-workflows) |
| Manifest format | [Manifest Keys Reference](epupp-user-guide.md#manifest-keys-reference) |
| Script timing | [Script Timing](epupp-user-guide.md#script-timing) |
| Available libraries | [Using Scittle Libraries](epupp-user-guide.md#using-scittle-libraries) |
| FS API | [REPL File System API](epupp-user-guide.md#repl-file-system-api) |
| Examples | [Examples](epupp-user-guide.md#examples) |
| UI overview | [Managing Scripts](epupp-user-guide.md#managing-scripts-popup-ui) |
| Troubleshooting | [Troubleshooting](epupp-user-guide.md#troubleshooting) |
