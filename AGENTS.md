# AI Agent Instructions for Epupp Users

You are assisting a user who is working with **Epupp**, a browser extension for tampering with web pages using ClojureScript via Scittle.

## Essential Knowledge

Epupp is NOT standard ClojureScript - it runs **Scittle** in the browser. Key differences:
- Runs in browser page context, not Node.js or JVM
- Limited to bundled Scittle libraries (see library table below)
- Direct DOM access via `js/` interop
- Scittle has async/await

## What Users Do Here

This template project is for:

1. **Live tampering** - Connect editor REPL to browser, modify pages interactively
2. **Userscript development** - Write scripts that auto-run on matching sites

## Quick Reference Tables

### Manifest Keys

| Key | Required | Example |
|-----|----------|---------|
| `:epupp/script-name` | Yes | `"github_tweaks.cljs"` |
| `:epupp/auto-run-match` | Yes | `"https://github.com/*"` or `["url1" "url2"]` |
| `:epupp/description` | No | `"Enhance GitHub UI"` |
| `:epupp/run-at` | No | `"document-idle"` (default), `"document-start"`, `"document-end"` |
| `:epupp/inject` | No | `["scittle://replicant.js"]` |

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

**`document-start` warning:** At this timing, `document.body` is null. User code must wait for DOMContentLoaded for functionality that needs DOM access.

### REPL Troubleshooting Checklist

When users report REPL issues:

1. **Is relay running?** Check process list for `bb ... sci.nrepl.browser-server ...` or `bb browser-nrepl ...` command
2. **Do ports match?** Default is 1339 (nREPL) and 1340 (WebSocket)
3. **Is it a scriptable page?** Cannot script `chrome://`, `about:`, or extension pages
4. **Is popup showing the current tab as connected? If not, click Connect button
5. **Did page reload?** Reconnect from popup after page navigation (the user can also configure Epupp to auto-reconnect)

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

### Using Replicant

```clojure
{:epupp/script-name "my/replicant_widget.cljs"
 :epupp/auto-run-match "*"
 :epupp/inject ["scittle://replicant.js"]}

(ns my.replicant-widget
  (:require [replicant.dom :as r]))

(r/render
 (doto (js/document.createElement "div")
   (->> (.appendChild js/document.body)))
 [:h1 "Hello from Replicant!"])
```

### Intercepting Before Page Scripts

```clojure
{:epupp/script-name "my/document_start_test.cljs"
 :epupp/run-at "document-start"}

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
  userscripts/           # Userscripts with manifests
    hq/                  # Example namespace folder
      hello_world.cljs
  live-tampers/          # Ad-hoc tampering code (experiments, or for repeated use)
```

- `userscripts/` - Scripts ready to sync to Epupp
- `live-tampers/` - Strictly for REPL use

## What NOT to Do

- **Don't use `epupp/` prefix** in script names - reserved for system scripts
- **Don't assume DOM exists at `document-start`** - it doesn't
- **Don't suggest npm packages** - only bundled Scittle libraries are available
