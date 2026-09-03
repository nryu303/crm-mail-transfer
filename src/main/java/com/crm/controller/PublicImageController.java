package com.crm.controller;

import com.crm.entity.HtmlImage;
import com.crm.service.HtmlImageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** Public, unauthenticated image serving — deliberately NOT under /manager/** (session-gated)
 *  or /media/** (Basic-Auth-gated), so an <img src="/img/{id}"> pasted into reply-page header
 *  or footer HTML renders correctly for anonymous visitors on the public /reply/{token} page. */
@Controller
public class PublicImageController {

    private final HtmlImageService htmlImageService;

    public PublicImageController(HtmlImageService htmlImageService) {
        this.htmlImageService = htmlImageService;
    }

    @GetMapping("/img/{id}")
    public ResponseEntity<org.springframework.core.io.Resource> serve(@PathVariable Long id) {
        HtmlImage img = htmlImageService.findById(id).orElse(null);
        if (img == null) return ResponseEntity.notFound().build();
        File f = htmlImageService.fileFor(img);
        if (f == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.getContentType()))
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
                .body(new FileSystemResource(f));
    }
}
