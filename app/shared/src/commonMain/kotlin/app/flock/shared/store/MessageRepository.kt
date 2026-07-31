package app.flock.shared.store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.flock.shared.util.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MessageRepository(private val database: FlockDatabase) {
    private val queries = database.messageCacheQueries

    fun observeRoomMessages(roomId: String, limit: Long): Flow<List<Message_cache>> {
        return queries.selectRoomMessages(roomId, limit)
            .asFlow()
            .mapToList(Dispatchers.Default)
    }

    suspend fun insertPendingMessage(
        roomId: String,
        clientMessageId: String,
        senderUserId: String,
        kind: String,
        body: ByteArray,
        mediaUploadId: String? = null,
        mediaUrl: String? = null,
        createdAt: Long = currentTimeMillis()
    ) = withContext(Dispatchers.Default) {
        upsertMessageCache(
            roomId = roomId,
            messageId = null,
            clientMessageId = clientMessageId,
            senderUserId = senderUserId,
            kind = kind,
            body = body,
            createdAt = createdAt,
            editedAt = null,
            deletedAt = null,
            deliveryState = "PENDING",
            mediaUploadId = mediaUploadId,
            mediaUrl = mediaUrl
        )
    }

    suspend fun confirmMessageDelivery(
        roomId: String,
        clientMessageId: String,
        messageId: String,
        createdAt: Long
    ) = withContext(Dispatchers.Default) {
        // Existing rows only update delivery fields, so dummy insert values are ignored on conflict.
        upsertMessageCache(
            roomId = roomId,
            messageId = messageId,
            clientMessageId = clientMessageId,
            senderUserId = "",
            kind = "",
            body = ByteArray(0),
            createdAt = createdAt,
            editedAt = null,
            deletedAt = null,
            deliveryState = "DELIVERED",
            mediaUploadId = null,
            mediaUrl = null
        )
    }

    suspend fun insertReceivedMessage(
        roomId: String,
        messageId: String,
        clientMessageId: String,
        senderUserId: String,
        kind: String,
        body: ByteArray,
        createdAt: Long,
        mediaUploadId: String? = null,
        mediaUrl: String? = null
    ) = withContext(Dispatchers.Default) {
        upsertMessageCache(
            roomId = roomId,
            messageId = messageId,
            clientMessageId = clientMessageId,
            senderUserId = senderUserId,
            kind = kind,
            body = body,
            createdAt = createdAt,
            editedAt = null,
            deletedAt = null,
            deliveryState = "DELIVERED",
            mediaUploadId = mediaUploadId,
            mediaUrl = mediaUrl
        )
    }

    suspend fun updateMessageContent(
        roomId: String,
        clientMessageId: String?,
        messageId: String?,
        body: ByteArray,
        editedAt: Long
    ) = withContext(Dispatchers.Default) {
        queries.updateMessageContent(
            body = body,
            edited_at = editedAt,
            room_id = roomId,
            client_message_id = clientMessageId,
            message_id = messageId
        )
    }

    suspend fun markMessageDeleted(
        roomId: String,
        clientMessageId: String,
        deletedAt: Long
    ) = withContext(Dispatchers.Default) {
        queries.markMessageDeleted(
            deleted_at = deletedAt,
            room_id = roomId,
            client_message_id = clientMessageId
        )
    }

    suspend fun updateDeliveryState(
        roomId: String,
        clientMessageId: String,
        state: String
    ) = withContext(Dispatchers.Default) {
        queries.updateDeliveryState(
            delivery_state = state,
            room_id = roomId,
            client_message_id = clientMessageId
        )
    }

    suspend fun getPendingMessages(roomId: String): List<Message_cache> = withContext(Dispatchers.Default) {
        queries.selectPendingMessages(roomId).executeAsList()
    }

    private fun upsertMessageCache(
        roomId: String,
        messageId: String?,
        clientMessageId: String,
        senderUserId: String,
        kind: String,
        body: ByteArray,
        createdAt: Long,
        editedAt: Long?,
        deletedAt: Long?,
        deliveryState: String,
        mediaUploadId: String?,
        mediaUrl: String?
    ) {
        database.transaction {
            queries.insertMessageIfAbsent(
                room_id = roomId,
                message_id = messageId,
                client_message_id = clientMessageId,
                sender_user_id = senderUserId,
                kind = kind,
                body = body,
                created_at = createdAt,
                edited_at = editedAt,
                deleted_at = deletedAt,
                delivery_state = deliveryState,
                media_upload_id = mediaUploadId,
                media_url = mediaUrl
            )
            queries.updateCachedMessageDelivery(
                message_id = messageId,
                delivery_state = deliveryState,
                media_url = mediaUrl,
                room_id = roomId,
                client_message_id = clientMessageId
            )
        }
    }
}
