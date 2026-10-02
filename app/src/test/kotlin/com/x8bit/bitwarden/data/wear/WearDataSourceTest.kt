package com.x8bit.bitwarden.data.wear

import android.net.Uri
import com.bitwarden.core.data.manager.UuidManager
import com.bitwarden.core.data.wear.WearDataChannel
import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.bitwarden.ui.platform.base.BaseRobolectricTest
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.PutDataRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WearDataSourceTest : BaseRobolectricTest() {
    private val client = mockk<DataClient>()
    private val uuidManager = mockk<UuidManager>()
    private val source = WearDataSourceImpl(client, uuidManager, WearDataChannel.SYNTHETIC)
    private val item = mockk<DataItem>()
    private val packet = WearSnapshotProtocol.encode(WearSnapshotFrame(
        WearSnapshotHeader("22222222-2222-4222-8222-222222222222", 1, "a".repeat(64)),
        byteArrayOf(1, 2, 3),
    )).getOrThrow()

    @Before
    fun setUp() {
        every { uuidManager.generateUuid() } returnsMany listOf("attempt-one", "attempt-two")
        every { item.uri } returns Uri.Builder().scheme("wear").authority("watch")
            .path(WearDataProtocol.receiptPath(WearDataChannel.SYNTHETIC, "watch")).build()
    }

    @Test
    fun `receipt from another owner is rejected even if bytes match`() = runTest {
        every { item.data } returns WearDataProtocol.createReceipt(packet).getOrThrow()
        every { item.uri } returns Uri.Builder().scheme("wear").authority("other-watch")
            .path(WearDataProtocol.receiptPath(WearDataChannel.SYNTHETIC, "watch")).build()
        every { client.getDataItem(any<Uri>()) } returns completedTask(Result.success(item))
        assertTrue(source.getReceipt("watch").isFailure)
    }

    @Test
    fun `publish uses an urgent targeted asset and fresh attempt for redelivery`() = runTest {
        val requests = mutableListOf<PutDataRequest>()
        every { client.putDataItem(capture(requests)) } returns completedTask(Result.success(item))
        assertTrue(source.publish("watch", packet).isSuccess)
        assertTrue(source.publish("watch", packet).isSuccess)
        requests.forEach { request ->
            assertEquals(
                WearDataProtocol.packetPath(WearDataChannel.SYNTHETIC, "watch"), request.uri.path,
            )
            assertTrue(request.isUrgent)
            assertEquals(
                Asset.createFromBytes(packet),
                request.assets.values.single(),
            )
        }
        assertFalse(requireNotNull(requests.first().data).contentEquals(requests.last().data))
        verify(exactly = 2) { uuidManager.generateUuid() }
        verify(exactly = 0) { client.getDataItem(any<Uri>()) }
    }

    @Test
    fun `malformed packet is rejected before reaching Google Play services`() = runTest {
        assertTrue(source.publish("watch", byteArrayOf()).isFailure)
        verify(exactly = 0) { client.putDataItem(any()) }
    }

    @Test
    fun `receipt lookup uses explicit watch ownership and returns a detached copy`() = runTest {
        val receipt = WearDataProtocol.createReceipt(packet).getOrThrow()
        every { item.data } returns receipt
        val uris = mutableListOf<Uri>()
        every { client.getDataItem(capture(uris)) } returns completedTask(Result.success(item))
        val result = source.getReceipt("watch").getOrThrow()
        assertArrayEquals(receipt, result)
        assertFalse(receipt === result)
        assertEquals("wear", uris.single().scheme)
        assertEquals("watch", uris.single().host)
        assertEquals(
            WearDataProtocol.receiptPath(WearDataChannel.SYNTHETIC, "watch"), uris.single().path,
        )
    }

    @Test
    fun `missing receipt does not acknowledge a packet`() = runTest {
        every { client.getDataItem(any<Uri>()) } returns completedTask(Result.success(null))
        assertNull(source.getReceipt("watch").getOrThrow())
    }

    @Test
    fun `oversized receipt is rejected`() = runTest {
        every { item.data } returns ByteArray(WearDataProtocol.MAX_RECEIPT_BYTES + 1)
        every { client.getDataItem(any<Uri>()) } returns completedTask(Result.success(item))
        assertTrue(source.getReceipt("watch").isFailure)
    }

    @Test
    fun `failed transport reports sanitized failure`() = runTest {
        every { client.putDataItem(any()) } returns completedTask(
            Result.failure(SecurityException("private details")),
        )
        val result = source.publish("watch", packet)
        assertTrue(result.isFailure)
        assertEquals("Wear data unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `caller cancellation propagates instead of becoming a transport failure`() = runTest {
        every { client.putDataItem(any()) } throws CancellationException()
        var canceled = false
        try {
            source.publish("watch", packet)
        } catch (_: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
    }
}

private fun <T> completedTask(result: Result<T>): Task<T> {
    val task = mockk<Task<T>>()
    every { task.addOnSuccessListener(any()) } answers {
        result.onSuccess { firstArg<OnSuccessListener<T>>().onSuccess(it) }
        task
    }
    every { task.addOnFailureListener(any()) } answers {
        result.onFailure { firstArg<OnFailureListener>().onFailure(it as Exception) }
        task
    }
    every { task.addOnCanceledListener(any()) } returns task
    return task
}
