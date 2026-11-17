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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for LAN peer-to-peer file sharing powered by Fandem ({@code com.tambapps.p2p.fandem}).
 */
@Component
@ConfigurationProperties("tforge.p2p")
public class P2pProperties {

    /** Master switch for the P2P LAN file sharing feature. */
    private boolean enabled = true;

    /** Temporary staging directory while an outbound Fandem share is active. */
    private String stagingDir = "./data/p2p-staging";

    /** Maximum file size accepted for a single P2P share (default 512 MiB). */
    private long maxFileBytes = 536_870_912L;

    /** Maximum lifetime of an unconsumed share before it is automatically closed. */
    private Duration shareTtl = Duration.ofMinutes(15);

    /** Accept timeout on Fandem's TCP PeerServer socket, in milliseconds. */
    private int acceptTimeoutMs = 300_000;

    /** Whether to listen for Fandem UDP multicast announcements (ff02::1:50000) on the LAN. */
    private boolean discoveryEnabled = true;

    /**
     * Optional LAN IPv4 address to bind Fandem TCP transfers to (e.g. {@code 10.13.89.180}).
     * When blank, the service picks the first non-virtual private LAN interface (skipping
     * Docker/container bridges) before falling back to Fandem's default detection.
     */
    private String bindAddress = "";

    /** Maximum number of files accepted in a single staged folder or multi-file P2P share. */
    private int maxFilesPerShare = 2000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getStagingDir() { return stagingDir; }
    public void setStagingDir(String stagingDir) { this.stagingDir = stagingDir; }

    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }

    public Duration getShareTtl() { return shareTtl; }
    public void setShareTtl(Duration shareTtl) { this.shareTtl = shareTtl; }

    public int getAcceptTimeoutMs() { return acceptTimeoutMs; }
    public void setAcceptTimeoutMs(int acceptTimeoutMs) { this.acceptTimeoutMs = acceptTimeoutMs; }

    public boolean isDiscoveryEnabled() { return discoveryEnabled; }
    public void setDiscoveryEnabled(boolean discoveryEnabled) { this.discoveryEnabled = discoveryEnabled; }

    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }

    public int getMaxFilesPerShare() { return maxFilesPerShare; }
    public void setMaxFilesPerShare(int maxFilesPerShare) { this.maxFilesPerShare = maxFilesPerShare; }
}
