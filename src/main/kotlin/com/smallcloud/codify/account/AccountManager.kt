package com.smallcloud.codify.account

import com.intellij.openapi.application.ApplicationManager
import com.smallcloud.codify.settings.AppSettingsState

object AccountManager {
    private var previousLoggedInState: Boolean = false

    var ticket: String?
        get() = AppSettingsState.instance.streamlinedLoginTicket
        set(newTicket) {
            if (newTicket == ticket) return
            ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                .ticketChanged(newTicket)
            checkLoggedInAndNotifyIfNeed()
        }

    var user: String?
        get() = AppSettingsState.instance.userLoggedIn
        set(newUser) {
            if (newUser == user) return
            ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                .userChanged(newUser)
            checkLoggedInAndNotifyIfNeed()
        }
    var apiKey: String?
        get() = AppSettingsState.instance.apiKey
        set(newApiKey) {
            if (newApiKey == apiKey) return
            ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                .apiKeyChanged(newApiKey)
            checkLoggedInAndNotifyIfNeed()
        }
    var activePlan: String? = null
        set(newPlan) {
            if (newPlan == field) return
            field = newPlan
            ApplicationManager.getApplication()
                .messageBus
                .syncPublisher(AccountManagerChangedNotifier.TOPIC)
                .planStatusChanged(newPlan)
        }

    val isLoggedIn: Boolean
        get() {
            return !apiKey.isNullOrEmpty() && !user.isNullOrEmpty()
        }

    private fun loadFromSettings() {
        previousLoggedInState = isLoggedIn
    }

    fun startup() {
        loadFromSettings()
    }

    private fun checkLoggedInAndNotifyIfNeed() {
        if (previousLoggedInState == isLoggedIn) return
        previousLoggedInState = isLoggedIn
        loginChangedNotify(isLoggedIn)
    }

    private fun loginChangedNotify(isLoggedIn: Boolean) {
        ApplicationManager.getApplication()
            .messageBus
            .syncPublisher(AccountManagerChangedNotifier.TOPIC)
            .isLoggedInChanged(isLoggedIn)
    }

    fun logout() {
        apiKey = null
        user = null
    }
}
