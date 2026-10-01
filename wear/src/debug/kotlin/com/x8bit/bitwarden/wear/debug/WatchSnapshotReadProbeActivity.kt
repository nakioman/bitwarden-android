package com.x8bit.bitwarden.wear.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bitwarden.core.data.wear.WearSnapshotProbeProtocol
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManager
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.data.sync.WatchSnapshotManager
import com.x8bit.bitwarden.wear.debug.di.SnapshotTransportProbe
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Checks the isolated synthetic cache locally, including with the phone disconnected. */
@AndroidEntryPoint
class WatchSnapshotReadProbeActivity : ComponentActivity() {
    /** Synthetic-only cache, with no access to companion keys or snapshots. */
    @Inject
    @SnapshotTransportProbe
    lateinit var manager: WatchSnapshotManager

    /** System lock status used to distinguish a denied read from a test failure. */
    @Inject
    lateinit var securityManager: WatchSecurityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val result = manager.read()
            val outcome = result.fold(
                onSuccess = { plaintext ->
                    val message = when {
                        plaintext == null -> "PASS: synthetic cache is empty"
                        plaintext.contentEquals(
                            WearSnapshotProbeProtocol.SYNTHETIC_TEXT.toByteArray(),
                        ) -> "PASS: offline synthetic snapshot decrypted locally"
                        else -> "FAIL: synthetic data mismatch"
                    }
                    plaintext?.fill(0)
                    message
                },
                onFailure = {
                    if (securityManager.getStatus() == WatchSecurityStatus.LOCKED) {
                        "PASS: local snapshot read denied while locked"
                    } else {
                        "FAIL: synthetic cache could not be read"
                    }
                },
            )
            Log.i("WearSnapshotProbe", outcome)
            finish()
        }
    }
}
