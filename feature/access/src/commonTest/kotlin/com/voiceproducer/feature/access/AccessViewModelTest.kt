package com.voiceproducer.feature.access

import com.voiceproducer.core.domain.access.ProxyAccess
import com.voiceproducer.core.domain.usecase.SubmitAccessCodeUseCase
import com.voiceproducer.core.model.ProxyError
import com.voiceproducer.core.testing.FakeAccessCheck
import com.voiceproducer.core.testing.FakeAccessStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccessViewModelTest {
    private val main = StandardTestDispatcher()
    private val check = FakeAccessCheck()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(main)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(store: FakeAccessStore): AccessViewModel =
        AccessViewModel(store, SubmitAccessCodeUseCase(check, store)).also { advanceUntilIdle() }

    @Test
    fun withoutAccessTheSheetOpensAtStart() =
        runTest(main) {
            val state = viewModel(FakeAccessStore()).state.value

            assertFalse(state.granted)
            assertTrue(state.sheetOpen)
        }

    @Test
    fun withAccessTheSheetStaysClosed() =
        runTest(main) {
            val state = viewModel(FakeAccessStore(ProxyAccess.Code("c"))).state.value

            assertTrue(state.granted)
            assertFalse(state.sheetOpen)
        }

    @Test
    fun anAcceptedCodeClosesTheSheet() =
        runTest(main) {
            val vm = viewModel(FakeAccessStore())
            vm.onIntent(AccessIntent.CodeChanged("river-copper-violin-dawn"))
            vm.onIntent(AccessIntent.Submit)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state.granted)
            assertFalse(state.sheetOpen)
            assertEquals("", state.code)
            assertNull(state.error)
        }

    @Test
    fun theSheetShowsCheckingWhileTheProxyAnswers() =
        runTest(main) {
            val answer = CompletableDeferred<Unit>()
            check.answer = { answer.await() }
            val vm = viewModel(FakeAccessStore())
            vm.onIntent(AccessIntent.CodeChanged("river-copper-violin-dawn"))
            vm.onIntent(AccessIntent.Submit)
            advanceUntilIdle()

            assertTrue(vm.state.value.checking)
            vm.onIntent(AccessIntent.Submit)
            answer.complete(Unit)
            advanceUntilIdle()

            assertFalse(vm.state.value.checking)
            assertEquals(1, check.checked.size)
        }

    @Test
    fun aRejectedCodeStaysWithAnErrorThatTypingClears() =
        runTest(main) {
            check.answer = { throw ProxyError.Unauthorized() }
            val vm = viewModel(FakeAccessStore())
            vm.onIntent(AccessIntent.CodeChanged("river-copper-violin"))
            vm.onIntent(AccessIntent.Submit)
            advanceUntilIdle()

            assertEquals("That code didn't work. Check it and try again.", vm.state.value.error)
            assertEquals("river-copper-violin", vm.state.value.code)
            assertTrue(vm.state.value.sheetOpen)

            vm.onIntent(AccessIntent.CodeChanged("river-copper-violin-"))
            assertNull(vm.state.value.error)
        }

    @Test
    fun anUnreachableProxyShowsWhy() =
        runTest(main) {
            check.answer = { throw ProxyError.Http(503, "") }
            val vm = viewModel(FakeAccessStore())
            vm.onIntent(AccessIntent.CodeChanged("river-copper-violin-dawn"))
            vm.onIntent(AccessIntent.Submit)
            advanceUntilIdle()

            assertEquals("Couldn't check the code: The server had a problem (http 503), try again", vm.state.value.error)
        }

    @Test
    fun aDismissedSheetReopensWhenSomethingNeedsAccess() =
        runTest(main) {
            val vm = viewModel(FakeAccessStore())
            vm.onIntent(AccessIntent.Dismiss)
            assertFalse(vm.state.value.sheetOpen)

            var proceeded = false
            vm.guard { proceeded = true }

            assertFalse(proceeded)
            assertTrue(vm.state.value.sheetOpen)
        }

    @Test
    fun withAccessTheGuardedActionProceeds() =
        runTest(main) {
            val vm = viewModel(FakeAccessStore(ProxyAccess.Code("c")))
            var proceeded = false

            vm.guard { proceeded = true }

            assertTrue(proceeded)
            assertFalse(vm.state.value.sheetOpen)
        }

    @Test
    fun losingAccessReopensTheSheet() =
        runTest(main) {
            val store = FakeAccessStore(ProxyAccess.Code("c"))
            val vm = viewModel(store)

            store.clear()
            advanceUntilIdle()

            assertFalse(vm.state.value.granted)
            assertTrue(vm.state.value.sheetOpen)
        }
}
