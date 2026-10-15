package com.voiceproducer.core.data.remote

import com.voiceproducer.core.domain.access.ProxyAccess
import com.voiceproducer.core.domain.agent.AgentToken
import com.voiceproducer.core.model.AudioFormat
import com.voiceproducer.core.model.CompositionPlan
import com.voiceproducer.core.model.GenerationChunk
import com.voiceproducer.core.model.ProxyError
import com.voiceproducer.core.testing.MarkingDispatcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val credentials = FixedCredentials(ProxyAccess.Code("sekret"))

    private fun api(
        handler: MockRequestHandler,
        baseUrl: String = "https://p.example/fn",
    ) = ProxyApi(HttpClient(MockEngine(handler)), baseUrl, credentials)

    private fun HttpRequestData.bodyText(): String = (body as TextContent).text

    @Test
    fun sendsTheAccessCodeHeader() =
        runTest {
            lateinit var seen: HttpRequestData
            api({ req ->
                seen = req
                respond("""{"token":"t"}""", HttpStatusCode.OK, jsonHeaders)
            }).agentToken()
            assertEquals("sekret", seen.headers["x-access-code"])
            assertNull(seen.headers["x-app-secret"])
            assertEquals(HttpMethod.Get, seen.method)
            assertEquals("https://p.example/fn/agent-token", seen.url.toString())
        }

    @Test
    fun trailingSlashInBaseUrlIsNormalized() =
        runTest {
            var url = ""
            api({ req ->
                url = req.url.toString()
                respond("""{"token":"t"}""", HttpStatusCode.OK, jsonHeaders)
            }, baseUrl = "https://p.example/fn/").agentToken()
            assertEquals("https://p.example/fn/agent-token", url)
        }

    @Test
    fun createPlanDecodesChunks() =
        runTest {
            lateinit var seen: HttpRequestData
            val plan =
                api({ req ->
                    seen = req
                    respond(
                        """{"chunks":[{"text":"[A]","duration_ms":8000,"positive_styles":["x"]},
                    {"song_id":"s","range":{"start_ms":0,"end_ms":10}}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }).createPlan("a song", 44_000)
            assertEquals(2, plan.chunks.size)
            assertEquals("[A]", (plan.chunks[0] as GenerationChunk).text)
            assertEquals("https://p.example/fn/music-plan", seen.url.toString())
            assertEquals(HttpMethod.Post, seen.method)
            assertEquals("""{"prompt":"a song","music_length_ms":44000}""", seen.bodyText())
        }

    @Test
    fun composeParsesSongIdAndFormat() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3, 4)
            lateinit var seen: HttpRequestData
            val plan = CompositionPlan(listOf(GenerationChunk("[A]", 1000)))
            val result =
                api({ req ->
                    seen = req
                    respond(
                        bytes,
                        HttpStatusCode.OK,
                        headersOf(
                            "song-id" to listOf("s1"),
                            "x-audio-format" to listOf("pcm_24000"),
                            "x-audio-channels" to listOf("1"),
                        ),
                    )
                }).compose(plan)
            assertEquals(ComposeResult("s1", AudioFormat.Pcm(24000, 1), bytes), result)
            assertEquals("https://p.example/fn/music-compose", seen.url.toString())
            assertTrue(seen.bodyText().startsWith("""{"composition_plan":{"chunks":["""), seen.bodyText())
        }

    @Test
    fun callsAndTheirLoggingRunOnTheIoDispatcher() =
        runTest {
            val io = MarkingDispatcher(StandardTestDispatcher(testScheduler))
            val onIo = mutableListOf<Boolean>()
            val log = ApiLog(logger = { onIo += io.running })
            val api =
                ProxyApi(
                    HttpClient(
                        MockEngine { req ->
                            when (req.url.encodedPath.substringAfterLast('/')) {
                                "agent-token" -> {
                                    respond("""{"token":"t"}""", HttpStatusCode.OK, jsonHeaders)
                                }

                                "music-plan" -> {
                                    respond("""{"chunks":[]}""", HttpStatusCode.OK, jsonHeaders)
                                }

                                else -> {
                                    respond(
                                        byteArrayOf(1, 2),
                                        HttpStatusCode.OK,
                                        headersOf("song-id" to listOf("s1"), "x-audio-format" to listOf("pcm_24000")),
                                    )
                                }
                            }
                        },
                    ),
                    "https://p.example/fn",
                    credentials,
                    log,
                    io,
                )
            api.agentToken()
            api.createPlan("p", 1000)
            api.compose(CompositionPlan(emptyList()))
            assertEquals(listOf(true, true, true), onIo)
        }

    @Test
    fun composeWithoutSongIdThrowsHttp200() =
        runTest {
            val e =
                assertFailsWith<ProxyError.Http> {
                    api({ respond(byteArrayOf(1), HttpStatusCode.OK, headersOf("x-audio-format", "pcm_24000")) })
                        .compose(CompositionPlan(emptyList()))
                }
            assertEquals(200, e.status)
            assertTrue(e.body.contains("song-id"))
        }

    @Test
    fun composeWithoutFormatThrowsHttp200() =
        runTest {
            val e =
                assertFailsWith<ProxyError.Http> {
                    api({ respond(byteArrayOf(1), HttpStatusCode.OK, headersOf("song-id", "s1")) })
                        .compose(CompositionPlan(emptyList()))
                }
            assertEquals(200, e.status)
            assertTrue(e.body.contains("x-audio-format"))
        }

    @Test
    fun compose429ThrowsRateLimited() =
        runTest {
            assertFailsWith<ProxyError.RateLimited> {
                api({ respond("""{"error":"slow down","status":429}""", HttpStatusCode.TooManyRequests, jsonHeaders) })
                    .compose(CompositionPlan(emptyList()))
            }
        }

    @Test
    fun otherErrorsThrowHttpWithBody() =
        runTest {
            val body = """{"error":"boom","status":502}"""
            val e =
                assertFailsWith<ProxyError.Http> {
                    api({ respond(body, HttpStatusCode.BadGateway, jsonHeaders) }).createPlan("p", 1000)
                }
            assertEquals(502, e.status)
            assertEquals(body, e.body)
            assertIs<ProxyError>(e)
        }

    @Test
    fun badPrompt422MapsToTypedErrorWithSuggestion() =
        runTest {
            val body = """{"error":"bad_prompt","status":422,"reason":"copyrighted_material_detected","prompt_suggestion":"lush strings"}"""
            val e =
                assertFailsWith<ProxyError.BadPrompt> {
                    api({ respond(body, HttpStatusCode.UnprocessableEntity, jsonHeaders) }).createPlan("p", 1000)
                }
            assertEquals("copyrighted_material_detected", e.reason)
            assertEquals("lush strings", e.suggestion)
            assertEquals("Prompt rejected: it names artists or copyrighted material", e.message)
        }

    @Test
    fun badPrompt422WithNullsAndOnCompose() =
        runTest {
            val e =
                assertFailsWith<ProxyError.BadPrompt> {
                    api({
                        respond(
                            """{"error":"bad_prompt","status":422,"reason":null,"prompt_suggestion":null}""",
                            HttpStatusCode.UnprocessableEntity,
                            jsonHeaders,
                        )
                    })
                        .compose(CompositionPlan(emptyList()))
                }
            assertEquals(null, e.reason)
            assertEquals(null, e.suggestion)
        }

    @Test
    fun quotaExceeded402MapsToTypedErrorWithTheCredits() =
        runTest {
            val body = """{"error":"quota_exceeded","status":402,"credits_remaining":137,"credits_required":325}"""
            val e =
                assertFailsWith<ProxyError.QuotaExceeded> {
                    api({ respond(body, HttpStatusCode.PaymentRequired, jsonHeaders) }).compose(CompositionPlan(emptyList()))
                }
            assertEquals(137, e.remaining)
            assertEquals(325, e.required)
            assertEquals("Not enough ElevenLabs credits: 137 left, 325 needed", e.message)
        }

    @Test
    fun quotaExceededWithoutNumbersAndOther402sStayReadable() =
        runTest {
            val bare =
                assertFailsWith<ProxyError.QuotaExceeded> {
                    api({
                        respond(
                            """{"error":"quota_exceeded","status":402,"credits_remaining":null,"credits_required":null}""",
                            HttpStatusCode.PaymentRequired,
                            jsonHeaders,
                        )
                    }).createPlan("p", 1000)
                }
            assertEquals("Not enough ElevenLabs credits", bare.message)
            val other =
                assertFailsWith<ProxyError.Http> {
                    api({ respond("""{"error":"x","status":402}""", HttpStatusCode.PaymentRequired, jsonHeaders) }).createPlan("p", 1000)
                }
            assertEquals(402, other.status)
        }

    @Test
    fun other422AndUnparsableBodiesStayHttp() =
        runTest {
            for (body in listOf("""{"error":"unprocessable","status":422}""", "not json", "[1]")) {
                val e =
                    assertFailsWith<ProxyError.Http> {
                        api({ respond(body, HttpStatusCode.UnprocessableEntity, jsonHeaders) }).createPlan("p", 1000)
                    }
                assertEquals(422, e.status)
                assertEquals(body, e.body)
            }
        }

    @Test
    fun httpErrorsHaveReadableMessages() {
        assertEquals("The request failed (http 400)", ProxyError.Http(400, "").message)
        assertEquals("The server had a problem (http 502), try again", ProxyError.Http(502, "").message)
    }

    @Test
    fun agentTokenReadsToken() =
        runTest {
            val token = api({ respond("""{"token":"abc"}""", HttpStatusCode.OK, jsonHeaders) }).agentToken()
            assertEquals(AgentToken("abc", null), token)
        }

    @Test
    fun agentTokenReadsTheConversationIdFromItsRoom() =
        runTest {
            val payload =
                Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(
                    """{"video":{"roomJoin":true,"room":"room_agent_123_conv_7201m50ahs"}}""".encodeToByteArray(),
                )
            val jwt = "eyJhbGciOiJIUzI1NiJ9.$payload.sig"

            val token = api({ respond("""{"token":"$jwt"}""", HttpStatusCode.OK, jsonHeaders) }).agentToken()

            assertEquals(AgentToken(jwt, "conv_7201m50ahs"), token)
        }

    @Test
    fun agentTokenWithoutARoomHasNoConversationId() =
        runTest {
            val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"sub":"u"}""".encodeToByteArray())
            for (raw in listOf("a.$payload.c", "a.%%%.c", "plain")) {
                val token = api({ respond("""{"token":"$raw"}""", HttpStatusCode.OK, jsonHeaders) }).agentToken()
                assertNull(token.conversationId, raw)
            }
        }

    @Test
    fun agentRefusalAsksTheProxyAboutTheConversation() =
        runTest {
            lateinit var seen: HttpRequestData
            val reason =
                api({ req ->
                    seen = req
                    respond("""{"status":"failed","reason":"Too many calls at once."}""", HttpStatusCode.OK, jsonHeaders)
                }).agentRefusal("conv_7")
            assertEquals("Too many calls at once.", reason)
            assertEquals("https://p.example/fn/agent-conversation?id=conv_7", seen.url.toString())
            assertEquals("sekret", seen.headers["x-access-code"])
        }

    @Test
    fun agentRefusalWithoutARecordedReasonIsNull() =
        runTest {
            val reason =
                api({ respond("""{"status":"in-progress","reason":null}""", HttpStatusCode.OK, jsonHeaders) })
                    .agentRefusal("conv_7")
            assertNull(reason)
        }

    @Test
    fun agentRefusalForAnExhaustedQuotaThrowsTheTypedError() =
        runTest {
            val body = """{"error":"quota_exceeded","status":402,"credits_remaining":null,"credits_required":null}"""
            val e =
                assertFailsWith<ProxyError.QuotaExceeded> {
                    api({ respond(body, HttpStatusCode.PaymentRequired, jsonHeaders) }).agentRefusal("conv_7")
                }
            assertEquals("Not enough ElevenLabs credits", e.message)
        }

    @Test
    fun anUnauthorizedAnswerDropsTheAccessAndThrowsTheTypedError() =
        runTest {
            assertFailsWith<ProxyError.Unauthorized> {
                api({ respond("""{"error":"unauthorized","status":401}""", HttpStatusCode.Unauthorized, jsonHeaders) }).agentToken()
            }
            assertEquals(1, credentials.rejections)
        }

    @Test
    fun withoutAccessNoAccessHeaderIsSent() =
        runTest {
            lateinit var seen: HttpRequestData
            ProxyApi(
                HttpClient(
                    MockEngine { req ->
                        seen = req
                        respond("""{"token":"t"}""", HttpStatusCode.OK, jsonHeaders)
                    },
                ),
                "https://p.example/fn",
                FixedCredentials(null),
            ).agentToken()
            assertNull(seen.headers["x-access-code"])
        }

    @Test
    fun checkingAccessAsksTheProxyWithTheGivenCode() =
        runTest {
            lateinit var seen: HttpRequestData
            api({ req ->
                seen = req
                respond("""{"access":"code"}""", HttpStatusCode.OK, jsonHeaders)
            }).checkAccess(ProxyAccess.Code("river-copper-violin-dawn"))
            assertEquals("https://p.example/fn/access", seen.url.toString())
            assertEquals(HttpMethod.Get, seen.method)
            assertEquals("river-copper-violin-dawn", seen.headers["x-access-code"])
        }

    @Test
    fun aRejectedCheckThrowsWithoutDroppingTheStoredAccess() =
        runTest {
            assertFailsWith<ProxyError.Unauthorized> {
                api({ respond("""{"error":"unauthorized","status":401}""", HttpStatusCode.Unauthorized, jsonHeaders) })
                    .checkAccess(ProxyAccess.Code("nope"))
            }
            assertEquals(0, credentials.rejections)
        }

    @Test
    fun theApiLogNeverRecordsTheCode() =
        runTest {
            val log = ApiLog()
            logged(log) { respond("""{"access":"code"}""", HttpStatusCode.OK, jsonHeaders) }
                .checkAccess(ProxyAccess.Code("river-copper-violin-dawn"))
            val entry = log.entries.value.single()
            assertTrue("river-copper-violin-dawn" !in entry.request + entry.response.orEmpty(), entry.toString())
        }

    @Test
    fun composeResultEqualityComparesBytesByContent() {
        val f = AudioFormat.Pcm(24000, 1)
        assertEquals(ComposeResult("s", f, byteArrayOf(1, 2)), ComposeResult("s", f, byteArrayOf(1, 2)))
        assertEquals(
            ComposeResult("s", f, byteArrayOf(1, 2)).hashCode(),
            ComposeResult("s", f, byteArrayOf(1, 2)).hashCode(),
        )
    }

    private fun logged(
        log: ApiLog,
        handler: MockRequestHandler,
    ) = ProxyApi(HttpClient(MockEngine(handler)), "https://p.example/fn", credentials, log)

    @Test
    fun planCallRecordsRequestPrettyResponseAndStatus() =
        runTest {
            val log = ApiLog()
            logged(
                log,
            ) { respond("""{"chunks":[{"text":"[A]","duration_ms":8000,"positive_styles":["x"]}]}""", HttpStatusCode.OK, jsonHeaders) }
                .createPlan("a song", 44_000)
            val e = log.entries.value.single()
            assertEquals("music-plan", e.endpoint)
            assertEquals("{\n    \"prompt\": \"a song\",\n    \"music_length_ms\": 44000\n}", e.request)
            assertEquals(200, e.status)
            assertTrue(e.durationMs != null)
            assertTrue(e.response!!.contains("\n    \"chunks\": ["), e.response)
            assertTrue(e.response!!.contains("\"text\": \"[A]\""))
            assertNull(e.error)
        }

    @Test
    fun composeCallRecordsPlanAndSummaryWithoutAudioBytes() =
        runTest {
            val log = ApiLog()
            val audio = ByteArray(4096) { 7 }
            logged(log) {
                respond(
                    audio,
                    HttpStatusCode.OK,
                    headersOf("song-id" to listOf("s1"), "x-audio-format" to listOf("pcm_24000"), "x-audio-channels" to listOf("1")),
                )
            }.compose(CompositionPlan(listOf(GenerationChunk("[Verse] lush", 8000, positiveStyles = listOf("dreamy")))))
            val e = log.entries.value.single()
            assertEquals("music-compose", e.endpoint)
            assertTrue(e.request.contains("\"composition_plan\""))
            assertTrue(e.request.contains("[Verse] lush") && e.request.contains("dreamy") && e.request.contains("8000"))
            assertEquals(200, e.status)
            assertEquals("s1", e.songId)
            assertEquals("format=pcm_24000 channels=1 size=4096 bytes song-id=s1", e.response)
        }

    @Test
    fun failureRecordsStatusBodyAndError() =
        runTest {
            val log = ApiLog()
            assertFailsWith<ProxyError.Http> {
                logged(log) { respond("""{"error":"boom"}""", HttpStatusCode.BadGateway, jsonHeaders) }.createPlan("p", 1000)
            }
            val e = log.entries.value.single()
            assertEquals(502, e.status)
            assertEquals("""{"error":"boom"}""", e.response)
            assertEquals("The server had a problem (http 502), try again", e.error)
        }

    @Test
    fun badPromptRecordsReasonAndSuggestion() =
        runTest {
            val log = ApiLog()
            val body = """{"error":"bad_prompt","status":422,"reason":"copyrighted_material_detected","prompt_suggestion":"lush strings"}"""
            assertFailsWith<ProxyError.BadPrompt> {
                logged(log) { respond(body, HttpStatusCode.UnprocessableEntity, jsonHeaders) }.createPlan("p", 1000)
            }
            val e = log.entries.value.single()
            assertEquals(422, e.status)
            assertTrue(e.error!!.contains("copyrighted_material_detected") && e.error!!.contains("lush strings"), e.error)
        }

    @Test
    fun networkErrorRecordsErrorWithNullStatus() =
        runTest {
            val log = ApiLog()
            assertFailsWith<IllegalStateException> {
                logged(log) { throw IllegalStateException("no route") }.createPlan("p", 1000)
            }
            val e = log.entries.value.single()
            assertNull(e.status)
            assertEquals("no route", e.error)
        }

    @Test
    fun agentTokenNeverRecordsTokenOrSecret() =
        runTest {
            val log = ApiLog()
            val token = "tok-SUPER-private-123"
            logged(log) { respond("""{"token":"$token"}""", HttpStatusCode.OK, jsonHeaders) }.agentToken()
            assertFailsWith<ProxyError.Unauthorized> {
                logged(log) { respond("""{"error":"x","echo":"$token sekret"}""", HttpStatusCode.Unauthorized, jsonHeaders) }.agentToken()
            }
            assertFailsWith<Exception> {
                logged(log) { respond("""{"token":"$token" oops""", HttpStatusCode.OK, jsonHeaders) }.agentToken()
            }
            val entries = log.entries.value
            assertEquals(3, entries.size)
            assertEquals("invalid response", entries[2].error)
            assertEquals("agent-token", entries[0].endpoint)
            assertEquals(200, entries[0].status)
            assertEquals("token received", entries[0].response)
            for (e in entries) {
                for (field in listOfNotNull(e.request, e.response, e.error, e.songId, e.endpoint)) {
                    assertTrue(!field.contains(token) && !field.contains("sekret"), "leaked in $field")
                }
            }
        }

    @Test
    fun throwingLoggerDoesNotFailASuccessfulCall() =
        runTest {
            val log = ApiLog(logger = { error("logger down") })
            val token = logged(log) { respond("""{"token":"t"}""", HttpStatusCode.OK, jsonHeaders) }.agentToken()
            assertEquals("t", token.value)
            assertNull(
                log.entries.value
                    .single()
                    .error,
            )
        }

    @Test
    fun cancellationIsRecordedAsCancelledAndRethrown() =
        runTest {
            val log = ApiLog()
            assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
                logged(log) { throw kotlin.coroutines.cancellation.CancellationException("job cancelled") }.createPlan("p", 1000)
            }
            assertEquals(
                "cancelled",
                log.entries.value
                    .single()
                    .error,
            )
        }
}
