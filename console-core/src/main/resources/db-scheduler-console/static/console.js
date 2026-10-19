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
        if (event.target.closest("[data-live-toggle]")) setPollingPaused(!paused);
    });
    const pauseKey = "db-scheduler-console:polling-paused";
    function storedPollingPaused() {
        try { return localStorage.getItem(pauseKey) === "true"; } catch (_) { return false; }
    }
    let paused = storedPollingPaused();
    function renderPolling() {
        document.body.toggleAttribute("data-polling-paused", paused);
        document.querySelectorAll("[data-live-status]").forEach(status => {
            status.textContent = paused ? "Updates paused" : "Live";
        });
        document.querySelectorAll("[data-live-toggle]").forEach(toggle => {
            toggle.textContent = paused ? "Resume updates" : "Pause updates";
        });
    }
    function setPollingPaused(value) {
        paused = value;
        try {
            if (paused) localStorage.setItem(pauseKey, "true"); else localStorage.removeItem(pauseKey);
        } catch (_) { }
        renderPolling();
        if (!paused) {
            document.querySelectorAll("[hx-trigger*='every']").forEach(region =>
                region.dispatchEvent(new CustomEvent("every")));
        }
    }
    renderPolling();
    window.addEventListener("storage", event => { if (event.key === pauseKey) { paused = storedPollingPaused(); renderPolling(); } });
    function rejected(status, target) {
        return status >= 400 && status !== 400 && !(status === 404 && target?.id === "detail-live");
    }
    document.addEventListener("htmx:before:request", event => {
        const source = event.detail.ctx.sourceElement;
        const trigger = event.detail.ctx.sourceEvent;
        if (trigger?.type === "every" && paused) {
            event.preventDefault();
            return;
        }
        if (source.matches("#detail-live, #history-region, #executions-region, #recurring-region")
            && (!trigger || trigger.type !== "dsc-refresh")
            && source.querySelector("details[open], input[name=ref]:checked, input[name=when]:focus, input[name=when]:valid")) {
            event.preventDefault();
        }
    });
    document.addEventListener("htmx:before:history:update", event => {
        if (rejected(event.detail.response?.status, event.detail.sourceElement)) event.preventDefault();
    });
    document.addEventListener("htmx:before:swap", event => {
        const ctx = event.detail.ctx;
        const status = ctx.response?.status;
        if (status === 401 || status === 403) {
            event.preventDefault();
            clearConsoleHistory();
            location.reload();
            return;
        }
        if (rejected(status, ctx.target)) {
            event.preventDefault();
            return;
        }
        for (const task of event.detail.tasks) {
            task.fragment?.querySelectorAll("script").forEach(script => script.remove());
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
    const evaluationDisabled = function () { throw new Error("htmx evaluation is disabled"); };
    htmx.registerExtension("db-scheduler-console", {
        init(api) { api.initSecurity(null, evaluationDisabled, evaluationDisabled); }
    });
})();
