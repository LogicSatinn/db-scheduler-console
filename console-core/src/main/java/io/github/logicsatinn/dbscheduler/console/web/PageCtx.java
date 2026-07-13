package io.github.logicsatinn.dbscheduler.console.web;

public record PageCtx(String basePath, boolean readOnly, long pollSeconds,
                      String csrfHeaderJson, String active) {}
