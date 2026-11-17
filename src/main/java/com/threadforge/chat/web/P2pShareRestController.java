/*
 * Copyright 2026 ThreadForge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.threadforge.chat.web;

import com.threadforge.chat.p2p.P2pShareDto.ShareView;
import com.threadforge.chat.p2p.P2pShareDto.StagedFileView;
import com.threadforge.chat.p2p.P2pShareDto.StagingSessionView;
import com.threadforge.chat.p2p.P2pShareDto.StateView;
import com.threadforge.chat.p2p.P2pShareService;
import com.threadforge.chat.p2p.P2pShareService.ReceivedPayload;
import com.threadforge.chat.security.CurrentUser;
import com.threadforge.chat.security.RateLimitExceededException;
import com.threadforge.chat.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * REST endpoints for LAN peer-to-peer file sharing (backed by vendored Fandem P2P library).
 */
@RestController
public class P2pShareRestController {

    private final CurrentUser currentUser;
    private final P2pShareService p2pService;
    private final RateLimiter rateLimiter;

    public P2pShareRestController(CurrentUser currentUser,
                                  P2pShareService p2pService,
                                  RateLimiter rateLimiter) {
        this.currentUser = currentUser;
        this.p2pService = p2pService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/p2p/state")
    public StateView state(Authentication auth) {
        var me = currentUser.resolve(auth);
        return p2pService.stateFor(me);
    }

    @PostMapping("/api/p2p/shares")
    public ResponseEntity<ShareView> createShare(HttpServletRequest request, Authentication auth)
            throws IOException {
        var me = currentUser.resolve(auth);
        if (!rateLimiter.tryAcquire(me.getUsername(), "p2p-share", 15, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("P2P share rate limit exceeded");
        }
        var upload = RawUpload.from(request, true);
        var created = p2pService.startShare(me, upload);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/api/p2p/shares/sessions")
    public ResponseEntity<StagingSessionView> createStagingSession(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "folder", defaultValue = "false") boolean folder,
            Authentication auth) throws IOException {
        var me = currentUser.resolve(auth);
        if (!rateLimiter.tryAcquire(me.getUsername(), "p2p-share", 15, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("P2P share rate limit exceeded");
        }
        var session = p2pService.createStagingSession(me, name, folder);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @PostMapping("/api/p2p/shares/sessions/{sessionId}/files")
    public StagedFileView uploadStagedFile(@PathVariable String sessionId,
                                           HttpServletRequest request,
                                           Authentication auth) throws IOException {
        var me = currentUser.resolve(auth);
        var upload = RawUpload.from(request, true);
        return p2pService.addFileToStagingSession(me, sessionId, upload);
    }

    @PostMapping("/api/p2p/shares/sessions/{sessionId}/finalize")
    public ResponseEntity<ShareView> finalizeStagingSession(@PathVariable String sessionId,
                                                            Authentication auth) throws IOException {
        var me = currentUser.resolve(auth);
        var created = p2pService.finalizeStagingSession(me, sessionId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/api/p2p/shares/sessions/{sessionId}")
    public ResponseEntity<Void> abortStagingSession(@PathVariable String sessionId,
                                                    Authentication auth) {
        var me = currentUser.resolve(auth);
        p2pService.abortStagingSession(me, sessionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/p2p/shares/{id}")
    public ShareView cancelShare(@PathVariable String id, Authentication auth) {
        var me = currentUser.resolve(auth);
        return p2pService.cancelShare(id, me);
    }

    @GetMapping("/api/p2p/shares/{id}/download")
    public ResponseEntity<StreamingResponseBody> downloadShare(@PathVariable String id,
                                                               Authentication auth) throws IOException {
        var me = currentUser.resolve(auth);
        if (!rateLimiter.tryAcquire(me.getUsername(), "p2p-receive", 20, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("P2P receive rate limit exceeded");
        }
        return streamReceived(p2pService.receiveByShareId(id));
    }

    @GetMapping("/api/p2p/receive")
    public ResponseEntity<StreamingResponseBody> receiveFromTarget(@RequestParam("target") String target,
                                                                   Authentication auth) throws IOException {
        var me = currentUser.resolve(auth);
        if (!rateLimiter.tryAcquire(me.getUsername(), "p2p-receive", 20, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("P2P receive rate limit exceeded");
        }
        return streamReceived(p2pService.receiveByTarget(target));
    }

    private static ResponseEntity<StreamingResponseBody> streamReceived(ReceivedPayload payload)
            throws IOException {
        var files = payload.files();
        if (files.size() == 1) {
            File file = files.get(0);
            long size = file.length();
            var headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(file.getName(), StandardCharsets.UTF_8)
                    .build());
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(size);
            headers.set("X-Content-Type-Options", "nosniff");
            StreamingResponseBody body = out -> {
                try (payload; var in = Files.newInputStream(file.toPath())) {
                    in.transferTo(out);
                }
            };
            return ResponseEntity.ok().headers(headers).body(body);
        }

        var headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("p2p-files.zip", StandardCharsets.UTF_8)
                .build());
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set("X-Content-Type-Options", "nosniff");
        StreamingResponseBody body = out -> {
            try (payload; var zip = new ZipOutputStream(out)) {
                for (File f : files) {
                    zip.putNextEntry(new ZipEntry(f.getName()));
                    try (var in = Files.newInputStream(f.toPath())) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
