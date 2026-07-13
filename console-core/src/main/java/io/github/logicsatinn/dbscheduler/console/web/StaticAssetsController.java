package io.github.logicsatinn.dbscheduler.console.web;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class StaticAssetsController {

    @GetMapping("/static/{file:.+}")
    public ResponseEntity<byte[]> asset(@PathVariable String file) throws IOException {
        if (file.contains("..") || file.contains("/")) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db-scheduler-console/static/" + file)) {
            if (in == null) {
                return ResponseEntity.notFound().build();
            }
            MediaType type = file.endsWith(".css") ? MediaType.valueOf("text/css")
                    : file.endsWith(".js") ? MediaType.valueOf("text/javascript")
                    : MediaType.APPLICATION_OCTET_STREAM;
            return ResponseEntity.ok()
                    .contentType(type)
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                    .body(in.readAllBytes());
        }
    }
}
