package com.myfriend.app.domain

import javax.inject.Inject
import javax.inject.Singleton

/** Tracks which conversation is visibly open, so unread counts stay meaningful. */
@Singleton
class OpenSessionTracker @Inject constructor() {
    @Volatile var openSessionId: String? = null
}
