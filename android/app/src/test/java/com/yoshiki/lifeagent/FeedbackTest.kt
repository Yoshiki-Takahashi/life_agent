package com.yoshiki.lifeagent

import androidx.lifecycle.SavedStateHandle
import com.yoshiki.lifeagent.data.FeedbackRepository
import com.yoshiki.lifeagent.data.FeedbackRequest
import com.yoshiki.lifeagent.data.FeedbackResult
import com.yoshiki.lifeagent.ui.FeedbackViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun lostResponseKeepsSameMemoAcrossRecreationAndNewMemoGetsNewId() = runTest(dispatcher) {
        val requests = mutableListOf<FeedbackRequest>()
        val repository = FeedbackRepository { request ->
            requests += request
            if (requests.size == 1) throw IOException("lost response")
            FeedbackResult(40, "https://github.com/example/app/issues/40")
        }
        val saved = SavedStateHandle()
        val first = FeedbackViewModel(repository, saved)
        first.openedFrom("coach")
        first.updateBody("計画画面で期限を変更したい")
        first.send()
        first.send() // Double tap must not send twice.
        advanceUntilIdle()
        assertEquals(1, requests.size)
        assertEquals("計画画面で期限を変更したい", first.uiState.value.body)
        val restored = FeedbackViewModel(repository, saved)
        restored.updateBody("結果不明の内容を書き換えない")
        restored.openedFrom("home")
        restored.send()
        advanceUntilIdle()
        assertEquals(requests[0], requests[1])
        assertEquals("coach", requests[1].screen)
        assertEquals(40, restored.uiState.value.result?.issueNumber)
        assertEquals("", restored.uiState.value.body)
        restored.updateBody("別の改善点")
        restored.send()
        advanceUntilIdle()
        assertNotEquals(requests[1].clientRequestId, requests[2].clientRequestId)
    }

    @Test fun explicitRejectionAllowsEditingBeforeRetry() = runTest(dispatcher) {
        val requests = mutableListOf<FeedbackRequest>()
        val vm = FeedbackViewModel(FeedbackRepository {
            requests += it
            if (requests.size == 1) throw HttpException(Response.error<Unit>(503,
                """{"detail":{"message":"not configured","result_unknown":false}}""".toResponseBody()))
            FeedbackResult(41, "https://github.com/example/app/issues/41")
        })
        vm.updateBody("最初のメモ")
        vm.send()
        advanceUntilIdle()
        vm.updateBody("修正したメモ")
        vm.send()
        advanceUntilIdle()
        assertEquals("修正したメモ", requests[1].body)
        assertNotEquals(requests[0].clientRequestId, requests[1].clientRequestId)
        assertEquals(41, vm.uiState.value.result?.issueNumber)
    }

    @Test fun missingConfigurationExplainsFailureAndRetriesSameMemoAfterSetup() = runTest(dispatcher) {
        val requests = mutableListOf<FeedbackRequest>()
        val vm = FeedbackViewModel(FeedbackRepository {
            requests += it
            if (requests.size == 1) throw HttpException(Response.error<Unit>(503,
                """{"detail":{"message":"フィードバックの送信先が未設定です","result_unknown":false}}""".toResponseBody()))
            FeedbackResult(43, "https://github.com/example/app/issues/43")
        })
        vm.updateBody("送信したいメモ")
        vm.send()
        advanceUntilIdle()
        assertEquals("フィードバックの送信先が未設定です", vm.uiState.value.error)
        assertEquals("送信したいメモ", vm.uiState.value.body)
        assertFalse(vm.uiState.value.resultUnknown)
        vm.send()
        advanceUntilIdle()
        assertEquals(requests[0], requests[1])
        assertEquals(43, vm.uiState.value.result?.issueNumber)
    }

    @Test fun logoutClearsDraftAndLateResultCannotRestorePreviousSession() = runTest(dispatcher) {
        val deferred = CompletableDeferred<FeedbackResult>()
        val saved = SavedStateHandle()
        val vm = FeedbackViewModel(FeedbackRepository { deferred.await() }, saved)
        vm.updateBody("個人のメモ")
        vm.send()
        runCurrent()
        vm.reset()
        deferred.complete(FeedbackResult(42, "https://github.com/example/app/issues/42"))
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.body)
        assertNull(vm.uiState.value.result)
        val nextSession = FeedbackViewModel(FeedbackRepository { error("unused") }, saved)
        assertEquals("", nextSession.uiState.value.body)
        assertFalse(nextSession.uiState.value.resultUnknown)
    }

    @Test fun laterConfigurationFailureCannotUnlockAnEarlierUncertainSubmission() = runTest(dispatcher) {
        val requests = mutableListOf<FeedbackRequest>()
        val vm = FeedbackViewModel(FeedbackRepository {
            requests += it
            when (requests.size) {
                1 -> throw IOException("response lost")
                2 -> throw HttpException(Response.error<Unit>(503,
                    """{"detail":{"message":"フィードバックの送信先が未設定です","result_unknown":false}}""".toResponseBody()))
                else -> FeedbackResult(44, "https://github.com/example/app/issues/44")
            }
        })
        vm.updateBody("元のメモ")
        vm.send()
        advanceUntilIdle()
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.resultUnknown)
        assertEquals("送信結果を確認できません。同じ内容で再確認してください。", vm.uiState.value.error)
        vm.updateBody("別の内容")
        vm.send()
        advanceUntilIdle()
        assertTrue(requests.all { it == requests[0] })
        assertEquals(44, vm.uiState.value.result?.issueNumber)
    }
}
