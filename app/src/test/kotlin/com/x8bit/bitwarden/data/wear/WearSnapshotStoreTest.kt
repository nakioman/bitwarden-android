package com.x8bit.bitwarden.data.wear

import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearOtpEntry
import com.bitwarden.core.data.wear.WearOtpPayloadProtocol
import com.bitwarden.core.data.wear.WearPayloadCipher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator

class WearSnapshotStoreTest {
    private val dataSource = mockk<WearDataSource>()
    private val store = WearSnapshotStoreImpl(dataSource, FakeDispatcherManager())
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public.encoded
    private val keyId = WearDataProtocol.recipientKeyId(publicKey)
    private val entries = listOf(WearOtpEntry("id", "GitHub", "user", "secret"))
    private val published = mutableListOf<Pair<WearSnapshotInfo, ByteArray>>()

    @BeforeEach
    fun setUp() {
        coEvery { dataSource.getWatchKeys() } returns Result.success(
            listOf(WearWatchKey("watch", publicKey)),
        )
        coEvery { dataSource.getSnapshots() } answers {
            Result.success(published.map { it.first })
        }
        coEvery { dataSource.putSnapshot(any(), any()) } answers {
            published.removeAll { it.first.watchNodeId == firstArg<WearSnapshotInfo>().watchNodeId }
            published += firstArg<WearSnapshotInfo>() to secondArg<ByteArray>()
            Result.success(Unit)
        }
        coEvery { dataSource.deleteSnapshot(any()) } answers {
            published.removeAll { it.first.watchNodeId == firstArg<String>() }
            Result.success(Unit)
        }
    }

    @Test
    fun `publish encrypts entries for the watch key`() = runTest {
        assertTrue(store.publish("user", entries) { true }.isSuccess)

        val (info, envelope) = published.single()
        assertEquals("watch", info.watchNodeId)
        assertEquals(keyId, info.recipientKeyId)
        val plaintext = WearPayloadCipher.decrypt(
            keyPair.private, envelope, WearDataProtocol.snapshotContext(keyId),
        ).getOrThrow()
        assertEquals(
            listOf("secret"),
            WearOtpPayloadProtocol.decode(plaintext).getOrThrow().map { it.secret },
        )
    }

    @Test
    fun `unchanged content is not republished`() = runTest {
        store.publish("user", entries) { true }.getOrThrow()
        store.publish("user", entries) { true }.getOrThrow()
        coVerify(exactly = 1) { dataSource.putSnapshot(any(), any()) }
    }

    @Test
    fun `a new watch key is republished`() = runTest {
        store.publish("user", entries) { true }.getOrThrow()
        val newKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair().public.encoded
        coEvery { dataSource.getWatchKeys() } returns Result.success(
            listOf(WearWatchKey("watch", newKey)),
        )
        store.publish("user", entries) { true }.getOrThrow()
        assertEquals(
            WearDataProtocol.recipientKeyId(newKey),
            published.single().first.recipientKeyId,
        )
    }

    @Test
    fun `snapshots for watches without a key are removed`() = runTest {
        store.publish("user", entries) { true }.getOrThrow()
        coEvery { dataSource.getWatchKeys() } returns Result.success(emptyList())
        store.publish("user", entries) { true }.getOrThrow()
        assertTrue(published.isEmpty())
    }

    @Test
    fun `nothing is published once no longer allowed`() = runTest {
        assertTrue(store.publish("user", entries) { false }.isSuccess)
        coVerify(exactly = 0) { dataSource.putSnapshot(any(), any()) }
    }

    @Test
    fun `remove deletes only the account's snapshots`() = runTest {
        store.publish("user", entries) { true }.getOrThrow()
        store.remove("other").getOrThrow()
        assertEquals(1, published.size)
        store.remove("user").getOrThrow()
        assertTrue(published.isEmpty())
    }

    @Test
    fun `transport failures are reported`() = runTest {
        coEvery { dataSource.getWatchKeys() } returns Result.failure(IOException())
        assertTrue(store.publish("user", entries) { true }.isFailure)
        coEvery { dataSource.getSnapshots() } returns Result.failure(IOException())
        assertTrue(store.remove("user").isFailure)
    }
}
