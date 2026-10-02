package com.x8bit.bitwarden.data.wear

import android.content.SharedPreferences
import com.bitwarden.core.data.wear.WearSnapshotFrame
import com.bitwarden.core.data.wear.WearSnapshotHeader
import com.bitwarden.core.data.wear.WearSnapshotProtocol
import com.bitwarden.data.datasource.disk.base.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class WearOutboxDiskSourceTest {
    private val preferences = FakeSharedPreferences()
    private val disk = WearOutboxDiskSourceImpl(preferences, Json)

    @Test
    fun `empty storage and valid revision state round trip`() {
        assertEquals(emptyList<WearOutboxRecord>(), disk.read().getOrThrow())
        val records = listOf(WearOutboxRecord("watch", 1))
        disk.write(records).getOrThrow()
        assertEquals(records, WearOutboxDiskSourceImpl(preferences, Json).read().getOrThrow())
    }

    @Test
    fun `existing unreadable data is not treated as empty storage`() {
        val unreadable = mockk<SharedPreferences> {
            every { getString(any(), null) } returns null
            every { contains(any()) } returns true
        }
        assertTrue(WearOutboxDiskSourceImpl(unreadable, Json).read().isFailure)
    }

    @Test
    fun `malformed oversized and inconsistent records are rejected`() {
        val malformed = mockk<SharedPreferences>()
        val reader = WearOutboxDiskSourceImpl(malformed, Json)
        for (value in listOf(
            "invalid", "x".repeat(MAX_OUTBOX_CHARACTERS + 1),
            """[{"deviceId":"watch","reservedRevision":0}]""",
        )) {
            every { malformed.getString(any(), null) } returns value
            assertTrue(reader.read().isFailure)
        }
        assertTrue(disk.write(listOf(WearOutboxRecord("watch", 0))).isFailure)
        assertTrue(disk.write(List(2) { WearOutboxRecord("watch", 1) }).isFailure)
        assertTrue(disk.write(listOf(WearOutboxRecord("watch", 1, userId = "user"))).isFailure)
    }

    @Test
    fun `pending packet metadata and digest must match the persisted frame`() {
        val header = WearSnapshotHeader(
            "00000000-0000-0000-0000-000000000001", 1, "a".repeat(64),
        )
        val packet = WearSnapshotProtocol.encode(WearSnapshotFrame(header, byteArrayOf(1)))
            .getOrThrow()
        val valid = WearOutboxRecord(
            deviceId = "watch", reservedRevision = 1, userId = "user", lastHeader = header,
            lastDigest = packetDigest(packet),
            pendingPacket = Base64.getEncoder().encodeToString(packet),
        )
        for (invalid in listOf(
            valid.copy(lastDigest = "b".repeat(64)),
            valid.copy(lastHeader = header.copy(revision = 2), reservedRevision = 2),
            valid.copy(pendingPacket = "not base64"),
        )) {
            assertTrue(disk.write(listOf(invalid)).isFailure)
        }
        disk.write(listOf(valid)).getOrThrow()
        assertEquals(listOf(valid), disk.read().getOrThrow())
    }

    @Test
    fun `failed commit never reports successful persistence`() {
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false
        val failing = mockk<SharedPreferences> { every { edit() } returns editor }
        assertTrue(
            WearOutboxDiskSourceImpl(failing, Json).write(listOf(WearOutboxRecord("watch", 1)))
                .isFailure,
        )
    }
}
