package com.daniel.tvdeinsight.data.sync.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendRegistrationContractTest {
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
            client = BackendClientDescriptor("com.daniel.tvdeinsight", "0.5.69-unified")
        )

        assertEquals(
            "{\"activation_key\":\"test.activation.valid.client.00000001\"," +
                "\"device_public_key\":{\"kty\":\"EC\",\"crv\":\"P-256\"," +
                "\"x\":\"axfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpY\"," +
                "\"y\":\"T-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU\"}," +
                "\"client\":{\"app_id\":\"com.daniel.tvdeinsight\"," +
                "\"app_version\":\"0.5.69-unified\",\"api_version\":1}}",
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
