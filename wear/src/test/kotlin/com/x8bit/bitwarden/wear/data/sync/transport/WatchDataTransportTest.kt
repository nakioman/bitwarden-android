package com.x8bit.bitwarden.wear.data.sync.transport

import com.bitwarden.core.data.wear.WearDataProtocol
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchDataTransportTest {
    private val client = mockk<DataClient>()
    private val nodes = mockk<NodeClient>()
    private val transport = WatchDataTransportImpl(client, nodes)

    @Test
    fun `transport error is sanitized`() = runTest {
        every { nodes.localNode } returns task(Result.failure(SecurityException("private details")))
        val result = transport.deletePublicKey()
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
        assertTrue(transport.readSnapshot().isFailure)
    }

    @Test
    fun `caller cancellation propagates`() {
        every { nodes.localNode } throws CancellationException()
        assertThrows(CancellationException::class.java) {
            runTest { transport.readSnapshot() }
        }
    }

    @Test
    fun `bounded snapshot reader keeps complete snapshots and rejects oversized ones`() {
        val bytes = ByteArray(10_000) { it.toByte() }
        assertArrayEquals(bytes, readBoundedSnapshot(bytes.inputStream()))
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedSnapshot(ByteArray(WearDataProtocol.MAX_SNAPSHOT_BYTES + 1).inputStream())
        }
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
