package com.nuvio.tv.core.profile

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lets screens deep in the nav graph (Settings > CinemaOS Account) ask MainActivity to return to
 * "Who's watching"; MainActivity owns the profile-gate state, so it handles the request.
 */
object ProfileSwitchRequests {
    private val _requests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    fun request() {
        _requests.tryEmit(Unit)
    }
}
