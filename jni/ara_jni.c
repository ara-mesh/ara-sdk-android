/*
 * ara_jni.c — JNI bridge between Kotlin/Java and the Go engine.
 *
 * This file is compiled by the Android NDK (see CMakeLists.txt). It links
 * against libaraengine.so and translates JNI types (jstring, jlong, etc.)
 * into plain C types expected by the Go exports.
 *
 * Kotlin class: com.aramesh.sdk.v1.Engine
 * Package JNI prefix: Java_com_aramesh_sdk_v1_Ara_
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "libaraengine.h"

#define ARA_TAG  "AraJNI"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, ARA_TAG, __VA_ARGS__)

/* ── helpers ──────────────────────────────────────────────────────────────── */

static const char* jstr(JNIEnv* env, jstring s) {
    if (s == NULL) return "";
    return (*env)->GetStringUTFChars(env, s, NULL);
}

static void jstr_release(JNIEnv* env, jstring s, const char* c) {
    if (s != NULL) (*env)->ReleaseStringUTFChars(env, s, c);
}

static jstring to_jstring(JNIEnv* env, const char* c) {
    if (c == NULL) return NULL;
    jstring s = (*env)->NewStringUTF(env, c);
    AraFree((char*)c);  /* Go-allocated; must be freed via AraFree */
    return s;
}

/* ── Engine JNI methods ───────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeOpen(
    JNIEnv* env, jclass cls,
    jstring path, jstring crsqlitePath, jstring migrationsJson,
    jstring networkId, jint encryption, jstring licenseKey)
{
    const char* p  = jstr(env, path);
    const char* cr = jstr(env, crsqlitePath);
    const char* mj = jstr(env, migrationsJson);
    const char* ni = jstr(env, networkId);
    const char* lk = jstr(env, licenseKey);
    jlong h = (jlong)AraOpen((char*)p, (char*)cr, (char*)mj, (char*)ni, (int)encryption, (char*)lk);
    if (h < 0) {
        ALOGE("AraOpen failed: path=%s crsqlite=%s networkId=%s", p, cr, ni);
    }
    jstr_release(env, path, p);
    jstr_release(env, crsqlitePath, cr);
    jstr_release(env, migrationsJson, mj);
    jstr_release(env, networkId, ni);
    jstr_release(env, licenseKey, lk);
    return h;
}

JNIEXPORT void JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeClose(JNIEnv* env, jclass cls, jlong h)
{
    AraClose((long long)h);
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeExec(
    JNIEnv* env, jclass cls, jlong h, jstring sql, jstring argsJson)
{
    const char* s = jstr(env, sql);
    const char* a = jstr(env, argsJson);
    char* err = AraExec((long long)h, (char*)s, (char*)a);
    jstr_release(env, sql, s);
    jstr_release(env, argsJson, a);
    if (err == NULL) return NULL;
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeQuery(
    JNIEnv* env, jclass cls, jlong h, jstring sql, jstring argsJson)
{
    const char* s = jstr(env, sql);
    const char* a = jstr(env, argsJson);
    char* result = AraQuery((long long)h, (char*)s, (char*)a);
    jstr_release(env, sql, s);
    jstr_release(env, argsJson, a);
    return to_jstring(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeQueryRow(
    JNIEnv* env, jclass cls, jlong h, jstring sql, jstring argsJson)
{
    const char* s = jstr(env, sql);
    const char* a = jstr(env, argsJson);
    char* result = AraQueryRow((long long)h, (char*)s, (char*)a);
    jstr_release(env, sql, s);
    jstr_release(env, argsJson, a);
    return to_jstring(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeExecTraced(
    JNIEnv* env, jclass cls, jlong h, jstring traceparent, jstring sql, jstring argsJson)
{
    const char* tp = jstr(env, traceparent);
    const char* s  = jstr(env, sql);
    const char* a  = jstr(env, argsJson);
    char* err = AraExecTraced((long long)h, (char*)tp, (char*)s, (char*)a);
    jstr_release(env, traceparent, tp);
    jstr_release(env, sql, s);
    jstr_release(env, argsJson, a);
    if (err == NULL) return NULL;
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeSyncTraced(
    JNIEnv* env, jclass cls, jlong h, jstring traceparent)
{
    const char* tp = jstr(env, traceparent);
    char* err = AraSyncTraced((long long)h, (char*)tp);
    jstr_release(env, traceparent, tp);
    if (err == NULL) return NULL;
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeSync(JNIEnv* env, jclass cls, jlong h)
{
    char* err = AraSync((long long)h);
    if (err == NULL) return NULL;
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeAddTransportMQTT(
    JNIEnv* env, jclass cls, jlong h, jstring configJson)
{
    const char* c = jstr(env, configJson);
    ALOGE("nativeAddTransportMQTT config=%s", c);
    char* err = AraAddTransportMQTT((long long)h, (char*)c);
    jstr_release(env, configJson, c);
    if (err == NULL) return NULL;
    ALOGE("nativeAddTransportMQTT error: %s", err);
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeAddTransportUDP(
    JNIEnv* env, jclass cls, jlong h, jint port)
{
    char* err = AraAddTransportUDP((long long)h, (int)port);
    if (err == NULL) return NULL;
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeAddTransportMeshtastic(
    JNIEnv* env, jclass cls, jlong h, jstring portPath, jint channel)
{
    const char* portPathStr = (*env)->GetStringUTFChars(env, portPath, NULL);
    if (portPathStr == NULL) return NULL;
    char* err = AraAddTransportMeshtastic((long long)h, (char*)portPathStr, (int)channel);
    (*env)->ReleaseStringUTFChars(env, portPath, portPathStr);
    if (err == NULL) return NULL;
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeNodeID(JNIEnv* env, jclass cls, jlong h)
{
    return to_jstring(env, AraNodeID((long long)h));
}

JNIEXPORT jint JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeSchemaVersion(JNIEnv* env, jclass cls, jlong h)
{
    return (jint)AraSchemaVersion((long long)h);
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativePeers(JNIEnv* env, jclass cls, jlong h)
{
    return to_jstring(env, AraPeers((long long)h));
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativePeerGraph(JNIEnv* env, jclass cls, jlong h)
{
    return to_jstring(env, AraPeerGraph((long long)h));
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeSetBlobDir(
    JNIEnv* env, jclass cls, jlong h, jstring dir, jint mode, jlong maxBytes, jlong maxBlobSize)
{
    const char* d = jstr(env, dir);
    char* err = AraSetBlobDir((long long)h, (char*)d, (int)mode, (long long)maxBytes, (long long)maxBlobSize);
    jstr_release(env, dir, d);
    if (err == NULL) return NULL;
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeBlobIngest(
    JNIEnv* env, jclass cls, jlong h, jstring path, jstring mimeType)
{
    const char* p = jstr(env, path);
    const char* m = jstr(env, mimeType);
    char* result = AraBlobIngest((long long)h, (char*)p, (char*)m);
    jstr_release(env, path, p);
    jstr_release(env, mimeType, m);
    return to_jstring(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeBlobPath(
    JNIEnv* env, jclass cls, jlong h, jstring id)
{
    const char* i = jstr(env, id);
    char* result = AraBlobPath((long long)h, (char*)i);
    jstr_release(env, id, i);
    return to_jstring(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeInitOTLP(
    JNIEnv* env, jclass cls, jlong h, jstring addr, jstring serviceName)
{
    const char* a = jstr(env, addr);
    const char* s = jstr(env, serviceName);
    ALOGE("nativeInitOTLP addr=%s service=%s", a, s);
    char* err = AraInitOTLP((long long)h, (char*)a, (char*)s);
    jstr_release(env, addr, a);
    jstr_release(env, serviceName, s);
    if (err == NULL) return NULL;
    ALOGE("nativeInitOTLP error: %s", err);
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativePublicKey(JNIEnv* env, jclass cls, jlong h)
{
    return to_jstring(env, AraPublicKey((long long)h));
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeAllowPeer(
    JNIEnv* env, jclass cls, jlong h, jstring pubkeyHex, jstring label)
{
    const char* pk = jstr(env, pubkeyHex);
    const char* lb = jstr(env, label);
    char* err = AraAllowPeer((long long)h, (char*)pk, (char*)lb);
    jstr_release(env, pubkeyHex, pk);
    jstr_release(env, label, lb);
    if (err == NULL) return NULL;
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}

JNIEXPORT jstring JNICALL
Java_com_aramesh_sdk_v1_Ara_nativeRevokePeer(
    JNIEnv* env, jclass cls, jlong h, jstring pubkeyHex)
{
    const char* pk = jstr(env, pubkeyHex);
    char* err = AraRevokePeer((long long)h, (char*)pk);
    jstr_release(env, pubkeyHex, pk);
    if (err == NULL) return NULL;
    jstring jerr = (*env)->NewStringUTF(env, err);
    AraFree(err);
    return jerr;
}
