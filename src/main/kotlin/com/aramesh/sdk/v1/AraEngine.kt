package com.aramesh.sdk.v1

import android.content.Context
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context as OtelContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Node wraps an open Ara sync node.
 *
 * Obtain one via [Ara.open]. All SQL operations are forwarded to the Go
 * engine over JNI. Close the node when done:
 *
 * ```kotlin
 * val node = Ara.open(context, path, migrations)
 * node.exec("INSERT INTO items (id, name) VALUES (?, ?)", listOf("a1", "Map"))
 * val rows = node.query("SELECT * FROM items")
 * node.close()
 * ```
 */
class Node internal constructor(
    private val handle: Long,
    val nodeId: String,
) : AutoCloseable {

    /** Execute a write statement. Throws [AraException] on failure. */
    fun exec(sql: String, args: List<Any?> = emptyList()) {
        val tp = currentTraceparent()
        val err = if (tp.isNotEmpty())
            Ara.nativeExecTraced(handle, tp, sql, args.toJsonArray())
        else
            Ara.nativeExec(handle, sql, args.toJsonArray())
        if (err != null) throw AraException(err)
    }

    /**
     * Execute a read query. Returns a list of rows, each row a map of
     * column name → value (String, Long, Double, or null).
     */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Map<String, Any?>> {
        val json = Ara.nativeQuery(handle, sql, args.toJsonArray())
            ?: return emptyList()
        return parseRows(json)
    }

    /**
     * Execute a query expected to return at most one row.
     * Returns null if no rows were returned.
     */
    fun queryRow(sql: String, args: List<Any?> = emptyList()): Map<String, Any?>? {
        val json = Ara.nativeQueryRow(handle, sql, args.toJsonArray())
            ?: return null
        if (json == "null") return null
        return parseRow(JSONObject(json))
    }

    /** Trigger an immediate sync broadcast to all peers. */
    fun sync() {
        val tp = currentTraceparent()
        val err = if (tp.isNotEmpty())
            Ara.nativeSyncTraced(handle, tp)
        else
            Ara.nativeSync(handle)
        if (err != null) throw AraException(err)
    }

    /**
     * Add an MQTT transport. Call before the engine is running (or it will
     * take effect on the next sync tick).
     *
     * @param brokerUrl  e.g. "tcp://192.168.1.100:1883"
     * @param networkId  shared network identifier for all nodes in the mesh
     */
    fun addTransportMQTT(brokerUrl: String, networkId: String) {
        val cfg = JSONObject().apply {
            put("broker_url", brokerUrl)
            put("network_id", networkId)
        }.toString()
        val err = Ara.nativeAddTransportMQTT(handle, cfg)
        if (err != null) throw AraException(err)
    }

    /**
     * Add a UDP LAN transport. Syncs with other Ara nodes on the same subnet
     * without requiring an MQTT broker. Uses LAN broadcast for peer discovery.
     *
     * @param port  UDP port to bind (default 7946)
     */
    fun addTransportUDP(port: Int = 7946) {
        val err = Ara.nativeAddTransportUDP(handle, port)
        if (err != null) throw AraException(err)
    }

    /**
     * Add a Meshtastic LoRa transport via USB serial.
     * Requires a USB OTG cable and a Meshtastic radio connected via serial.
     *
     * @param portPath Serial device path (e.g. "/dev/ttyUSB0" or "/dev/ttyACM0")
     * @param channel Meshtastic channel index (0-7, default 0)
     */
    fun addTransportMeshtastic(portPath: String, channel: Int = 0) {
        val err = Ara.nativeAddTransportMeshtastic(handle, portPath, channel)
        if (err != null) throw AraException(err)
    }

    /** Return all known peers with health state derived from gossip. */
    fun peers(): List<PeerInfo> {
        val json = Ara.nativePeers(handle) ?: return emptyList()
        if (json.startsWith("{\"error\"")) throw AraException(json)
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            PeerInfo(
                id = obj.optString("ID"),
                schemaVersion = obj.optInt("SchemaVersion"),
                health = obj.optString("Health"),
                transports = obj.optJSONArray("Transports")
                    ?.let { t -> (0 until t.length()).map { t.getString(it) } }
                    ?: emptyList(),
            )
        }
    }

    /** Return the mesh topology graph with via-peer edge metadata. */
    fun peerGraph(): GraphData {
        val json = Ara.nativePeerGraph(handle) ?: return GraphData()
        if (json.startsWith("{\"error\"")) throw AraException(json)
        val obj = JSONObject(json)
        val nodesArr = obj.optJSONArray("nodes") ?: JSONArray()
        val edgesArr = obj.optJSONArray("edges") ?: JSONArray()
        val nodes = (0 until nodesArr.length()).map { i ->
            val n = nodesArr.getJSONObject(i)
            GraphNode(id = n.optString("id"), health = n.optString("health"), self = n.optBoolean("self"))
        }
        val edges = (0 until edgesArr.length()).map { i ->
            val e = edgesArr.getJSONObject(i)
            GraphEdge(source = e.optString("source"), target = e.optString("target"), direct = e.optBoolean("direct"))
        }
        return GraphData(nodes, edges)
    }

    /**
     * Initialise OpenTelemetry exporting to [otlpAddr] (e.g. "192.168.1.100:4317").
     * Safe to call after [Ara.open]. Replaces any previous OTel provider.
     * Throws [Exception] on failure.
     */
    fun initOTLP(otlpAddr: String, serviceName: String = "ara-android") {
        val err = Ara.nativeInitOTLP(handle, otlpAddr, serviceName)
        if (err != null) throw AraException(err)
    }

    // ── Blob API ───────────────────────────────────────────────────────────────

    /**
     * Configure the blob store directory and automatic sync policy.
     * Call before [Ara.open] starts the run loop.
     *
     * @param dir     Directory path for blob storage (created if absent).
     * @param policy  Sync mode and storage caps (see [BlobPolicy]).
     */
    fun setBlobStore(dir: String, policy: BlobPolicy = BlobPolicy()) {
        val err = Ara.nativeSetBlobDir(handle, dir, policy.mode.code, policy.maxBytes, policy.maxBlobSize)
        if (err != null) throw AraException(err)
    }

    /**
     * Copy a local file into the blob store, record its metadata in ara_blobs,
     * and mark it locally available. Returns the SHA-256 id.
     * The returned id can be referenced in app tables (e.g. messages.attachment_id).
     */
    fun ingestBlob(path: String, mimeType: String = "application/octet-stream"): String {
        val result = Ara.nativeBlobIngest(handle, path, mimeType)
            ?: throw AraException("ingestBlob returned null")
        if (result.startsWith("{\"error\"")) throw AraException(result)
        return result
    }

    /**
     * Return the local filesystem path of a stored blob, or "" if not present.
     * Use this to open the file for display after confirming it has been fetched.
     */
    fun blobPath(id: String): String =
        Ara.nativeBlobPath(handle, id) ?: ""

    // ── Encryption API ─────────────────────────────────────────────────────────

    /**
     * Returns this node's X25519 public key as a 64-character hex string.
     * Empty string if encryption was not enabled at open time.
     * Share this with operators of other nodes so they can call [allowPeer].
     */
    fun publicKey(): String = Ara.nativePublicKey(handle) ?: ""

    /**
     * Add a peer's public key to the CRDT-synced allowlist.
     * The entry propagates automatically to all trusted peers via sync.
     *
     * @param pubkeyHex  64-char hex string from the peer's [publicKey] call
     * @param label      Human-readable name for the peer (e.g. "Team 3 tablet")
     */
    fun allowPeer(pubkeyHex: String, label: String) {
        val err = Ara.nativeAllowPeer(handle, pubkeyHex, label)
        if (err != null) throw AraException(err)
    }

    /**
     * Revoke a peer's public key in the CRDT-synced allowlist.
     * The revocation propagates automatically to all trusted peers via sync.
     * After propagation, the revoked node's messages are silently dropped.
     *
     * @param pubkeyHex  64-char hex string from the peer's [publicKey] call
     */
    fun revokePeer(pubkeyHex: String) {
        val err = Ara.nativeRevokePeer(handle, pubkeyHex)
        if (err != null) throw AraException(err)
    }

    /** The highest applied migration version (0 if none). */
    val schemaVersion: Int get() = Ara.nativeSchemaVersion(handle)

    override fun close() {
        Ara.nativeClose(handle)
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun currentTraceparent(): String {
        val carrier = mutableMapOf<String, String>()
        W3CTraceContextPropagator.getInstance()
            .inject(OtelContext.current(), carrier) { m, k, v -> m?.set(k, v) }
        return carrier["traceparent"] ?: ""
    }

    private fun List<Any?>.toJsonArray(): String = JSONArray(this).toString()

    private fun parseRows(json: String): List<Map<String, Any?>> {
        if (json.startsWith("{\"error\"")) throw AraException(json)
        val arr = JSONArray(json)
        return (0 until arr.length()).map { parseRow(arr.getJSONObject(it)) }
    }

    private fun parseRow(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { key ->
            when (val v = obj.get(key)) {
                JSONObject.NULL -> null
                else -> v
            }
        }
}

/** Thrown when an Ara JNI call returns an error string. */
class AraException(message: String) : kotlin.Exception(message)

/** Peer health information as returned by [Node.peers]. */
data class PeerInfo(
    val id: String,
    val schemaVersion: Int,
    val health: String,
    val transports: List<String>,
)

/** Whether a node automatically fetches blob bytes from peers. */
enum class BlobSyncMode(val code: Int) {
    /** Metadata only; never pull bytes (default). */
    None(0),
    /** Pull thumbnails only (≤ 2 KB). */
    ThumbOnly(1),
    /** Pull full blobs when the transport allows. */
    Full(2),
}

/**
 * Blob store sync policy. Passed to [Node.setBlobStore].
 *
 * @param mode        Whether and how far to auto-replicate blob bytes.
 * @param maxBytes    Total storage cap in bytes; 0 = unlimited.
 * @param maxBlobSize Skip individual blobs larger than this; 0 = unlimited.
 */
data class BlobPolicy(
    val mode: BlobSyncMode = BlobSyncMode.None,
    val maxBytes: Long = 0L,
    val maxBlobSize: Long = 0L,
)

/** A node in the mesh topology graph. */
data class GraphNode(val id: String, val health: String, val self: Boolean)

/** A directed edge in the mesh topology graph. Direct edges were heard from the source directly. */
data class GraphEdge(val source: String, val target: String, val direct: Boolean)

/** Mesh topology returned by [Node.peerGraph]. */
data class GraphData(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
)

/** A single schema migration. SQL is additive-only. */
data class Migration(
    val version: Int,
    val description: String,
    val sql: String = "",
    /** Tables to register as CRDT-synced after the SQL runs. Use when creating new tables. */
    val sync: List<String> = emptyList(),
    /** Table whose cr-sqlite triggers must be rebuilt after the SQL runs. Use when adding columns. */
    val alterSync: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("description", description)
        if (sql.isNotEmpty()) put("sql", sql)
        if (sync.isNotEmpty()) put("sync", JSONArray(sync))
        if (alterSync.isNotEmpty()) put("alter_sync", alterSync)
    }
}

/**
 * Factory for opening Ara sync nodes. The native libraries are loaded
 * automatically on first call.
 *
 * Call [open] once per database file. The returned [Node] must be closed
 * when no longer needed.
 */
object Ara {

    init {
        // crsqlite must be loaded before araengine so dlopen sees its symbols
        System.loadLibrary("crsqlite")
        // ara_jni wraps libaraengine.so (Go engine) + the JNI bridge
        System.loadLibrary("ara_jni")
    }

    /**
     * Open (or create) an Ara sync node backed by a SQLite database at [path].
     *
     * @param context     Android context — used to locate bundled native libs
     * @param path        Filesystem path to the SQLite file (use absolute path)
     * @param migrations  Ordered list of schema migrations
     * @param networkId   Logical mesh identifier — only nodes with the same ID sync
     * @param encryption  Enable X25519 keypairs and AES-256-GCM message encryption
     * @param licenseKey  Ed25519-signed key from Ara; empty = 10-node evaluation limit
     * @param syncIntervalSeconds  Periodic handshake interval; default 30s. Widen for
     *                    LoRa nodes to stay within the duty-cycle budget.
     */
    fun open(
        context: Context,
        path: String,
        migrations: List<Migration>,
        networkId: String = "",
        encryption: Boolean = false,
        licenseKey: String = "",
        syncIntervalSeconds: Int = 30,
    ): Node {
        val crsqlitePath =
            "${context.applicationInfo.nativeLibraryDir}/libcrsqlite.so"
        val migrationsJson = JSONArray(migrations.map { it.toJson() }).toString()
        val handle = nativeOpen(path, crsqlitePath, migrationsJson, networkId, if (encryption) 1 else 0, licenseKey)
        if (handle < 0L) throw AraException("Ara.open failed — check logs")
        if (syncIntervalSeconds > 0) nativeSetSyncInterval(handle, syncIntervalSeconds)
        val nodeId = nativeNodeID(handle) ?: ""
        return Node(handle, nodeId)
    }

    // ── JNI declarations ───────────────────────────────────────────────────
    // These map to Java_com_aramesh_sdk_v1_Ara_native* in ara_jni.c

    @JvmStatic external fun nativeOpen(path: String, crsqlitePath: String, migrationsJson: String, networkId: String, encryption: Int, licenseKey: String): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeExec(handle: Long, sql: String, argsJson: String): String?
    @JvmStatic external fun nativeExecTraced(handle: Long, traceparent: String, sql: String, argsJson: String): String?
    @JvmStatic external fun nativeQuery(handle: Long, sql: String, argsJson: String): String?
    @JvmStatic external fun nativeQueryRow(handle: Long, sql: String, argsJson: String): String?
    @JvmStatic external fun nativeSync(handle: Long): String?
    @JvmStatic external fun nativeSyncTraced(handle: Long, traceparent: String): String?
    @JvmStatic external fun nativeAddTransportMQTT(handle: Long, configJson: String): String?
    @JvmStatic external fun nativeAddTransportUDP(handle: Long, port: Int): String?
    @JvmStatic external fun nativeAddTransportMeshtastic(handle: Long, portPath: String, channel: Int): String?
    @JvmStatic external fun nativeNodeID(handle: Long): String?
    @JvmStatic external fun nativeSchemaVersion(handle: Long): Int
    @JvmStatic external fun nativeSetSyncInterval(handle: Long, seconds: Int)
    @JvmStatic external fun nativePeers(handle: Long): String?
    @JvmStatic external fun nativePeerGraph(handle: Long): String?
    @JvmStatic external fun nativeInitOTLP(handle: Long, otlpAddr: String, serviceName: String): String?
    @JvmStatic external fun nativeSetBlobDir(handle: Long, dir: String, mode: Int, maxBytes: Long, maxBlobSize: Long): String?
    @JvmStatic external fun nativeBlobIngest(handle: Long, path: String, mimeType: String): String?
    @JvmStatic external fun nativeBlobPath(handle: Long, id: String): String?
    @JvmStatic external fun nativePublicKey(handle: Long): String?
    @JvmStatic external fun nativeAllowPeer(handle: Long, pubkeyHex: String, label: String): String?
    @JvmStatic external fun nativeRevokePeer(handle: Long, pubkeyHex: String): String?
}
