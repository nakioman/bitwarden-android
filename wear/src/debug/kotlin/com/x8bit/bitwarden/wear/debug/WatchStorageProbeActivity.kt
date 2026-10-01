package com.x8bit.bitwarden.wear.debug

import android.app.KeyguardManager
import android.os.Bundle
import android.util.AtomicFile
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.wear.WearPayloadCipher
import com.x8bit.bitwarden.wear.data.security.WatchSecurityManagerImpl
import com.x8bit.bitwarden.wear.data.security.WatchSecurityStatus
import com.x8bit.bitwarden.wear.data.sync.WatchEnvelopeDiskSourceImpl
import com.x8bit.bitwarden.wear.data.sync.WatchSecureStorageManagerImpl
import com.x8bit.bitwarden.wear.data.sync.WatchSyncKeyManagerImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

private const val TAG = "WearStorage"

/** Exercises real Keystore and atomic storage with synthetic data and a separate debug key/file. */
@AndroidEntryPoint
class WatchStorageProbeActivity : ComponentActivity() {
    /** Runs the probe outside the main thread. */
    @Inject
    lateinit var dispatcherManager: DispatcherManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val outcome = withContext(dispatcherManager.io) { performProbe() }
            Log.i(TAG, outcome)
            finish()
        }
    }

    private suspend fun performProbe(): String {
        val security = WatchSecurityManagerImpl(getSystemService(KeyguardManager::class.java))
        val status = security.getStatus()
        if (status != WatchSecurityStatus.UNLOCKED && status != WatchSecurityStatus.LOCKED) {
            return "Probe skipped: configure a secure watch screen lock first"
        }
        val keys = WatchSyncKeyManagerImpl("$packageName.wear.storage.probe.v1")
        val disk = WatchEnvelopeDiskSourceImpl(
            AtomicFile(File(noBackupFilesDir, "wear-storage-probe-v1")),
        )
        val manager = WatchSecureStorageManagerImpl(security, keys, disk, dispatcherManager)
        return try {
            manager.clear().getOrThrow()
            val publicKey = manager.provision().getOrThrow()
            val context = "synthetic-probe-enrollment".toByteArray()
            val plaintext = "synthetic-offline-payload".toByteArray()
            val envelope = WearPayloadCipher.encrypt(publicKey, plaintext, context).getOrThrow()
            manager.receive(envelope).getOrThrow()
            check(disk.read().getOrThrow()?.contentEquals(envelope) == true)
            if (status == WatchSecurityStatus.LOCKED) {
                check(keys.decrypt(envelope, context).isFailure)
                check(manager.read(context).isFailure)
                "PASS: locked watch persisted ciphertext; Keystore denied decryption"
            } else {
                val restored = manager.read(context).getOrThrow() ?: error("No cache")
                check(restored.contentEquals(plaintext))
                restored.fill(0)
                manager.clear().getOrThrow()
                check(keys.decrypt(envelope, context).isFailure)
                "PASS: encrypted disk round trip and key erasure on unlocked watch"
            }
        } catch (_: IOException) {
            "FAIL: storage probe could not read or persist its synthetic data"
        } catch (_: IllegalStateException) {
            "FAIL: storage probe assertion failed"
        } catch (_: java.security.GeneralSecurityException) {
            "FAIL: Keystore probe operation failed"
        } catch (_: IllegalArgumentException) {
            "FAIL: Keystore probe input rejected"
        } finally {
            keys.deleteKey()
            disk.delete()
        }
    }
}
