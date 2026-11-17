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

package com.threadforge.chat.p2p;

import com.threadforge.chat.domain.User;
import com.threadforge.chat.p2p.P2pShareDto.ShareStatus;
import com.threadforge.chat.security.PublicBadRequestException;
import com.threadforge.chat.security.UploadTooLargeException;
import com.threadforge.chat.web.RawUpload;
import com.tambapps.p2p.fandem.Fandem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P2pShareServiceTest {

    @TempDir
    Path tempDir;

    private P2pProperties properties;
    private P2pShareService service;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        properties = new P2pProperties();
        properties.setStagingDir(tempDir.toString());
        properties.setDiscoveryEnabled(false);
        properties.setAcceptTimeoutMs(15_000);
        service = new P2pShareService(properties);
        service.init();

        alice = new User("sub-alice", "alice", "alice@example.com", "Alice");
        bob = new User("sub-bob", "bob", "bob@example.com", "Bob");
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void startShareAndReceiveByShareIdTransfersExactBytes() throws Exception {
        byte[] payload = new byte[64 * 1024];
        new Random(42).nextBytes(payload);

        var upload = new RawUpload(
                "folder/diagram.bin",
                "application/octet-stream",
                null,
                payload.length,
                new ByteArrayInputStream(payload));

        var share = service.startShare(alice, upload);
        assertThat(share.fileName()).isEqualTo("diagram.bin");
        assertThat(share.fileSize()).isEqualTo(payload.length);
        assertThat(share.status()).isEqualTo(ShareStatus.WAITING);
        assertThat(share.peerKey()).isNotBlank();
        assertThat(share.port()).isGreaterThan(0);

        // Bob sees Alice's share in lanShares
        var bobState = service.stateFor(bob);
        assertThat(bobState.lanShares()).extracting(P2pShareDto.ShareView::id).contains(share.id());

        try (var received = service.receiveByShareId(share.id())) {
            assertThat(received.files()).hasSize(1);
            var file = received.files().get(0);
            assertThat(file.getName()).isEqualTo("diagram.bin");
            assertThat(Files.readAllBytes(file.toPath())).isEqualTo(payload);
        }
    }

    @Test
    void receiveByTargetAcceptsPeerKeyAndHostPort() throws Exception {
        byte[] payload = "Fandem LAN peer transfer payload".getBytes();
        var upload = new RawUpload(
                "notes.txt",
                "text/plain",
                null,
                payload.length,
                new ByteArrayInputStream(payload));

        var share = service.startShare(alice, upload);
        String target = Fandem.isCorrectPeerKey(share.peerKey())
                ? share.peerKey()
                : share.host() + ":" + share.port();

        try (var received = service.receiveByTarget(target)) {
            assertThat(received.files()).hasSize(1);
            assertThat(Files.readAllBytes(received.files().get(0).toPath())).isEqualTo(payload);
        }
    }

    @Test
    void onlyOwnerOrAdminCanCancelShare() throws IOException {
        byte[] payload = "hello".getBytes();
        var upload = new RawUpload(
                "hello.txt",
                "text/plain",
                null,
                payload.length,
                new ByteArrayInputStream(payload));
        var share = service.startShare(alice, upload);

        assertThatThrownBy(() -> service.cancelShare(share.id(), bob))
                .isInstanceOf(AccessDeniedException.class);

        var cancelled = service.cancelShare(share.id(), alice);
        assertThat(cancelled.status()).isEqualTo(ShareStatus.CANCELLED);
    }

    @Test
    void rejectsEmptyOrOversizedFiles() {
        var emptyUpload = new RawUpload(
                "empty.txt",
                "text/plain",
                null,
                0,
                new ByteArrayInputStream(new byte[0]));
        assertThatThrownBy(() -> service.startShare(alice, emptyUpload))
                .isInstanceOf(PublicBadRequestException.class);

        properties.setMaxFileBytes(8);
        var tooBig = new RawUpload(
                "big.bin",
                "application/octet-stream",
                null,
                16,
                new ByteArrayInputStream(new byte[16]));
        assertThatThrownBy(() -> service.startShare(alice, tooBig))
                .isInstanceOf(UploadTooLargeException.class);
    }

    @Test
    void parseAndValidatePeerAllowsLanAndBlocksPublicOrMetadataAddresses() {
        var lan = service.parseAndValidatePeer("192.168.1.10:8081");
        assertThat(lan.getAddress().getHostAddress()).isEqualTo("192.168.1.10");
        assertThat(lan.getPort()).isEqualTo(8081);

        var keyPeer = service.parseAndValidatePeer("C0A8010A");
        assertThat(keyPeer.getAddress().getHostAddress()).isEqualTo("192.168.1.10");

        assertThatThrownBy(() -> service.parseAndValidatePeer("8.8.8.8:8081"))
                .isInstanceOf(PublicBadRequestException.class);
        assertThatThrownBy(() -> service.parseAndValidatePeer("169.254.169.254:80"))
                .isInstanceOf(PublicBadRequestException.class);
        assertThatThrownBy(() -> service.parseAndValidatePeer("192.168.1.10:99999"))
                .isInstanceOf(PublicBadRequestException.class);
        assertThatThrownBy(() -> service.parseAndValidatePeer("invalid-target"))
                .isInstanceOf(PublicBadRequestException.class);
    }

    @Test
    void folderStagingPacksNestedZipAndTransfersViaFandem() throws Exception {
        var session = service.createStagingSession(alice, "photos", true);
        byte[] aBytes = "hello root".getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = "nested image bytes".getBytes(StandardCharsets.UTF_8);

        service.addFileToStagingSession(alice, session.sessionId(),
                new RawUpload("photos/readme.txt", "text/plain", null, aBytes.length, new ByteArrayInputStream(aBytes)));
        service.addFileToStagingSession(alice, session.sessionId(),
                new RawUpload("photos/sub/pic.dat", "application/octet-stream", null, bBytes.length, new ByteArrayInputStream(bBytes)));

        var share = service.finalizeStagingSession(alice, session.sessionId());
        assertThat(share.fileName()).isEqualTo("photos.zip");
        assertThat(share.status()).isEqualTo(ShareStatus.WAITING);

        try (var received = service.receiveByShareId(share.id())) {
            assertThat(received.files()).hasSize(1);
            var zipFile = received.files().get(0);
            assertThat(zipFile.getName()).isEqualTo("photos.zip");

            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (var zis = new ZipInputStream(Files.newInputStream(zipFile.toPath()))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    entries.put(e.getName(), zis.readAllBytes());
                }
            }
            assertThat(entries).containsOnlyKeys("photos/readme.txt", "photos/sub/pic.dat");
            assertThat(entries.get("photos/readme.txt")).isEqualTo(aBytes);
            assertThat(entries.get("photos/sub/pic.dat")).isEqualTo(bBytes);
        }
    }

    @Test
    void multiFileStagingTransfersAllFilesNatively() throws Exception {
        var session = service.createStagingSession(alice, "shared-files", false);
        byte[] first = "first file".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second file".getBytes(StandardCharsets.UTF_8);

        service.addFileToStagingSession(alice, session.sessionId(),
                new RawUpload("one.txt", "text/plain", null, first.length, new ByteArrayInputStream(first)));
        service.addFileToStagingSession(alice, session.sessionId(),
                new RawUpload("two.txt", "text/plain", null, second.length, new ByteArrayInputStream(second)));

        var share = service.finalizeStagingSession(alice, session.sessionId());
        assertThat(share.fileName()).contains("2 files");
        assertThat(share.fileSize()).isEqualTo(first.length + second.length);

        try (var received = service.receiveByShareId(share.id())) {
            assertThat(received.files()).hasSize(2);
            assertThat(received.files()).extracting(java.io.File::getName)
                    .containsExactlyInAnyOrder("one.txt", "two.txt");
        }
    }

    @Test
    void stagingRejectsPathTraversalAndHonorsConfiguredBindAddress() throws Exception {
        var session = service.createStagingSession(alice, "docs", true);
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.addFileToStagingSession(alice, session.sessionId(),
                new RawUpload("../escape.txt", "text/plain", null, data.length, new ByteArrayInputStream(data))))
                .isInstanceOf(PublicBadRequestException.class);
        service.abortStagingSession(alice, session.sessionId());

        properties.setBindAddress("127.0.0.1");
        assertThat(service.resolveBindAddress().getHostAddress()).isEqualTo("127.0.0.1");
    }
}
