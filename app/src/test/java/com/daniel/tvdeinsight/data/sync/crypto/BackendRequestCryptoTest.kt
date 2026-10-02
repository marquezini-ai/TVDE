package com.daniel.tvdeinsight.data.sync.crypto

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendRequestCryptoTest {
    @Test
    fun canonicalRequest_matchesBackendContractExactly() {
        val canonical = BackendRequestCrypto.canonicalRequest(
            method = "post",
            path = "/v1/sync",
            principal = "installation-id",
            timestamp = 1_800_000_000,
            nonce = "nonce",
            idempotencyKey = "request-id",
            bodyHash = "body-hash"
        )

        assertArrayEquals(
            "TVDE1\nPOST\n/v1/sync\ninstallation-id\n1800000000\nnonce\nrequest-id\nbody-hash\n"
                .toByteArray(StandardCharsets.UTF_8),
            canonical
        )
    }

    @Test
    fun bodyHash_usesUnpaddedBase64Url() {
        assertEquals(
            "LPJNul-wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ",
            BackendRequestCrypto.bodySha256("hello".toByteArray())
        )
    }

    @Test
    fun jwkThumbprint_matchesRfc7638BackendVector() {
        val jwk = EcPublicJwk(
            x = "axfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpY",
            y = "T-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
        )

        assertEquals(
            "xx0BcA-wMohw8atYDJOe6peGModklG2wRHBlXHMvl0M",
            BackendRequestCrypto.jwkThumbprint(jwk)
        )
    }

    @Test
    fun publicJwk_thumbprintAndSignature_areValid() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val pair = generator.generateKeyPair()
        val jwk = BackendRequestCrypto.publicJwk(pair.public)
        val thumbprint = BackendRequestCrypto.jwkThumbprint(jwk)
        val canonical = BackendRequestCrypto.canonicalRequest(
            "POST", "/v1/installations/register", thumbprint, 1_800_000_000,
            "nonce", "request-id", BackendRequestCrypto.bodySha256("{}".toByteArray())
        )
        val encodedSignature = BackendRequestCrypto.sign(pair.private, canonical)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(canonical)
        }

        assertEquals("EC", jwk.kty)
        assertEquals("P-256", jwk.crv)
        assertEquals(43, jwk.x.length)
        assertEquals(43, jwk.y.length)
        assertEquals(43, thumbprint.length)
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(encodedSignature)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun canonicalRequest_rejectsQueryParameters() {
        BackendRequestCrypto.canonicalRequest(
            "POST", "/v1/sync?cursor=x", "principal", 1, "nonce", "request", "hash"
        )
    }
}
