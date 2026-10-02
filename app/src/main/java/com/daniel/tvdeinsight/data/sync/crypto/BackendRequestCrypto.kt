package com.daniel.tvdeinsight.data.sync.crypto

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64

data class EcPublicJwk(
    val kty: String = "EC",
    val crv: String = "P-256",
    val x: String,
    val y: String
)

data class SignedRequestHeaders(
    val timestamp: Long,
    val nonce: String,
    val idempotencyKey: String,
    val bodySha256: String,
    val signature: String
)

object BackendRequestCrypto {
    private const val CANONICAL_PREFIX = "TVDE1"

    fun bodySha256(body: ByteArray): String = sha256Base64Url(body)

    fun publicJwk(publicKey: PublicKey): EcPublicJwk {
        val ecKey = publicKey as? ECPublicKey
            ?: throw IllegalArgumentException("Expected an EC public key")
        require(ecKey.params.curve.field.fieldSize == 256) { "Expected a P-256 public key" }
        return EcPublicJwk(
            x = base64Url(unsignedFixed(ecKey.w.affineX, 32)),
            y = base64Url(unsignedFixed(ecKey.w.affineY, 32))
        )
    }

    fun jwkThumbprint(jwk: EcPublicJwk): String {
        require(jwk.kty == "EC" && jwk.crv == "P-256") { "Unsupported JWK" }
        val canonical = "{\"crv\":\"${jwk.crv}\",\"kty\":\"${jwk.kty}\",\"x\":\"${jwk.x}\",\"y\":\"${jwk.y}\"}"
        return sha256Base64Url(canonical.toByteArray(StandardCharsets.UTF_8))
    }

    fun canonicalRequest(
        method: String,
        path: String,
        principal: String,
        timestamp: Long,
        nonce: String,
        idempotencyKey: String,
        bodyHash: String
    ): ByteArray {
        require(path.startsWith('/') && '?' !in path && '#' !in path) {
            "Path must be absolute and contain no query or fragment"
        }
        val values = listOf(
            CANONICAL_PREFIX,
            method.uppercase(),
            path,
            principal,
            timestamp.toString(),
            nonce,
            idempotencyKey,
            bodyHash
        )
        require(values.none { '\n' in it || '\r' in it }) { "Canonical fields contain a line break" }
        return (values.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    fun sign(privateKey: PrivateKey, canonicalRequest: ByteArray): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(canonicalRequest)
        return base64Url(signer.sign())
    }

    fun signedHeaders(
        privateKey: PrivateKey,
        method: String,
        path: String,
        principal: String,
        body: ByteArray,
        timestamp: Long,
        nonce: String,
        idempotencyKey: String
    ): SignedRequestHeaders {
        val digest = bodySha256(body)
        val canonical = canonicalRequest(
            method = method,
            path = path,
            principal = principal,
            timestamp = timestamp,
            nonce = nonce,
            idempotencyKey = idempotencyKey,
            bodyHash = digest
        )
        return SignedRequestHeaders(
            timestamp = timestamp,
            nonce = nonce,
            idempotencyKey = idempotencyKey,
            bodySha256 = digest,
            signature = sign(privateKey, canonical)
        )
    }

    private fun sha256Base64Url(value: ByteArray): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(value))

    private fun base64Url(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun unsignedFixed(value: BigInteger, size: Int): ByteArray {
        val encoded = value.toByteArray()
        val unsigned = if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
        require(unsigned.size <= size) { "Coordinate is too large" }
        return ByteArray(size).also { output ->
            unsigned.copyInto(output, destinationOffset = size - unsigned.size)
        }
    }
}
