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

// Add a transport
node.addTransportMQTT("tcp://192.168.1.1:1883", "my-mesh")

// Write
node.exec("INSERT INTO messages (id, content, created_at) VALUES (?, ?, ?)",
    listOf("msg-1", "Hello mesh", System.currentTimeMillis()))

// Read
val rows = node.query("SELECT id, content FROM messages")
rows.forEach { row -> Log.d("Ara", "${row["id"]}: ${row["content"]}") }

// Sync immediately
node.sync()
```

## Transports

| Transport | Method | Use case |
|-----------|--------|----------|
| UDP LAN | `addTransportUDP(port)` | Local network / WiFi |
| MQTT | `addTransportMQTT(brokerUrl, networkId)` | WiFi or cellular via broker |
| Meshtastic | `addTransportMeshtastic(portPath, channel)` | LoRa off-grid (USB OTG serial) |

## Documentation

Full API reference and guides: [ara-mesh.github.io/ara-docs](https://ara-mesh.github.io/ara-docs)

## License

Proprietary — All Rights Reserved. See [LICENSE](LICENSE).
