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

import java.time.Instant;
import java.util.List;

/**
 * JSON payloads for the LAN P2P file sharing API ({@code /api/p2p/**}).
 */
public final class P2pShareDto {

    private P2pShareDto() {}

    public enum ShareStatus {
        WAITING,
        TRANSFERRING,
        COMPLETED,
        CANCELLED,
        EXPIRED,
        FAILED
    }

    public record FileSummary(String fileName, long fileSize, String checksum) {}

    public record ShareView(
            String id,
            String ownerUsername,
            String ownerDisplayName,
            String peerKey,
            String host,
            int port,
            String fileName,
            long fileSize,
            ShareStatus status,
            int progressPercent,
            long bytesTransferred,
            String errorMessage,
            Instant createdAt,
            Instant expiresAt,
            boolean mine) {}

    public record DiscoveredPeerView(
            String peerKey,
            String host,
            int port,
            String deviceName,
            List<FileSummary> files,
            Instant lastSeen) {}

    public record StateView(
            boolean enabled,
            boolean multicastDiscoveryActive,
            String localAddress,
            long maxFileBytes,
            List<ShareView> myShares,
            List<ShareView> lanShares,
            List<DiscoveredPeerView> discoveredPeers) {}

    public record StagingSessionView(
            String sessionId,
            String name,
            boolean folder,
            long maxTotalBytes,
            int maxFiles) {}

    public record StagedFileView(
            String relativePath,
            long fileSize,
            int totalFiles,
            long totalBytes) {}
}
