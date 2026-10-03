package com.daniel.tvdeinsight.data.sync.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendRegistrationContractTest {
    @Test
    fun `admin license issue contract preserves exact fields`() {
        val request = ClientLicenseIssueRequest("abcdef1234567890", 1_900_000_000_000L)
        val encoded = BackendHttpClient.json.encodeToString(ClientLicenseIssueRequest.serializer(), request)
        assertTrue(encoded.contains("\"android_id\":\"abcdef1234567890\""))
        assertTrue(encoded.contains("\"license_type\":\"CUSTOM\""))

        val response = BackendHttpClient.json.decodeFromString(
            ClientLicenseIssueResponse.serializer(),
            """{"activation_key":"payload.signature","android_id":"abcdef1234567890","expires_at_epoch_ms":1900000000000,"license_type":"CUSTOM","server_time":1800000000}"""
        )
        assertEquals("payload.signature", response.activationKey)
        assertEquals(1_900_000_000_000L, response.expiresAtEpochMillis)
    }

    @Test
    fun requestJson_matchesOpenApiFieldNames() {
        val request = RegistrationRequest(
            activationKey = "test.activation.valid.client.00000001",
            devicePublicKey = SerializableEcPublicJwk(
                kty = "EC",
                crv = "P-256",
                x = "axfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpY",
                y = "T-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
            ),
            client = BackendClientDescriptor("com.daniel.tvdeinsight", "0.6.0-unified")
        )

        assertEquals(
            "{\"activation_key\":\"test.activation.valid.client.00000001\"," +
                "\"device_public_key\":{\"kty\":\"EC\",\"crv\":\"P-256\"," +
                "\"x\":\"axfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpY\"," +
                "\"y\":\"T-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU\"}," +
                "\"client\":{\"app_id\":\"com.daniel.tvdeinsight\"," +
                "\"app_version\":\"0.6.0-unified\",\"api_version\":1}}",
            BackendHttpClient.json.encodeToString(RegistrationRequest.serializer(), request)
        )
    }

    @Test
    fun permanentRegistrationErrors_haveExplicitStates() {
        assertEquals(
            BackendRegistrationStatus.ALREADY_BOUND,
            RegistrationFailurePolicy.permanentStatus("LICENSE_ALREADY_BOUND")
        )
        assertEquals(
            BackendRegistrationStatus.REVOKED,
            RegistrationFailurePolicy.permanentStatus("LICENSE_REVOKED")
        )
        assertEquals(
            BackendRegistrationStatus.CLOCK_ERROR,
            RegistrationFailurePolicy.permanentStatus("TIMESTAMP_OUT_OF_RANGE")
        )
        assertEquals(
            BackendRegistrationStatus.SIGNATURE_INVALID,
            RegistrationFailurePolicy.permanentStatus("INVALID_SIGNATURE")
        )
    }
}
