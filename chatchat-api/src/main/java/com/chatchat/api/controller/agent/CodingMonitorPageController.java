package com.chatchat.api.controller.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Static login shell only. Task data is served by authenticated Java APIs. */
@RestController
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public class CodingMonitorPageController {
    private ResponseEntity<Resource> file(String name,String type){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .header("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            .contentType(MediaType.parseMediaType(type)).body(new ClassPathResource("coding-monitor/"+name));
    }
    @GetMapping({"/monitor","/monitor/"}) public ResponseEntity<Resource> page(){return file("index.html","text/html;charset=UTF-8");}
    @GetMapping("/monitor/app.js") public ResponseEntity<Resource> script(){return file("app.js","text/javascript;charset=UTF-8");}
    @GetMapping("/monitor/style.css") public ResponseEntity<Resource> style(){return file("style.css","text/css;charset=UTF-8");}
}
