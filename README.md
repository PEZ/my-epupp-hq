# My Epupp HQ

A workspace for live tampering the web from your editor and/or AI agent harness, using [Epupp](https://github.com/PEZ/epupp).

## What is Epupp?

Epupp is a web browser extension, a bit similar to [Tampermonkey](https://www.tampermonkey.net/), that allows you to use userscripts to tamper with web pages you visit so that they behave as you want them. Unlike Tampermonkey, Epupp also starts a scripting REPL inside the page, exposing it to your editor and/or AI agent over the [nREPL](https://nrepl.org/) protocol. This lets you use your favorite tools to develop userscripts, and to modify/inspect web pages completely ad-hoc as you need it.

The scripting environment in Epupp is [Scittle](https://github.com/babashka/scittle), which provides an interpreted version of [ClojureScript](https://clojurescript.org/). This is a very dynamic programming language, enabling full Interactive Programming. If you have ever configured/scripted Emacs, you will recognize the model.

Let's name the “editor and/or AI agent harness” as the “Epupp REPL client” or “REPL client” from now on.

To connect the REPL client to the browser tab (the REPL server) we use [browser-nrepl](https://github.com/babashka/sci.nrepl), a relay between the websocket exposed in the browser tab and the nREPL protocol spoken by the REPL client.

## What is My Epupp HQ?

This is a template repo aimed at providing a starting point and a hub for your live web tampering with Epupp. The workspace/project contains some basic configuration and instructions to guide you and your AI agents when adding Epupp to your daily routine.

## Prerequisites

* **Epupp** [installed](https://github.com/PEZ/epupp?tab=readme-ov-file#install) in your browser
* **Babashka** [installed](https://github.com/babashka/babashka#installation) on your computer
* An **Epupp REPL client** (a Clojure REPL enabled editor and/or AI harness)
* **Your copy of this repo** cloned to your computer
* At least a skim of the [Epupp README](https://github.com/PEZ/epupp?tab=readme-ov-file#epupp-live-tamper-your-web)

## Start a Live Tamper Session

1. Start the browser-nrepl relay, from the project root (if you are using VS Code, see below for an alternative way to start the relay):
   ```sh
   bb browser-nrepl
   ```
2. Connect the browser tab to browser-nrepl: Click **Connect** in the Epupp extension's popup UI.
3. Connect your REPL client: This will depend on what you are using as your REPL client, see below.

If your REPL client is an editor:

4. Open the file [userscripts/hq/hello_world.cljs](userscripts/hq/hello_world.cljs).
5. Connect your editor to the REPL on port 1339
6. Evaluate the file or the expression

If your REPL client is an AI agent:

4. Connect your AI agent harness to the REPL on port 1339
5. Tell your agent that the Epupp REPL is connected and that you want it to quickly demo it for you.

## REPL Pitfalls

### Navigation Hangs the REPL

On non-SPA sites, setting `js/window.location` (or clicking a link) from a REPL eval can tear down the page and its REPL. The eval response never returns - the connection hangs until you reconnect the repl. Very disruptive!

**Fix: Defer navigation with `setTimeout`:**

```clojure
;; BAD - eval never completes, connection hangs
(set! (.-location js/window) "https://example.com/page")

;; GOOD - returns immediately, navigates after response completes
(js/setTimeout
  #(set! (.-location js/window) "https://example.com/page")
  50)
;; => timeout ID returned instantly
```

After navigation, wait for the new page to load and REPL to reconnect. All prior definitions will be gone - redefine utilities or bake them into a userscript.

### Clipboard Access Blocked

`navigator.clipboard.writeText` is often blocked, needing user gesture, due to permissions policy. Use a textarea workaround:

```clojure
(defn copy-to-clipboard! [text]
  (let [el (js/document.createElement "textarea")]
    (set! (.-value el) text)
    (.appendChild js/document.body el)
    (.select el)
    (js/document.execCommand "copy")
    (.removeChild js/document.body el)))
```

## How to connect REPL Clients to Epupp

The REPL client needs to support nREPL. From there it is a matter of connecting to the nREPL port that the browser-nrepl relay has been started on. The mechanics for this will differ depending on the editor/AI harness used.

Please help with making this project friendly and easy to use with your favorite editor/AI harness by providing instructions and configuration. (I am the creator of Calva and only really have bandwidth/know-how for VS Code and Copilot.)

### VS Code with Calva

This project has [VS Code](https://code.visualstudio.com/) and [Calva](https://calva.io) configuration for starting and connecting multiple browser-nrepl relays to some common sites.

0. Run the default Build Task: <kbd>cmd/ctrl</kbd>+<kbd>b</kbd>, this starts the relays, one of them is for the default Epupp port 1339.
0. Connect the browser tab to browser-nrepl: Click **Connect** in the Epupp extension's popup UI.
1. In VS Code, install the Calva extension
1. Click the REPL button that appears in the VS Code status bar and select **Connect to a running REPL in your project**
1. Select **Epupp REPL** from the **Project Type** menu

That's it. You should see a green session indicator with `epupp-default` in the status bar. To convince yourself that you really have VS Code connected to the tab:

1. In the `userscripts/hq/hello_world.cljs` place the cursor in/near the code you want to evaluate and press <kbd>alt</kbd>+<kbd>enter</kbd>
1. Check in the browser what happened

The configuration leverages the fact that Epupp can be made to use different default ports per domain. If you edit the ports in the Epupp panel before connecting, Epupp will remember that port for the current domain. The configuration lives in two files:
* [.vscode/tasks.json](.vscode/tasks.json). The browser-nrepl tasks for: GitHub, GitLab, YouTube, Ebay, and a default (all other sites).
* [.vscode/settings.json](.vscode/settings.json). The Calva REPL Connect sequences for these tasks.

To use these as provided, check `tasks.json` for the ports used and enter them in the Epupp popup UI per site. But really, they are just suggestions. Add config for your favorite sites and use whatever ports you think make sense. Note that the nREPL port needs to be synced between `tasks.json` and `settings.json`.

Please see [calva.io](https://calva.io) for how to get started and use Calva.

#### Copilot with Calva Backseat Driver

Ready to let the AI Agent hack the web for you? Assuming you did the old-fashioned Human Intelligence steps above ([VS Code with Calva](#vs-code-with-calva)):

0. Install the [Copilot](https://marketplace.visualstudio.com/items?itemName=GitHub.copilot) extension in VS Code
1. Install [Calva Backseat Driver](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva-backseat-driver) in VS Code
1. In the Copilot chat view select the **Epupp Assistant** agent
   * For model, Opus 4.5-6 is recommended, but Sonnet 4.5-6 and GPT 5.3 Codex also work fine. Avoid **Auto**, because VS Code will probably select some lame model that does not understand how to use the Epupp REPL.
1. Ask Copilot to use the `epupp-default` REPL to do something fun with the web page you are connected to, or just show you that it can do something.

#### VS Code/Cursor with [ECA](https://github.com/editor-code-assistant/eca)

TBD: PRs welcome

#### [Cursor](https://www.cursor.com/) with Calva Backseat Driver

TBD: PRs welcome

#### Cursor with [clojure-mcp](https://github.com/bhauman/clojure-mcp)

TBD: PRs welcome

### [Emacs](https://www.gnu.org/software/emacs/) with [CIDER](https://cider.mx/)

TBD: PRs welcome

### VIM

TBD: PRs welcome

### [IntelliJ](https://www.jetbrains.com/idea/) with [Cursive](https://cursive-ide.com/)

TBD: PRs welcome

### Your Favorite Editor

TBD: PRs welcome

### [Claude Code](https://docs.anthropic.com/en/docs/claude-code)

TBD: PRs welcome

### Your Favorite AI Agent Harness

TBD: PRs welcome

## Enjoy! ♥️

Epupp is created and maintained by Peter Strömberg a.k.a PEZ, and provided as open source and is free to use. A lot of my time is spent on bringing Epupp and related software to you, and keeping it supported, working, and relevant.

* Please consider [sponsoring Epupp](https://github.com/sponsors/PEZ).
