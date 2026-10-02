package com.x8bit.bitwarden.wear.debug.data

import com.bitwarden.core.data.wear.WearDataChannel
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchDataTransportTest {
    private val client = mockk<DataClient>()
    private val nodes = mockk<NodeClient>()
    private val transport = WatchDataTransportImpl(client, nodes)

    @Test
    fun `local identity comes from the Google Play node client`() = runTest {
        val node = mockk<Node> { every { id } returns "watch" }
        every { nodes.localNode } returns task(Result.success(node))
        assertEquals("watch", transport.getLocalNodeId().getOrThrow())
    }

    @Test
    fun `transport error is sanitized`() = runTest {
        every { nodes.localNode } returns task(Result.failure(SecurityException("private details")))
        val result = transport.getLocalNodeId()
        assertTrue(result.isFailure)
        assertEquals("Wear data unavailable", result.exceptionOrNull()?.message)
    }

    @Test
    fun `unresponsive node lookup times out`() = runTest {
        val task = mockk<Task<Node>>()
        every { task.addOnSuccessListener(any()) } returns task
        every { task.addOnFailureListener(any()) } returns task
        every { task.addOnCanceledListener(any()) } returns task
        every { nodes.localNode } returns task
        assertTrue(transport.getLocalNodeId().isFailure)
    }

    @Test
    fun `caller cancellation propagates`() {
        every { nodes.localNode } throws CancellationException()
        assertThrows(CancellationException::class.java) {
            runTest { transport.getLocalNodeId() }
        }
    }

    @Test
    fun `malformed receipt is rejected before publication`() = runTest {
        assertTrue(transport.publishReceipt(WearDataChannel.PRODUCTION, "watch", byteArrayOf())
            .isFailure)
        verify(exactly = 0) { client.putDataItem(any()) }
    }
}

private fun <T> task(result: Result<T>): Task<T> {
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
