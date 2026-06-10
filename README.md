# ara-sdk-android

Android SDK for [Ara](https://github.com/ara-mesh/ara) — a delay-tolerant, offline-first mesh sync library for applications that need shared state without central infrastructure.

## Install

Add the GitHub Packages Maven repository and dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/ara-mesh/ara-sdk-android")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.aramesh:ara-sdk:v1")
}
```

**Supported ABI:** `arm64-v8a` (Android 10+, `minSdk 29`)

## Quick start

```kotlin
import com.aramesh.sdk.v1.Ara
import com.aramesh.sdk.v1.Migration

val migrations = listOf(
    Migration(
        version = 1,
        description = "messages table",
        sql = """
            CREATE TABLE IF NOT EXISTS messages (
                id         TEXT PRIMARY KEY,
                content    TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT 0
            ) STRICT;
        """,
        sync = listOf("messages"),
    )
)

val node = Ara.open(context, filesDir.absolutePath + "/ara.db", migrations)

node.addTransportMQTT("tcp://192.168.1.1:1883", "my-mesh")

node.exec("INSERT INTO messages (id, content, created_at) VALUES (?, ?, ?)",
    listOf("msg-1", "Hello mesh", System.currentTimeMillis()))

val rows = node.query("SELECT id, content FROM messages")
rows.forEach { row -> Log.d("Ara", "${row["id"]}: ${row["content"]}") }

node.sync()
```

## Transports

| Transport | Method | Use case |
|-----------|--------|----------|
| UDP LAN | `addTransportUDP(port)` | Local network / WiFi |
| MQTT | `addTransportMQTT(brokerUrl, networkId)` | WiFi or cellular via broker |
| Meshtastic | `addTransportMeshtastic(portPath, channel)` | LoRa off-grid (USB OTG serial) |

## Config

```kotlin
Ara.open(
    context     = context,
    path        = filesDir.absolutePath + "/ara.db",
    migrations  = migrations,
    networkId   = "hawkesbay-sar",  // scope to a logical mesh; default ""
    encryption  = true,             // X25519 + AES-256-GCM; default false
    licenseKey  = "...",            // empty = 10-node evaluation limit
    syncIntervalSeconds = 60,       // widen on LoRa to preserve duty cycle; default 30
)
```

## Encryption

When `encryption = true`, each node generates an X25519 keypair. Nodes must be mutually allowlisted before they will exchange data.

```kotlin
// Print this node's public key — share it with operators of peer nodes
Log.d("Ara", node.publicKey())

// Add a trusted peer (propagates to all nodes via CRDT sync)
node.allowPeer("<64-char hex key>", "Team 3 tablet")

// Revoke a peer (propagates to all nodes via CRDT sync)
node.revokePeer("<64-char hex key>")
```

## Blobs

Ara can sync binary attachments (photos, files) alongside CRDT data. Blob metadata syncs immediately; bytes are delivered asynchronously based on the sync policy.

```kotlin
// Configure blob storage (call before nodes start exchanging data)
node.setBlobStore(
    dir    = filesDir.absolutePath + "/blobs",
    policy = BlobPolicy(
        mode        = BlobSyncMode.ThumbOnly,  // None / ThumbOnly / Full
        maxBytes    = 500L * 1024 * 1024,      // 500 MB cap; 0 = unlimited
        maxBlobSize = 10L  * 1024 * 1024,      // skip blobs > 10 MB; 0 = unlimited
    )
)

// Ingest a local file — returns its SHA-256 content ID
val id = node.ingestBlob("/sdcard/DCIM/photo.jpg", "image/jpeg")

// Retrieve the local path once available (empty string if not yet fetched)
val path = node.blobPath(id)
```

## Mesh topology

```kotlin
// All known peers with health state
val peers = node.peers()

// Full topology graph (nodes + directed edges)
val graph = node.peerGraph()
graph.nodes.forEach { n -> Log.d("Ara", "${n.id} health=${n.health} self=${n.self}") }
graph.edges.forEach { e -> Log.d("Ara", "${e.source} → ${e.target} direct=${e.direct}") }
```

## OpenTelemetry

```kotlin
// Set at open time via Ara.open, or re-point at runtime:
node.initOTLP("192.168.1.1:4317", "my-app")
```

## Schema migrations

Migrations are additive-only. Use `sync` when creating a new table, `alterSync` when adding columns to an existing synced table.

```kotlin
listOf(
    Migration(
        version     = 1,
        description = "create items table",
        sql         = "CREATE TABLE items (id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '') STRICT;",
        sync        = listOf("items"),
    ),
    Migration(
        version     = 2,
        description = "add priority column",
        sql         = "ALTER TABLE items ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;",
        alterSync   = "items",
    ),
)
```

## Documentation

Full API reference and guides: [ara-mesh.github.io/ara-docs](https://ara-mesh.github.io/ara-docs)

## License

Proprietary — All Rights Reserved. See [LICENSE](LICENSE).
