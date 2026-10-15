package com.voiceproducer.core.data.remote

import com.voiceproducer.core.domain.agent.AgentRefused
import com.voiceproducer.core.model.ProxyError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProxyAgentTokenSourceTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun source(
        status: HttpStatusCode,
        body: String,
    ) = ProxyAgentTokenSource(
        ProxyApi(
            HttpClient(
                MockEngine {
                    respond(body, status, jsonHeaders)
                },
            ),
            "https://p.example",
            FixedCredentials(null),
        ),
    )

    @Test
    fun anExhaustedQuotaIsTheRefusal() =
        runTest {
            val refusal =
                source(HttpStatusCode.PaymentRequired, """{"error":"quota_exceeded","status":402}""").refusal("conv_7")
            assertIs<ProxyError.QuotaExceeded>(refusal)
        }

    @Test
    fun anyOtherRecordedReasonIsTheRefusal() =
        runTest {
            val refusal = source(HttpStatusCode.OK, """{"status":"failed","reason":"Too many calls at once."}""").refusal("conv_7")
            assertIs<AgentRefused>(refusal)
            assertEquals("Too many calls at once.", refusal.message)
        }

    @Test
    fun aFailedLookupThrows() =
        runTest {
            assertFailsWith<ProxyError.Http> { source(HttpStatusCode.BadGateway, """{"error":"x"}""").refusal("conv_7") }
        }
}
