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
import com.threadforge.chat.p2p.P2pShareDto.DiscoveredPeerView;
import com.threadforge.chat.p2p.P2pShareDto.FileSummary;
import com.threadforge.chat.p2p.P2pShareDto.ShareStatus;
import com.threadforge.chat.p2p.P2pShareDto.ShareView;
import com.threadforge.chat.p2p.P2pShareDto.StagedFileView;
import com.threadforge.chat.p2p.P2pShareDto.StagingSessionView;
import com.threadforge.chat.p2p.P2pShareDto.StateView;
import com.threadforge.chat.security.PublicBadRequestException;
import com.threadforge.chat.security.ResourceNotFoundException;
import com.threadforge.chat.security.UploadTooLargeException;
import com.threadforge.chat.web.RawUpload;
import com.tambapps.p2p.fandem.Fandem;
import com.tambapps.p2p.fandem.FileReceiver;
import com.tambapps.p2p.fandem.FileSender;
import com.tambapps.p2p.fandem.SenderPeer;
import com.tambapps.p2p.fandem.exception.CorruptedFileException;
import com.tambapps.p2p.fandem.exception.SharingException;
import com.tambapps.p2p.fandem.model.SendingFileData;
import com.tambapps.p2p.fandem.util.FileUtils;
import com.tambapps.p2p.fandem.util.TransferListener;
import com.tambapps.p2p.speer.Peer;
import com.tambapps.p2p.speer.datagram.service.MulticastReceiverService;
import com.tambapps.p2p.speer.datagram.service.PeriodicMulticastService;
import com.tambapps.p2p.speer.util.PeerUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Coordinates LAN peer-to-peer file sharing using the vendored Fandem library
 * ({@code com.tambapps.p2p.fandem}).
 *
 * <p>Sending starts a Fandem TCP {@link FileSender} on the host's private LAN address and
 * advertises it over UDP multicast ({@code ff02::1:50000}) so external Fandem Android, Desktop,
 * or CLI peers on the same network—as well as other browser sessions on this workspace—can
 * discover and download the file directly with SHA-256 verification.
 */
@Service
public class P2pShareService implements MulticastReceiverService.DiscoveryListener<List<SenderPeer>> {

    private static final Logger log = LoggerFactory.getLogger(P2pShareService.class);
    private static final Duration DISCOVERED_PEER_TTL = Duration.ofSeconds(12);
    private static final Duration FINISHED_SHARE_RETENTION = Duration.ofMinutes(10);
    private static final Duration STAGING_SESSION_TTL = Duration.ofMinutes(15);
    private static final List<String> VIRTUAL_INTERFACE_PREFIXES = List.of(
            "docker", "br-", "veth", "virbr", "cni", "flannel", "podman", "tailscale", "tun", "wg", "zt");

    private final P2pProperties properties;
    private final Path stagingRoot;

    private final ConcurrentMap<String, ActiveShare> shares = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StagingSession> stagingSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DiscoveredEntry> discoveredPeers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService multicastExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "p2p-fandem-multicast");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService discoveryExecutor =
            Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "p2p-fandem-discovery");
                t.setDaemon(true);
                return t;
            });

    private PeriodicMulticastService<List<SenderPeer>> multicastService;
    private MulticastReceiverService<List<SenderPeer>> receiverService;
    private final List<SenderPeer> advertisedPeers = new CopyOnWriteArrayList<>();
    private volatile boolean multicastDiscoveryActive;

    public P2pShareService(P2pProperties properties) {
        this.properties = properties;
        this.stagingRoot = Path.of(properties.getStagingDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Files.createDirectories(stagingRoot);
        } catch (IOException e) {
            log.warn("Could not create P2P staging directory {}: {}", stagingRoot, e.getMessage());
        }

        multicastService = Fandem.multicastService(multicastExecutor);
        multicastService.setData(advertisedPeers);

        if (properties.isDiscoveryEnabled()) {
            receiverService = Fandem.senderPeersReceiverService(discoveryExecutor, this);
            try {
                receiverService.start();
                multicastDiscoveryActive = true;
                log.info("Fandem LAN P2P discovery listening on [ff02::1]:{}", Fandem.PEER_DISCOVERY_PORT);
            } catch (IOException e) {
                multicastDiscoveryActive = false;
                log.warn("Fandem UDP multicast discovery unavailable on this host ({}); peer-key and workspace LAN shares still work",
                        e.getMessage());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (receiverService != null) {
            try {
                receiverService.stop();
            } catch (Exception ignored) {}
        }
        if (multicastService != null && multicastService.isRunning()) {
            try {
                multicastService.stop(true);
            } catch (Exception ignored) {}
        }
        for (var share : shares.values()) {
            share.cancelInternal();
            deleteQuietly(share.tempDir);
        }
        shares.clear();
        for (var session : stagingSessions.values()) {
            deleteQuietly(session.tempDir);
        }
        stagingSessions.clear();
        multicastExecutor.shutdownNow();
        discoveryExecutor.shutdownNow();
    }

    public StateView stateFor(User viewer) {
        pruneExpired();
        var myShares = new ArrayList<ShareView>();
        var lanShares = new ArrayList<ShareView>();
        var byCreated = Comparator.comparing(ActiveShare::createdAt).reversed();

        shares.values().stream()
                .sorted(byCreated)
                .forEach(s -> {
                    var mine = viewer != null && viewer.getUsername().equals(s.ownerUsername);
                    var view = s.toView(mine);
                    if (mine) {
                        myShares.add(view);
                    } else if (s.status == ShareStatus.WAITING || s.status == ShareStatus.TRANSFERRING) {
                        lanShares.add(view);
                    }
                });

        var cutoff = Instant.now().minus(DISCOVERED_PEER_TTL);
        var discovered = discoveredPeers.values().stream()
                .filter(entry -> entry.lastSeen().isAfter(cutoff))
                .sorted(Comparator.comparing(DiscoveredEntry::lastSeen).reversed())
                .map(DiscoveredEntry::toView)
                .toList();

        return new StateView(
                properties.isEnabled(),
                multicastDiscoveryActive,
                resolveBindAddress().getHostAddress(),
                properties.getMaxFileBytes(),
                myShares,
                lanShares,
                discovered);
    }

    public ShareView startShare(User owner, RawUpload upload) throws IOException {
        ensureEnabled();
        long maxBytes = properties.getMaxFileBytes();
        if (upload.declaredLength() > maxBytes) {
            throw new UploadTooLargeException(maxBytes);
        }
        var cleanName = sanitizeFilename(upload.filename());
        var shareId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var shareDir = stagingRoot.resolve(shareId);
        Files.createDirectories(shareDir);
        var stagedFile = shareDir.resolve(cleanName);

        long written;
        try {
            written = copyWithLimit(upload.body(), stagedFile, maxBytes);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(shareDir);
            throw e;
        }
        if (written <= 0) {
            deleteQuietly(shareDir);
            throw new PublicBadRequestException("Cannot share an empty file.");
        }

        return launchShare(owner, shareId, shareDir, cleanName, written, List.of(stagedFile));
    }

    public StagingSessionView createStagingSession(User owner, String rawName, boolean folder) throws IOException {
        ensureEnabled();
        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String cleanName = sanitizeLabel(rawName, folder ? "shared-folder" : "shared-files");
        Path sessionDir = stagingRoot.resolve("stage-" + sessionId);
        Path filesDir = sessionDir.resolve("files");
        Files.createDirectories(filesDir);

        Instant now = Instant.now();
        var session = new StagingSession(
                sessionId,
                owner.getUsername(),
                cleanName,
                folder,
                sessionDir,
                filesDir,
                now.plus(STAGING_SESSION_TTL));
        stagingSessions.put(sessionId, session);
        return new StagingSessionView(
                sessionId,
                cleanName,
                folder,
                properties.getMaxFileBytes(),
                properties.getMaxFilesPerShare());
    }

    public StagedFileView addFileToStagingSession(User owner, String sessionId, RawUpload upload) throws IOException {
        ensureEnabled();
        var session = requireOwnedSession(owner, sessionId);
        long maxTotalBytes = properties.getMaxFileBytes();
        int maxFiles = properties.getMaxFilesPerShare();

        String relativePath = sanitizeRelativePath(upload.filename());
        if (upload.declaredLength() > 0 && session.totalBytes.get() + upload.declaredLength() > maxTotalBytes) {
            throw new UploadTooLargeException(maxTotalBytes);
        }

        synchronized (session) {
            if (session.closed) {
                throw new PublicBadRequestException("Staging session has already been finalized or cancelled.");
            }
            if (session.files.size() >= maxFiles && !session.files.containsKey(relativePath)) {
                throw new PublicBadRequestException("Too many files in one share (max " + maxFiles + ").");
            }
            long remaining = maxTotalBytes - session.totalBytes.get();
            if (remaining < 0) {
                throw new UploadTooLargeException(maxTotalBytes);
            }

            Path target = session.filesDir.resolve(relativePath).normalize();
            if (!target.startsWith(session.filesDir)) {
                throw new PublicBadRequestException("Invalid file path.");
            }
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            long written;
            try {
                written = copyWithLimit(upload.body(), target, remaining);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(target);
                throw e;
            }
            Long previousSize = session.files.put(relativePath, written);
            long delta = written - (previousSize != null ? previousSize : 0L);
            long newTotal = session.totalBytes.addAndGet(delta);
            int count = session.fileCount.updateAndGet(c -> session.files.size());
            return new StagedFileView(relativePath, written, count, newTotal);
        }
    }

    public ShareView finalizeStagingSession(User owner, String sessionId) throws IOException {
        ensureEnabled();
        var session = requireOwnedSession(owner, sessionId);
        List<String> relativePaths;
        synchronized (session) {
            if (session.closed) {
                throw new PublicBadRequestException("Staging session has already been finalized or cancelled.");
            }
            session.closed = true;
            stagingSessions.remove(sessionId, session);
            relativePaths = new ArrayList<>(session.files.keySet());
        }

        if (relativePaths.isEmpty()) {
            deleteQuietly(session.tempDir);
            throw new PublicBadRequestException("No files were uploaded to share.");
        }

        String shareId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path shareDir = stagingRoot.resolve(shareId);
        Files.createDirectories(shareDir);

        try {
            boolean hasNestedPaths = relativePaths.stream().anyMatch(p -> p.indexOf('/') >= 0);
            if (session.folder || hasNestedPaths) {
                String zipName = session.name.toLowerCase(Locale.ROOT).endsWith(".zip")
                        ? session.name
                        : session.name + ".zip";
                Path zipPath = shareDir.resolve(zipName);
                packZip(session.filesDir, relativePaths, zipPath);
                long zipSize = Files.size(zipPath);
                if (zipSize <= 0) {
                    throw new PublicBadRequestException("Cannot share an empty folder.");
                }
                deleteQuietly(session.tempDir);
                return launchShare(owner, shareId, shareDir, zipName, zipSize, List.of(zipPath));
            }

            List<Path> stagedFiles = new ArrayList<>(relativePaths.size());
            long totalSize = 0L;
            for (String rel : relativePaths) {
                Path src = session.filesDir.resolve(rel);
                long sz = Files.size(src);
                if (sz <= 0 && relativePaths.size() == 1) {
                    throw new PublicBadRequestException("Cannot share an empty file.");
                }
                Path dst = shareDir.resolve(Path.of(rel).getFileName().toString());
                Files.move(src, dst);
                stagedFiles.add(dst);
                totalSize += sz;
            }
            deleteQuietly(session.tempDir);
            if (totalSize <= 0) {
                throw new PublicBadRequestException("Cannot share empty files.");
            }
            String displayName = stagedFiles.size() == 1
                    ? stagedFiles.get(0).getFileName().toString()
                    : stagedFiles.size() + " files (" + stagedFiles.get(0).getFileName().toString() + ", …)";
            return launchShare(owner, shareId, shareDir, displayName, totalSize, stagedFiles);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(session.tempDir);
            deleteQuietly(shareDir);
            throw e;
        }
    }

    public void abortStagingSession(User owner, String sessionId) {
        ensureEnabled();
        var session = requireOwnedSession(owner, sessionId);
        synchronized (session) {
            session.closed = true;
        }
        stagingSessions.remove(sessionId, session);
        deleteQuietly(session.tempDir);
    }

    private StagingSession requireOwnedSession(User owner, String sessionId) {
        var session = stagingSessions.get(sessionId);
        if (session == null) {
            throw new ResourceNotFoundException("P2P staging session not found");
        }
        if (!owner.isAdmin() && !owner.getUsername().equals(session.ownerUsername)) {
            throw new AccessDeniedException("Only the owner can modify this P2P staging session");
        }
        return session;
    }

    private ShareView launchShare(User owner, String shareId, Path shareDir,
                                  String displayFileName, long totalBytes,
                                  List<Path> stagedFiles) {
        InetAddress bindAddr = resolveBindAddress();
        int port;
        try {
            port = PeerUtils.getAvailablePort(bindAddr, SenderPeer.DEFAULT_PORT);
        } catch (RuntimeException e) {
            deleteQuietly(shareDir);
            throw e;
        }

        Peer peer = Peer.of(bindAddr, port);
        String peerKey = formatPeerKey(peer);
        List<SendingFileData> fileDataList = new ArrayList<>(stagedFiles.size());
        for (Path p : stagedFiles) {
            fileDataList.add(SendingFileData.fromFile(p.toFile()));
        }
        String deviceLabel = (owner.getDisplayName() != null && !owner.getDisplayName().isBlank()
                ? owner.getDisplayName()
                : owner.getUsername()) + " (ThreadForge)";
        SenderPeer senderPeer = SenderPeer.of(peer, deviceLabel, fileDataList);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getShareTtl());
        var share = new ActiveShare(
                shareId,
                owner.getUsername(),
                owner.getDisplayName() != null && !owner.getDisplayName().isBlank()
                        ? owner.getDisplayName() : owner.getUsername(),
                peer,
                peerKey,
                senderPeer,
                displayFileName,
                totalBytes,
                shareDir,
                now,
                expiresAt);

        var listener = new TransferListener() {
            private long completedBeforeCurrent = 0L;
            private long currentFileSize = 0L;
            private String currentFileName = null;

            @Override
            public void onConnected(Peer selfPeer, Peer remotePeer) {
                share.markTransferring();
            }

            @Override
            public void onTransferStarted(String fileName, long fileSize) {
                if (currentFileName != null) {
                    completedBeforeCurrent += currentFileSize;
                }
                currentFileName = fileName;
                currentFileSize = Math.max(0L, fileSize);
                share.markTransferring();
            }

            @Override
            public void onProgressUpdate(String fileName, int progress, long bytesProcessed, long totalFileBytes) {
                long aggregateBytes = Math.min(totalBytes, completedBeforeCurrent + bytesProcessed);
                int aggregatePct = totalBytes > 0
                        ? (int) Math.min(100L, (aggregateBytes * 100L) / totalBytes)
                        : progress;
                share.updateProgress(aggregatePct, aggregateBytes);
            }
        };

        var sender = new FileSender(peer, listener, properties.getAcceptTimeoutMs());
        share.attachSender(sender);
        shares.put(shareId, share);
        registerAdvertisedPeer(senderPeer);

        Thread.startVirtualThread(() -> runSender(share, sender, fileDataList));
        return share.toView(true);
    }

    private void runSender(ActiveShare share, FileSender sender, List<SendingFileData> fileDataList) {
        try {
            sender.send(fileDataList);
            if (share.status != ShareStatus.CANCELLED) {
                share.markCompleted();
            }
        } catch (SocketTimeoutException e) {
            if (share.status != ShareStatus.CANCELLED) {
                share.markExpired("No peer connected before timeout.");
            }
        } catch (IOException e) {
            if (share.status != ShareStatus.CANCELLED) {
                share.markFailed(e.getMessage());
                log.warn("P2P share {} failed: {}", share.id, e.getMessage());
            }
        } finally {
            unregisterAdvertisedPeer(share.senderPeer);
            deleteQuietly(share.tempDir);
        }
    }

    public ShareView cancelShare(String id, User requester) {
        ensureEnabled();
        var share = shares.get(id);
        if (share == null) {
            throw new ResourceNotFoundException("P2P share not found");
        }
        if (!requester.isAdmin() && !requester.getUsername().equals(share.ownerUsername)) {
            throw new AccessDeniedException("Only the sender can cancel this P2P share");
        }
        share.cancelInternal();
        unregisterAdvertisedPeer(share.senderPeer);
        deleteQuietly(share.tempDir);
        return share.toView(requester.getUsername().equals(share.ownerUsername));
    }

    public ReceivedPayload receiveByShareId(String shareId) throws IOException {
        ensureEnabled();
        var share = shares.get(shareId);
        if (share == null) {
            throw new ResourceNotFoundException("P2P share not found");
        }
        if (share.status != ShareStatus.WAITING) {
            throw new PublicBadRequestException("This P2P share is no longer waiting for a receiver ("
                    + share.status.name().toLowerCase(Locale.ROOT) + ").");
        }
        return receiveFromPeer(share.peer);
    }

    public ReceivedPayload receiveByTarget(String target) throws IOException {
        ensureEnabled();
        Peer peer = parseAndValidatePeer(target);
        return receiveFromPeer(peer);
    }

    private ReceivedPayload receiveFromPeer(Peer peer) throws IOException {
        Path tempDir = Files.createTempDirectory(stagingRoot, "rx-");
        try {
            var receiver = new FileReceiver();
            List<File> files = receiver.receiveFrom(peer, FileUtils.availableFileInDirectoryProvider(tempDir.toFile()));
            if (files.isEmpty()) {
                deleteQuietly(tempDir);
                throw new PublicBadRequestException("Peer sent no files.");
            }
            return new ReceivedPayload(tempDir, files);
        } catch (CorruptedFileException e) {
            deleteQuietly(tempDir);
            throw new PublicBadRequestException("File checksum verification failed (corrupted transfer).");
        } catch (SharingException e) {
            deleteQuietly(tempDir);
            throw new PublicBadRequestException("P2P transfer failed: " + e.getMessage());
        } catch (IOException e) {
            deleteQuietly(tempDir);
            throw new PublicBadRequestException("Could not connect to peer " + peer + ": " + e.getMessage());
        }
    }

    public Peer parseAndValidatePeer(String rawTarget) {
        if (rawTarget == null || rawTarget.isBlank()) {
            throw new PublicBadRequestException("Enter a Peer Key or host:port.");
        }
        String trimmed = rawTarget.trim();
        Peer peer;
        if (Fandem.isCorrectPeerKey(trimmed)) {
            peer = Fandem.parsePeerFromHexString(trimmed);
        } else {
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new PublicBadRequestException(
                        "Invalid peer target. Use an 8/10-character Fandem Peer Key or host:port.");
            }
            String host = trimmed.substring(0, colon).trim();
            if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
                host = host.substring(1, host.length() - 1);
            }
            int port;
            try {
                port = Integer.parseInt(trimmed.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                throw new PublicBadRequestException("Invalid peer port.");
            }
            if (port < 1 || port > 65535) {
                throw new PublicBadRequestException("Peer port must be between 1 and 65535.");
            }
            try {
                peer = Peer.of(InetAddress.getByName(host), port);
            } catch (UnknownHostException e) {
                throw new PublicBadRequestException("Unknown peer host: " + host);
            }
        }
        validateLanAddress(peer.getAddress());
        return peer;
    }

    static void validateLanAddress(InetAddress addr) {
        if (addr == null || addr.isLinkLocalAddress() || addr.isMulticastAddress() || addr.isAnyLocalAddress()) {
            throw new PublicBadRequestException("Peer address must be on the local network.");
        }
        if (!(addr.isSiteLocalAddress() || addr.isLoopbackAddress())) {
            throw new PublicBadRequestException(
                    "Only local network (RFC 1918) or loopback peers are permitted for LAN P2P transfers.");
        }
    }

    @Override
    public void onDiscovery(List<SenderPeer> senderPeers) {
        if (senderPeers == null) {
            return;
        }
        Instant now = Instant.now();
        for (SenderPeer sp : senderPeers) {
            if (sp == null || sp.getAddress() == null) {
                continue;
            }
            String key = sp.getAddress().getHostAddress() + ":" + sp.getPort();
            discoveredPeers.put(key, new DiscoveredEntry(sp, formatPeerKey(sp), now));
        }
    }

    @Override
    public void onError(IOException e) {
        log.debug("Fandem multicast discovery error: {}", e.getMessage());
    }

    @Scheduled(fixedDelay = 60_000)
    void pruneExpired() {
        Instant now = Instant.now();
        var peerCutoff = now.minus(DISCOVERED_PEER_TTL);
        discoveredPeers.entrySet().removeIf(e -> e.getValue().lastSeen().isBefore(peerCutoff));

        stagingSessions.entrySet().removeIf(entry -> {
            StagingSession s = entry.getValue();
            if (now.isAfter(s.expiresAt)) {
                synchronized (s) {
                    s.closed = true;
                }
                deleteQuietly(s.tempDir);
                return true;
            }
            return false;
        });

        var shareRetentionCutoff = now.minus(FINISHED_SHARE_RETENTION);
        shares.entrySet().removeIf(entry -> {
            ActiveShare s = entry.getValue();
            if (s.status == ShareStatus.WAITING && now.isAfter(s.expiresAt)) {
                s.cancelInternal();
                s.markExpired("Share expired.");
                unregisterAdvertisedPeer(s.senderPeer);
                deleteQuietly(s.tempDir);
            }
            return s.status != ShareStatus.WAITING
                    && s.status != ShareStatus.TRANSFERRING
                    && s.finishedAt != null
                    && s.finishedAt.isBefore(shareRetentionCutoff);
        });
    }

    private synchronized void registerAdvertisedPeer(SenderPeer peer) {
        advertisedPeers.add(peer);
        if (multicastService != null && !advertisedPeers.isEmpty() && !multicastService.isRunning()) {
            try {
                multicastService.start(1000L);
            } catch (IOException e) {
                log.debug("Could not start Fandem UDP multicast broadcaster: {}", e.getMessage());
            }
        }
    }

    private synchronized void unregisterAdvertisedPeer(SenderPeer peer) {
        advertisedPeers.remove(peer);
        if (multicastService != null && advertisedPeers.isEmpty() && multicastService.isRunning()) {
            try {
                multicastService.stop(false);
            } catch (Exception ignored) {}
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new PublicBadRequestException("LAN P2P file sharing is disabled on this server.");
        }
    }

    InetAddress resolveBindAddress() {
        String configured = properties.getBindAddress();
        if (configured != null && !configured.isBlank()) {
            try {
                return InetAddress.getByName(configured.trim());
            } catch (UnknownHostException e) {
                log.warn("Invalid tforge.p2p.bind-address '{}'; falling back to auto-detection", configured);
            }
        }
        InetAddress preferred = findPreferredLanAddress();
        if (preferred != null) {
            return preferred;
        }
        InetAddress lan = PeerUtils.getPrivateNetworkIpAddressOrNull();
        return lan != null ? lan : InetAddress.getLoopbackAddress();
    }

    private static InetAddress findPreferredLanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            InetAddress fallback = null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) {
                    continue;
                }
                String name = ni.getName().toLowerCase(Locale.ROOT);
                boolean isContainerOrTunnel = VIRTUAL_INTERFACE_PREFIXES.stream().anyMatch(name::startsWith);
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || !addr.isSiteLocalAddress()) {
                        continue;
                    }
                    if (!isContainerOrTunnel) {
                        return addr;
                    }
                    if (fallback == null) {
                        fallback = addr;
                    }
                }
            }
            return fallback;
        } catch (SocketException e) {
            return null;
        }
    }

    static String formatPeerKey(Peer peer) {
        int offset = peer.getPort() - SenderPeer.DEFAULT_PORT;
        if (peer.getAddress() != null
                && peer.getAddress().getAddress().length == 4
                && offset >= 0 && offset <= 255) {
            return Fandem.toHexString(peer);
        }
        return peer.toString();
    }

    private static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PublicBadRequestException("Missing filename");
        }
        String normalized = raw.replace('\\', '/');
        String name = Path.of(normalized).getFileName().toString().trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new PublicBadRequestException("Invalid filename");
        }
        return name.length() > 180 ? name.substring(0, 180) : name;
    }

    private static String sanitizeLabel(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String cleaned = raw.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[\\p{Cntrl}/:*?\"<>|]+", "_").trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1).trim();
        }
        if (cleaned.isEmpty()) {
            return fallback;
        }
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    static String sanitizeRelativePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PublicBadRequestException("Missing file path");
        }
        String normalized = raw.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("/");
        List<String> cleanParts = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || ".".equals(trimmed)) {
                continue;
            }
            if ("..".equals(trimmed) || trimmed.indexOf('\0') >= 0) {
                throw new PublicBadRequestException("Invalid file path: " + raw);
            }
            cleanParts.add(trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed);
        }
        if (cleanParts.isEmpty()) {
            throw new PublicBadRequestException("Invalid file path");
        }
        return String.join("/", cleanParts);
    }

    private static void packZip(Path filesRoot, List<String> relativePaths, Path destinationZip) throws IOException {
        byte[] buf = new byte[32_768];
        try (var zos = new ZipOutputStream(Files.newOutputStream(destinationZip))) {
            zos.setLevel(Deflater.BEST_SPEED);
            for (String rel : relativePaths) {
                Path src = filesRoot.resolve(rel);
                if (!Files.isRegularFile(src)) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(rel));
                try (InputStream in = Files.newInputStream(src)) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private static long copyWithLimit(InputStream in, Path dest, long maxBytes) throws IOException {
        long total = 0;
        byte[] buf = new byte[16_384];
        try (OutputStream out = Files.newOutputStream(dest)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    throw new UploadTooLargeException(maxBytes);
                }
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    public record ReceivedPayload(Path tempDir, List<File> files) implements AutoCloseable {
        @Override
        public void close() {
            deleteQuietly(tempDir);
        }
    }

    private record DiscoveredEntry(SenderPeer peer, String peerKey, Instant lastSeen) {
        DiscoveredPeerView toView() {
            var summaries = peer.getFiles() == null ? List.<FileSummary>of()
                    : peer.getFiles().stream()
                            .map(f -> new FileSummary(f.getFileName(), f.getFileSize(), f.getChecksum().orElse(null)))
                            .toList();
            return new DiscoveredPeerView(
                    peerKey,
                    peer.getAddress().getHostAddress(),
                    peer.getPort(),
                    peer.getDeviceName(),
                    summaries,
                    lastSeen);
        }
    }

    private static final class ActiveShare {
        final String id;
        final String ownerUsername;
        final String ownerDisplayName;
        final Peer peer;
        final String peerKey;
        final SenderPeer senderPeer;
        final String fileName;
        final long fileSize;
        final Path tempDir;
        final Instant createdAt;
        final Instant expiresAt;

        volatile FileSender sender;
        volatile ShareStatus status = ShareStatus.WAITING;
        volatile int progressPercent;
        volatile long bytesTransferred;
        volatile String errorMessage;
        volatile Instant finishedAt;

        ActiveShare(String id, String ownerUsername, String ownerDisplayName,
                    Peer peer, String peerKey, SenderPeer senderPeer,
                    String fileName, long fileSize, Path tempDir,
                    Instant createdAt, Instant expiresAt) {
            this.id = id;
            this.ownerUsername = ownerUsername;
            this.ownerDisplayName = ownerDisplayName;
            this.peer = peer;
            this.peerKey = peerKey;
            this.senderPeer = senderPeer;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.tempDir = tempDir;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        void attachSender(FileSender sender) { this.sender = sender; }
        Instant createdAt() { return createdAt; }

        void markTransferring() {
            if (status == ShareStatus.WAITING) {
                status = ShareStatus.TRANSFERRING;
            }
        }

        void updateProgress(int progress, long bytes) {
            this.status = ShareStatus.TRANSFERRING;
            this.progressPercent = Math.max(0, Math.min(100, progress));
            this.bytesTransferred = bytes;
        }

        void markCompleted() {
            this.status = ShareStatus.COMPLETED;
            this.progressPercent = 100;
            this.bytesTransferred = fileSize;
            this.finishedAt = Instant.now();
        }

        void markExpired(String message) {
            this.status = ShareStatus.EXPIRED;
            this.errorMessage = message;
            this.finishedAt = Instant.now();
        }

        void markFailed(String message) {
            this.status = ShareStatus.FAILED;
            this.errorMessage = message;
            this.finishedAt = Instant.now();
        }

        void cancelInternal() {
            this.status = ShareStatus.CANCELLED;
            this.finishedAt = Instant.now();
            if (sender != null) {
                sender.cancel();
            }
        }

        ShareView toView(boolean mine) {
            return new ShareView(
                    id,
                    ownerUsername,
                    ownerDisplayName,
                    peerKey,
                    peer.getAddress().getHostAddress(),
                    peer.getPort(),
                    fileName,
                    fileSize,
                    status,
                    progressPercent,
                    bytesTransferred,
                    errorMessage,
                    createdAt,
                    expiresAt,
                    mine);
        }
    }

    private static final class StagingSession {
        final String id;
        final String ownerUsername;
        final String name;
        final boolean folder;
        final Path tempDir;
        final Path filesDir;
        final Instant expiresAt;
        final Map<String, Long> files = new LinkedHashMap<>();
        final AtomicLong totalBytes = new AtomicLong();
        final AtomicInteger fileCount = new AtomicInteger();
        boolean closed;

        StagingSession(String id, String ownerUsername, String name, boolean folder,
                       Path tempDir, Path filesDir, Instant expiresAt) {
            this.id = id;
            this.ownerUsername = ownerUsername;
            this.name = name;
            this.folder = folder;
            this.tempDir = tempDir;
            this.filesDir = filesDir;
            this.expiresAt = expiresAt;
        }
    }
}
