# My Epupp HQ

A workspace for live tampering the web from your editor and/or AI agent harness, using [Epupp](https://github.com/PEZ/epupp).

## What is Epupp?

Epupp is a web browser extension, a bit simular to Tampermonkey, that allows you to use userscripts to tamper web pages your visit so that they behave you want them to. Unlike Tampemonkey, Epupp also starts a scripting REPL inside the page, exposing it to your editor and/or AI agent over the nREPL protocol. This lets you use your favorite tools to develop userscripts, and to tamper/inspect web pages completely ad-hoc as you need it.

The scripting environment of Epupp is Scittle, which provides an interpreted version of ClojureScript. This is a very dynamic programming language, enabling full Interactive Programming. If you have ever configured/scripted Emacs, you will recognize the model.

Let's name the “editor and/or AI agent harness” as the “Epupp REPL client” or “REPL client” from now on.

To connect the REPL client to the browser tab (the REPL server) we use browser-nrepl, a relay between the websocket exposed in the browser tab and the nREPL protocol spoken by the REPL client.

## What is My Epupp HQ?

This is a template repo aimed at providing a starting point and hub for your web live tampering with Epupp. The workspace/project contains some basic configuration and instructions to guide you and your AI agents when adding Epupp to your daily routine.

## Prerequisites

* **Epupp** [installed](https://github.com/PEZ/epupp?tab=readme-ov-file#install) in your browser
* **Babashka** [installed](https://github.com/babashka/babashka#installation) on your computer
* An **Epupp REPL client** (a Clojure REPL enabled editor and/or AI harness
* **Your copy of this repo** cloned to your computer
* At least a skim of the [Epupp README](https://github.com/PEZ/epupp?tab=readme-ov-file#epupp-live-tamper-your-web)

## Start a Live Tamper Session

1. Start the browser-nrepl relay, from the project root:
   ```sh
   bb browser-nrepl
   ```
2. Connect the browser tab to browser-nrepl: Click **Connect** in the Epupp extension's popup UI.
3. Connect you REPL client: This will depend on what you are using as your REPL client, see below.

If your REPL client is an editor:

1. Open the file `userscripts/hq/hello_world.cljs`.
2. Connect your editor to the REPL on port 1339
3. Evaluate the file or the expression

If your REPL client is an AI agent:

1. Connect your AI agent harness to the REPL on port 1339
2. Tell your agent that the Epupp repl is connected and that you want it to quickly demo it for you.

## Howto connect REPL Clients to Epupp

The REPL client needs to supoprt nREPL. From there it is a matter of connecting to the nREPL port that the browser-nrepl relay has been started on. The mechanics for this will differ depending on the editor/AI harness used.

Please help with making this project friendly and easy to use with your favorite editor/AI harness, by providing instructions and configuration. (I am the creator of Calva so only really have bandwidth to bother with VS Code and Copilot.)

## VS Code with Calva

1. In VS Code, install the Calva extension
1. With `userscripts/hq/hello_world.cljs` opened, click the REPL button in the VS Code status bar and select **Connect to a running REPL in your project**
1. Select **scittle** from the **Project Type** menu
1. In the `userscripts/hq/hello_world.cljs` place the cursor in/near the code you want to evaluate and press <kbd>alt</kbd>+<kbd>enter</kbd>
1. Check in the browser what happened

### Copilot with Calva Backseat Driver

## Emacs

TBD: PRs welcome

## VIM

TBD: PRs welcome

## IntelliJ with Cursive

TBD: PRs welcome

## Your Favorite Editor

TBD: PRs welcome

## Claude Code

TBD: PRs welcome

## Your Favorite AI Agent Harness

TBD: PRs welcome

## Enjoy! ♥️

Epupp is created and maintained by Peter Strömberg a.k.a PEZ, and provided as open source and is free to use. A lot of my time is spent on bringing Epupp and related software to you, and keeping it supported, working, and relevant.

* Please consider [sponsoring Epupp](https://github.com/sponsors/PEZ).
