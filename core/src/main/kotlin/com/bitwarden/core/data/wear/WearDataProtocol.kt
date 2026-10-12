package com.bitwarden.core.data.wear

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

private const val ROUTE_PREFIX = "/bitwarden/wear/v2"
private const val MAX_NODE_LENGTH = 128
private const val MAX_PUBLIC_KEY_BYTES = 1024
private const val MIN_RSA_BITS = 2048
private const val MAX_RSA_BITS = 4096
private const val SNAPSHOT_CONTEXT = "bitwarden-wear-snapshot-v2:"

/**
 * Data Layer contract shared by the phone and watch apps.
 *
 * The watch publishes its public key at [PUBLIC_KEY_PATH]. The phone publishes one encrypted
 * snapshot per watch at [snapshotPath], replacing it whenever codes or the watch key change and
 * deleting it when sync is turned off or the account logs out.
 */
object WearDataProtocol {
    /** Capability declared by the watch app, used to list watches running it. */
    const val COMPANION_CAPABILITY = "bitwarden_wear_companion_v1"

    /** Watch-owned DataItem whose data is the watch's X.509 RSA public key. */
    const val PUBLIC_KEY_PATH = "$ROUTE_PREFIX/key"

    /** Prefix of every phone-owned snapshot DataItem. */
    const val SNAPSHOT_PATH_PREFIX = "$ROUTE_PREFIX/snapshots/"

    /** Asset field containing the encrypted snapshot envelope. */
    const val SNAPSHOT_ASSET = "snapshot"

    /** Identifier of the watch key the snapshot was encrypted for. */
    const val RECIPIENT_KEY_ID = "recipientKeyId"

    /** Watch node the snapshot is addressed to. */
    const val TARGET_NODE = "target"

    /** Opaque, non-reversible tag of the account that published the snapshot. */
    const val OWNER = "owner"

    /** Opaque tag the phone uses to skip republishing unchanged snapshots. */
    const val CONTENT_TAG = "contentTag"

    /** Upper bound for an encrypted snapshot envelope. */
    const val MAX_SNAPSHOT_BYTES: Int = WearPayloadCipher.MAX_PLAINTEXT_BYTES + 4096

    /** Returns the snapshot path addressed to [watchNodeId]. */
    fun snapshotPath(watchNodeId: String): String {
        require(watchNodeId.length in 1..MAX_NODE_LENGTH && watchNodeId.all { it in '!'..'~' })
        return SNAPSHOT_PATH_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(watchNodeId.toByteArray())
    }

    /** Identifies a recipient key without transmitting the key with every snapshot. */
    fun recipientKeyId(publicKey: ByteArray): String = sha256Hex(publicKey)

    /** Associated data binding a snapshot to the key it was encrypted for. */
    fun snapshotContext(recipientKeyId: String): ByteArray =
        (SNAPSHOT_CONTEXT + recipientKeyId).toByteArray()

    /** Returns the hex SHA-256 digest of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /** Whether [publicKey] is a bounded X.509 RSA key of a supported size. */
    fun isValidPublicKey(publicKey: ByteArray): Boolean = try {
        publicKey.size in 1..MAX_PUBLIC_KEY_BYTES &&
            (KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
                as? RSAPublicKey)
                ?.modulus
                ?.bitLength() in MIN_RSA_BITS..MAX_RSA_BITS
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
