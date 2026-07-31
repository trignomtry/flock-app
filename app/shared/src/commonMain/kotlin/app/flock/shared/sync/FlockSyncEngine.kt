package app.flock.shared.sync

import app.flock.shared.network.FlockClient
import app.flock.shared.network.SessionEvent
import app.flock.shared.protocol.ClientEnvelope
import app.flock.shared.protocol.MessageKind
import app.flock.shared.protocol.Ping
import app.flock.shared.protocol.ProtoUuid
import app.flock.shared.protocol.SendMessage
import app.flock.shared.store.MessageRepository
import app.flock.shared.store.Message_cache
import app.flock.shared.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

sealed interface ConnectionState {
    object Disconnected : ConnectionState
    object Connecting : ConnectionState
    object Connected : ConnectionState
    data class Failed(val error: Throwable) : ConnectionState
}

class FlockSyncEngine(
    private val roomId: String,
    private val authToken: String,
    private val userId: String,
    private val client: FlockClient,
    private val repository: MessageRepository,
    private val scope: CoroutineScope
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val outgoingMessages = MutableSharedFlow<ClientEnvelope>(extraBufferCapacity = 100)
    private var syncJob: kotlinx.coroutines.Job? = null

    fun start() {
        if (syncJob != null) return
        syncJob = scope.launch(Dispatchers.Default) {
            var attempt = 0
            while (coroutineContext.isActive) {
                try {
                    _connectionState.value = ConnectionState.Connecting
                    
                    val outgoingFlow = flow {
                        // 1. Emit all pending messages from database
                        val pending = repository.getPendingMessages(roomId)
                        for (msg in pending) {
                            val kind = when (msg.kind) {
                                "IMAGE" -> MessageKind.MESSAGE_KIND_IMAGE
                                "VIDEO" -> MessageKind.MESSAGE_KIND_VIDEO
                                "SYSTEM" -> MessageKind.MESSAGE_KIND_SYSTEM
                                else -> MessageKind.MESSAGE_KIND_TEXT
                            }
                            val envelope = ClientEnvelope(
                                requestId = ProtoUuid.randomUuid(),
                                sendMessage = SendMessage(
                                    roomId = ProtoUuid.fromString(msg.room_id),
                                    clientMessageId = ProtoUuid.fromString(msg.client_message_id),
                                    kind = kind,
                                    body = msg.body,
                                    mediaUploadId = msg.media_upload_id?.let { ProtoUuid.fromString(it) },
                                    replyToMessageId = null
                                )
                            )
                            emit(envelope)
                        }
                        
                        // 2. Stream new outgoing messages
                        outgoingMessages.collect { emit(it) }
                    }

                    client.connectRoom(roomId, authToken, outgoingFlow).collect { event ->
                        attempt = 0 // Reset attempt counter upon successful stream messages
                        _connectionState.value = ConnectionState.Connected
                        
                        when (event) {
                            SessionEvent.Connected -> {
                                _connectionState.value = ConnectionState.Connected
                            }
                            is SessionEvent.Message -> {
                                handleServerEnvelope(event.envelope)
                            }
                        }
                    }
                } catch (e: Exception) {
                    _connectionState.value = ConnectionState.Failed(e)
                    attempt++
                    val delayMs = calculateBackoff(attempt)
                    delay(delayMs)
                }
            }
        }
        
        // Start keepalive ping loop
        scope.launch(Dispatchers.Default) {
            while (coroutineContext.isActive) {
                delay(20000) // Ping every 20 seconds
                if (_connectionState.value == ConnectionState.Connected) {
                    try {
                        outgoingMessages.emit(
                            ClientEnvelope(
                                requestId = ProtoUuid.randomUuid(),
                                ping = Ping(nonce = currentTimeMillis())
                            )
                        )
                    } catch (e: Exception) {
                        // Ignore ping emit failures, the connection check will cycle it
                    }
                }
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        _connectionState.value = ConnectionState.Disconnected
    }

    suspend fun sendMessage(
        body: ByteArray,
        kind: MessageKind = MessageKind.MESSAGE_KIND_TEXT,
        mediaUploadId: String? = null
    ) {
        val clientMessageId = ProtoUuid.randomUuid()
        val kindStr = when (kind) {
            MessageKind.MESSAGE_KIND_IMAGE -> "IMAGE"
            MessageKind.MESSAGE_KIND_VIDEO -> "VIDEO"
            MessageKind.MESSAGE_KIND_SYSTEM -> "SYSTEM"
            else -> "TEXT"
        }

        // 1. Insert into local SQLite cache first (offline-first)
        repository.insertPendingMessage(
            roomId = roomId,
            clientMessageId = clientMessageId.toUuidString(),
            senderUserId = userId,
            kind = kindStr,
            body = body,
            mediaUploadId = mediaUploadId
        )

        // 2. Queue for sending if connected
        val envelope = ClientEnvelope(
            requestId = ProtoUuid.randomUuid(),
            sendMessage = SendMessage(
                roomId = ProtoUuid.fromString(roomId),
                clientMessageId = clientMessageId,
                kind = kind,
                body = body,
                mediaUploadId = mediaUploadId?.let { ProtoUuid.fromString(it) },
                replyToMessageId = null
            )
        )
        outgoingMessages.emit(envelope)
    }

    private suspend fun handleServerEnvelope(envelope: app.flock.shared.protocol.ServerEnvelope) {
        val msgCreated = envelope.messageCreated
        val msgUpdated = envelope.messageUpdated
        val msgDeleted = envelope.messageDeleted

        if (msgCreated != null) {
            val clientMsgId = msgCreated.clientMessageId?.toUuidString()
            val serverMsgId = msgCreated.messageId.toUuidString()
            val kindStr = when (msgCreated.kind) {
                MessageKind.MESSAGE_KIND_IMAGE -> "IMAGE"
                MessageKind.MESSAGE_KIND_VIDEO -> "VIDEO"
                MessageKind.MESSAGE_KIND_SYSTEM -> "SYSTEM"
                else -> "TEXT"
            }

            if (clientMsgId != null) {
                // If it was sent by us, reconcile
                repository.confirmMessageDelivery(
                    roomId = msgCreated.roomId.toUuidString(),
                    clientMessageId = clientMsgId,
                    messageId = serverMsgId,
                    createdAt = msgCreated.createdAt.value
                )
            } else {
                // Sent by someone else, or doesn't have clientMsgId
                repository.insertReceivedMessage(
                    roomId = msgCreated.roomId.toUuidString(),
                    messageId = serverMsgId,
                    clientMessageId = serverMsgId, // fallback client message id
                    senderUserId = msgCreated.senderUserId.toUuidString(),
                    kind = kindStr,
                    body = msgCreated.body,
                    createdAt = msgCreated.createdAt.value,
                    mediaUploadId = msgCreated.mediaUploadId?.toUuidString()
                )
            }
        } else if (msgUpdated != null) {
            repository.updateMessageContent(
                roomId = msgUpdated.roomId.toUuidString(),
                clientMessageId = null,
                messageId = msgUpdated.messageId.toUuidString(),
                body = msgUpdated.body,
                editedAt = msgUpdated.editedAt.value
            )
        } else if (msgDeleted != null) {
            // Since we only have clientMessageId key for deletion locally:
            // Let's resolve the client message id or use the message id
            val msgId = msgDeleted.messageId.toUuidString()
            repository.markMessageDeleted(
                roomId = msgDeleted.roomId.toUuidString(),
                clientMessageId = msgId, // fallback
                deletedAt = msgDeleted.deletedAt.value
            )
        }
    }

    private fun calculateBackoff(attempt: Int): Long {
        val baseDelay = 1000.0
        val maxDelay = 60000.0
        val factor = 2.0
        val rawDelay = min(maxDelay, baseDelay * factor.pow(attempt - 1))
        val jitter = Random.nextDouble(0.8, 1.2)
        return (rawDelay * jitter).toLong()
    }

    // Helper to map ByteArray message_id to Hex/UUID string format safely
    private fun ByteArray.toUuidString(): String {
        if (this.size == 16) {
            val hex = this.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }
        return this.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }
}
