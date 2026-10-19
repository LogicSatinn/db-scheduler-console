"use strict";
(() => {
    const base = document.body.dataset.consoleBase;
    // htmx 2 stores all origins' same-origin history entries in this one array.
    // Remove only paths owned by this console; expose for the host logout hook too.
    function clearConsoleHistory(paths = [base]) {
        try {
            const raw = localStorage.getItem("htmx-history-cache");
            if (!raw) return;
            const entries = JSON.parse(raw);
            if (!Array.isArray(entries)) return;
            const keep = entries.filter(entry => {
                const url = new URL(entry.url, location.href);
                return url.origin !== location.origin || !paths.some(path =>
                    url.pathname === path || url.pathname.startsWith(path + "/"));
            });
            localStorage.setItem("htmx-history-cache", JSON.stringify(keep));
        } catch (_) { /* Disabled storage must not prevent console use. */ }
    }
    window.dbSchedulerConsole = { clearHistory: clearConsoleHistory };
    if (!base) return; // A host logout page may load just the cleanup API.
    clearConsoleHistory();
    // Remove sensitive DOM before a browser back/forward-cache snapshot is made.
    window.addEventListener("pagehide", () => {
        clearConsoleHistory();
        document.body.replaceChildren();
    });
    window.addEventListener("pageshow", event => { if (event.persisted) location.reload(); });
    document.addEventListener("click", event => {
        const flash = event.target.closest("[data-dismiss-flash]");
        if (flash) flash.remove();
    });
    document.addEventListener("htmx:beforeRequest", event => {
        const source = event.detail.elt;
        const trigger = event.detail.requestConfig && event.detail.requestConfig.triggeringEvent;
        // Explicit actions and form submissions must not be suppressed.
        if (source.matches("#detail-live, #history-region, #executions-region")
            && (!trigger || trigger.type !== "dsc-refresh")
            && source.querySelector("details[open], input[name=ref]:checked, input[name=when]:focus, input[name=when]:valid")) {
            event.preventDefault();
        }
    });
    document.addEventListener("htmx:beforeSwap", event => {
        const status = event.detail.xhr.status;
        if (status === 401 || status === 403) {
            clearConsoleHistory();
            // A failed action can be a CSRF failure; force fresh host authorization.
            location.reload();
            return;
        }
        if (status === 404 && event.detail.target.id === "detail-live" || status === 400) {
            event.detail.shouldSwap = true;
            event.detail.isError = false;
        }
    });
    document.addEventListener("toggle", async event => {
        const details = event.target;
        if (!details.matches("details[data-payload-url]") || !details.open || details.dataset.loaded) return;
        details.dataset.loaded = "true";
        const preview = details.querySelector("pre");
        preview.textContent = "Loading…";
        try {
            const response = await fetch(details.dataset.payloadUrl, { credentials: "same-origin", cache: "no-store" });
            if (response.status === 401 || response.status === 403) {
                clearConsoleHistory(); preview.textContent = "Payload access denied"; return;
            }
            if (!response.ok || !response.headers.get("Content-Type")?.startsWith("text/plain")) throw new Error();
            preview.textContent = await response.text();
        } catch (_) { preview.textContent = "Preview unavailable"; delete details.dataset.loaded; }
    }, true);
})();
