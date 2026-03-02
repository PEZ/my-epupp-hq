**Write an Epupp userscript that lets me isolate any element on a web page for clean printing.**

## How it should work

The script should auto-start into a "hover to select" mode as soon as it runs. As I move my mouse over the page, highlight the element under the cursor with a visible outline (something like cyan, inset so it doesn't shift layout). Change the cursor to crosshair so I know I'm in selection mode.

When I click an element, isolate it by hiding everything else on the page - not by removing nodes, but by walking up the DOM tree from the clicked element to the body, hiding all siblings at each level. This way the element keeps its original CSS context (inherited styles, cascade, layout) while everything else disappears.

Once isolated, show a small fixed control bar at the top of the page with two buttons:

- **Cancel** - restore the page and go back to hover mode so I can try a different element
- **Print** - trigger the browser's print dialog

The control bar should look like a tool overlay - dark semi-transparent background, white text, high z-index. Push the body content down with padding so nothing hides behind the bar.

### Print behavior

When printing, the control bar and its body padding should be hidden via a `@media print` rule so the printout is clean - just the isolated element as if the page naturally only contained that content.

### Tall element printing

Many web apps use scrolling containers with constrained heights (`overflow: scroll/hidden/auto`). After hiding siblings, the isolated element can still be clipped by these ancestor containers, causing the printout to be cropped to the visible viewport area.

After isolating, reset overflow and height constraints on all ancestors from the isolated element up through `<body>` and `<html>`. Save and restore the original inline style values (including `!important` priority) alongside the other state preservation.

### Keyboard

Escape should work as progressive undo:

- In hover mode: cancel selection and return to idle
- In isolated mode: restore the page and return to hover mode (same as Cancel button)

### State preservation

Save and restore all original values - element display properties, body padding-top, ancestor overflow/height styles.  Use `defonce` for the state atom so the script survives REPL re-evaluations during development.

### Iteration workflow

The key UX insight: users rarely pick the right element on the first try. After canceling isolation, automatically re-enter hover mode so they can immediately try another element without re-running the script.

### Event handling

Use capture phase (third argument `true`) for mouseover and click listeners so the script intercepts events before the page's own handlers can interfere. Stop propagation and prevent default on clicks to avoid accidentally navigating links or triggering page behavior while selecting.

### Safety

Store event handler references in the state atom so they can be cleanly removed when exiting hover mode. If `start-hover!` is called while already hovering, toggle it off. If called while isolated, no-op.

The script should work on any website with zero configuration - no site-specific selectors, no external dependencies beyond `clojure.string`.

## How you should work

Please use the structural create clojure file tool to create a stub for the script, which should be named `pez/element_printing.cljs`. Include no-op function `start!` and a call to that function. Then task an Epupp Assistant subagent to plan the implementation in some incremental progression steps. Then hand the requirements and the plan over to an Epupp Assistant subagent with instructions to use the epupp-default repl and the askQuestions tool to develop the functionality interactively and in coop with the user. The agent should bring piece by piece of the functionality to life and instruct the user how to test at each step using askQuestions to collect the feedback. When all functionality is in place the subagent should in turn task a subagent to edit the userscript file with the code that implements the requirements. Then your subagent should report back to you and then you synthesize the work done in the chat.