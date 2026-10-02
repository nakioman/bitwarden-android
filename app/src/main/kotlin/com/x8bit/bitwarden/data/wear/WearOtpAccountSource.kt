package com.x8bit.bitwarden.data.wear

import com.x8bit.bitwarden.data.auth.repository.model.UserState

/** Exposes an account only while it is active, logged in and its vault is unlocked. */
interface WearOtpAccountSource {
    /** Returns the eligible account, or null when OTP preparation is not permitted. */
    fun getAccount(userId: String): UserState.Account?
}
