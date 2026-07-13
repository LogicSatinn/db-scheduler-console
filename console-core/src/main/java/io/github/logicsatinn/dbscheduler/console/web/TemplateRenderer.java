package io.github.logicsatinn.dbscheduler.console.web;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public class TemplateRenderer {

    private final TemplateEngine engine = TemplateEngine.createPrecompiled(ContentType.Html);

    public String render(String template, Map<String, Object> params) {
        StringOutput out = new StringOutput();
        engine.render(template, params, out);
        return out.toString();
    }

    public ResponseEntity<String> page(String template, Map<String, Object> params) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(render(template, params));
    }
}
