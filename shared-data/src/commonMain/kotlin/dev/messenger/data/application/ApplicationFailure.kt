package dev.messenger.data.application

import dev.messenger.data.ConversationRepository
import dev.messenger.domain.ApplicationFailure
import kotlinx.coroutines.CancellationException

internal suspend fun <T> applicationCall(work:suspend ()->T):T = try {work()}
catch(cancelled:CancellationException) {throw cancelled}
catch(failure:ApplicationFailure) {throw failure}
catch(failure:Exception) {throw ApplicationFailure(ConversationRepository.safeError(failure),failure)}
