package com.x8bit.bitwarden.data.wear

import com.bitwarden.vault.LoginUriView

/** Resolves website icon metadata using the phone's icon settings. */
interface WearOtpIconSource {
    /** Returns a website icon URL, or null when a local fallback should be shown. */
    fun getIconUrl(uris: List<LoginUriView>?): String?
}
