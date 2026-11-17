# ThreadForge

**ThreadForge** is a self-hosted, real-time team communication workspace built with **Java 25** and **Spring Boot 4** — combining structured channels, threaded replies, 1:1 WebRTC voice/video calls, LAN peer-to-peer file sharing, and embedded full-text search in a single deployable server.

---

## Highlights

* **Channels, Threads & DMs** — Public and private rooms, 1:1 direct messages, group chats, pinned messages, message forwarding, saved bookmarks, and isolated reply threads.
* **Live STOMP WebSockets** — Real-time message delivery, typing indicators, custom statuses, emoji reactions, unread badges, and `@mention` typeahead over `/ws`.
* **1:1 Voice & Video Calls** — WebRTC calling with short-lived HMAC-signed TURN credentials via `coturn`.
* **LAN Peer-to-Peer File Transfers** — Fast local-network file sharing powered by Fandem alongside raw-streamed file uploads.
* **Markdown, Syntax Highlighting & Mermaid** — Server-rendered CommonMark sanitized with `jsoup`, code highlighting via `highlight.js`, on-demand Mermaid diagrams, and SSRF-safe link previews.
* **Zero-Infrastructure Search** — Embedded Apache Lucene 10 full-text search scoped to channels the user can access.
* **Slash Commands & One-Time Secrets** — Built-in `/poll`, `/remind`, `/help`, and encrypted burn-after-reading secret links.
* **Hardened by Default** — OAuth2/OIDC SSO via Keycloak 26, strict `Content-Security-Policy`, CSRF protection, sliding-window rate limiting, and optional OpenBao/Vault secret resolution.

---

## Stack

| Component | Technology |
| :--- | :--- |
| **Backend** | Java 25 (Virtual Threads), Spring Boot 4.1, Gradle Kotlin DSL |
| **Storage** | PostgreSQL 18, Spring Data JPA, Flyway migrations |
| **Authentication** | Keycloak 26 (OAuth2 / OIDC + JWT bearer API chain) |
| **Realtime & Media** | Native STOMP WebSockets, WebRTC + `coturn` |
| **Search & Files** | Embedded Apache Lucene 10, raw-body streaming uploads, Fandem LAN P2P |
| **Frontend** | Thymeleaf, Vanilla ES Modules, Closure Compiler asset bundling |

---

## Quick Start

### Prerequisites

* **Java 25** (or let Gradle's toolchain download it automatically)
* **Docker** or **Podman** with Compose
* **`jq`**

### 1. Boot Local Services (PostgreSQL, Keycloak, coturn)

```bash
docker compose up -d
# or: podman compose up -d
```

Wait ~20 seconds for Keycloak to import `tforge-realm` (seeded with dev accounts `alice`/`alice` and `bob`/`bob`).

### 2. Launch ThreadForge

```bash
export KEYCLOAK_CLIENT_SECRET=$(jq -r '.clients[] | select(.clientId=="tforge-client") | .secret' keycloak/realm.json)
export TFORGE_TURN_URLS="turn:127.0.0.1:3478?transport=udp"
export TFORGE_TURN_SECRET="dev-turn-secret"

./gradlew bootRun
```

Open **http://localhost:8080** and sign in with `alice`/`alice` (or `bob`/`bob` in a second browser).

### 3. Local Network (LAN + HTTPS for Voice/Video Calls)

Browsers require a secure context (`HTTPS`) for microphone/camera access on non-localhost origins. Use the LAN launcher to detect your Wi-Fi IP, generate a local TLS certificate, configure Keycloak origins, and serve HTTPS on `:8443`:

```bash
./gradlew assemble
./scripts/lan-control.sh start
./scripts/lan-control.sh status
```

---

## Package Structure

```text
src/main/java/com/threadforge/chat/
├── ThreadForgeApplication.java
├── attachments/   Upload quotas, media detection, streaming helpers
├── calls/         1:1 WebRTC signaling, ring state machine, TURN credentials
├── config/        Dual security chains (Web OIDC + Bearer API), STOMP, Vault
├── domain/        JPA entities (User, Channel, Message, Conversation, Poll, etc.)
├── i18n/          Viewer timezone and locale resolution
├── linkpreview/   Bounded link metadata fetcher + SSRF guard
├── moderation/    Storage quotas, suspension registry, admin actions
├── p2p/           LAN peer-to-peer file sharing integration
├── paste/         Rich-text HTML-to-Markdown paste conversion
├── repository/    Spring Data JPA repositories
├── search/        Embedded Apache Lucene indexing & queries
├── secretshare/   One-time encrypted secret links
├── security/      Principal resolution, rate limiting, exception types
├── service/       Core chat, channel, DM, reaction, poll, presence services
├── slash/         Slash command parser and scheduler (/poll, /remind, /help)
└── web/           Thymeleaf pages, REST controllers, STOMP endpoints
```

---

## Building & Testing

```bash
# Compile, bundle static assets, and build JAR
./gradlew assemble

# Run unit tests
./gradlew :test --tests '*Test'

# Run full suite (requires Docker/Podman for Testcontainers integration tests)
./gradlew test
```

---

## License

Licensed under the [Apache License 2.0](LICENSE). See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for bundled third-party licenses.
