package com.x8bit.bitwarden.data.wear

/** Coordinates trusted account events, durable revocation, and encrypted delivery. */
interface WearSyncCoordinator {
    /** Refreshes authorized watches for the specified active unlocked account. */
    suspend fun refresh(userId: String): Result<Unit>

    /**
     * Records withdrawal of every consent for an account before queuing its revocations.
     * Call only for an explicit logout or account removal, never an uninitialized account flow.
     */
    suspend fun revokeAccount(userId: String): Result<Unit>

    /** Repairs pending revocations and retries ciphertext without reading the vault. */
    suspend fun retry(): Result<Unit>
}
