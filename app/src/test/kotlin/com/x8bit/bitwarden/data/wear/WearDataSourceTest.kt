package com.x8bit.bitwarden.data.wear

import android.net.Uri
import com.bitwarden.core.data.wear.WearDataProtocol
import com.bitwarden.ui.platform.base.BaseRobolectricTest
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator

class WearDataSourceTest : BaseRobolectricTest() {
    private val client = mockk<DataClient>()
    private val nodeClient = mockk<NodeClient>()
    private val source = WearDataSourceImpl(client, nodeClient)
    private val publicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        .generateKeyPair().public.encoded
    private val info = WearSnapshotInfo("watch", "owner", "a".repeat(64), "tag")

    @Before
    fun setUp() {
        val node = mockk<Node> { every { id } returns "phone" }
        every { nodeClient.localNode } returns completedTask(Result.success(node))
    }

    @Test
    fun `watch keys come from every other node and ignore invalid keys`() = runTest {
        val uris = mutableListOf<Uri>()
        val buffer = buffer(
            keyItem("watch", publicKey),
            keyItem("phone", publicKey),
            keyItem("broken", byteArrayOf(1, 2, 3)),
        )
        every { client.getDataItems(capture(uris)) } returns completedTask(Result.success(buffer))

        val keys = source.getWatchKeys().getOrThrow()

        assertEquals(listOf("watch"), keys.map { it.nodeId })
        assertArrayEquals(publicKey, keys.single().publicKey)
        assertEquals(null, uris.single().host)
        assertEquals(WearDataProtocol.PUBLIC_KEY_PATH, uris.single().path)
        verify { buffer.release() }
    }

    @Test
    fun `snapshots are listed from this phone only`() = runTest {
        val request = PutDataMapRequest.create(WearDataProtocol.snapshotPath("watch")).apply {
            dataMap.putString(WearDataProtocol.TARGET_NODE, "watch")
            dataMap.putString(WearDataProtocol.OWNER, "owner")
            dataMap.putString(WearDataProtocol.RECIPIENT_KEY_ID, "a".repeat(64))
            dataMap.putString(WearDataProtocol.CONTENT_TAG, "tag")
        }.asPutDataRequest()
        val item = mockk<DataItem>()
        every { item.uri } returns Uri.parse("wear://phone${request.uri.path}")
        every { item.data } returns request.data
        every { item.assets } returns emptyMap()
        every { item.freeze() } returns item
        val uris = mutableListOf<Uri>()
        every {
            client.getDataItems(capture(uris), DataClient.FILTER_PREFIX)
        } returns completedTask(Result.success(buffer(item)))

        assertEquals(listOf(info), source.getSnapshots().getOrThrow())
        assertEquals("phone", uris.single().host)
        assertEquals(WearDataProtocol.SNAPSHOT_PATH_PREFIX, uris.single().path)
    }

    @Test
    fun `put publishes an urgent snapshot addressed to the watch`() = runTest {
        val requests = mutableListOf<PutDataRequest>()
        every { client.putDataItem(capture(requests)) } returns
            completedTask(Result.success(mockk()))

        assertTrue(source.putSnapshot(info, byteArrayOf(1, 2, 3)).isSuccess)

        val request = requests.single()
        assertEquals(WearDataProtocol.snapshotPath("watch"), request.uri.path)
        assertTrue(request.isUrgent)
        assertEquals(Asset.createFromBytes(byteArrayOf(1, 2, 3)), request.assets.values.single())
    }

    @Test
    fun `delete removes only this phone's snapshot for the watch`() = runTest {
        val uris = mutableListOf<Uri>()
        every { client.deleteDataItems(capture(uris)) } returns completedTask(Result.success(1))

        assertTrue(source.deleteSnapshot("watch").isSuccess)

        assertEquals("phone", uris.single().host)
        assertEquals(WearDataProtocol.snapshotPath("watch"), uris.single().path)
    }

    @Test
    fun `failed transport reports sanitized failure`() = runTest {
        every { client.putDataItem(any()) } returns completedTask(
            Result.failure(SecurityException("private details")),
        )
        val result = source.putSnapshot(info, byteArrayOf(1))
        assertTrue(result.isFailure)
        assertEquals("Wear data unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `caller cancellation propagates instead of becoming a transport failure`() = runTest {
        every { client.putDataItem(any()) } throws CancellationException()
        var canceled = false
        try {
            source.putSnapshot(info, byteArrayOf(1))
        } catch (_: CancellationException) {
            canceled = true
        }
        assertTrue(canceled)
    }
}

private fun keyItem(node: String, key: ByteArray): DataItem = mockk {
    every { uri } returns Uri.parse("wear://$node${WearDataProtocol.PUBLIC_KEY_PATH}")
    every { data } returns key
}

private fun buffer(vararg items: DataItem): DataItemBuffer = mockk(relaxUnitFun = true) {
    every { iterator() } answers { items.toMutableList().iterator() }
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
