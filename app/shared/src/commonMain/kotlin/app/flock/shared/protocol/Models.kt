@file:OptIn(ExperimentalSerializationApi::class)

package app.flock.shared.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoOneOf

@Serializable
data class ProtoUuid(
    @ProtoNumber(1) val value: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ProtoUuid
        return value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }

    fun toUuidString(): String {
        require(value.size == 16) { "UUID bytes must be exactly 16 bytes" }
        val hex = value.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    companion object {
        fun randomUuid(): ProtoUuid {
            val bytes = ByteArray(16)
            kotlin.random.Random.nextBytes(bytes)
            bytes[6] = (bytes[6].toInt() and 0x0F or 0x40).toByte()
            bytes[8] = (bytes[8].toInt() and 0x3F or 0x80).toByte()
            return ProtoUuid(bytes)
        }

        fun fromString(uuidString: String): ProtoUuid {
            val clean = uuidString.replace("-", "")
            require(clean.length == 32) { "Invalid UUID string: $uuidString" }
            val bytes = ByteArray(16)
            for (i in 0 until 16) {
                bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return ProtoUuid(bytes)
        }
    }
}

@Serializable
data class TimestampMillis(
    @ProtoNumber(1) val value: Long
)

@Serializable
enum class MessageKind {
    @ProtoNumber(0) MESSAGE_KIND_UNSPECIFIED,
    @ProtoNumber(1) MESSAGE_KIND_TEXT,
    @ProtoNumber(2) MESSAGE_KIND_IMAGE,
    @ProtoNumber(3) MESSAGE_KIND_VIDEO,
    @ProtoNumber(4) MESSAGE_KIND_SYSTEM
}

@Serializable
data class ClientEnvelope(
    @ProtoNumber(1) val requestId: ProtoUuid,
    @ProtoOneOf val payload: ClientPayload? = null,
) {
    constructor(
        requestId: ProtoUuid,
        sendMessage: SendMessage? = null,
        joinRoom: JoinRoom? = null,
        leaveRoom: LeaveRoom? = null,
        typing: Typing? = null,
        ack: Ack? = null,
        ping: Ping? = null,
    ) : this(requestId, clientPayloadOf(sendMessage, joinRoom, leaveRoom, typing, ack, ping))

    val sendMessage: SendMessage?
        get() = (payload as? ClientPayload.SendMessagePayload)?.sendMessage
    val joinRoom: JoinRoom?
        get() = (payload as? ClientPayload.JoinRoomPayload)?.joinRoom
    val leaveRoom: LeaveRoom?
        get() = (payload as? ClientPayload.LeaveRoomPayload)?.leaveRoom
    val typing: Typing?
        get() = (payload as? ClientPayload.TypingPayload)?.typing
    val ack: Ack?
        get() = (payload as? ClientPayload.AckPayload)?.ack
    val ping: Ping?
        get() = (payload as? ClientPayload.PingPayload)?.ping
}

@Serializable
sealed interface ClientPayload {
    @Serializable
    data class SendMessagePayload(@ProtoNumber(2) val sendMessage: SendMessage) : ClientPayload

    @Serializable
    data class JoinRoomPayload(@ProtoNumber(3) val joinRoom: JoinRoom) : ClientPayload

    @Serializable
    data class LeaveRoomPayload(@ProtoNumber(4) val leaveRoom: LeaveRoom) : ClientPayload

    @Serializable
    data class TypingPayload(@ProtoNumber(5) val typing: Typing) : ClientPayload

    @Serializable
    data class AckPayload(@ProtoNumber(6) val ack: Ack) : ClientPayload

    @Serializable
    data class PingPayload(@ProtoNumber(7) val ping: Ping) : ClientPayload
}

private fun clientPayloadOf(
    sendMessage: SendMessage?,
    joinRoom: JoinRoom?,
    leaveRoom: LeaveRoom?,
    typing: Typing?,
    ack: Ack?,
    ping: Ping?,
): ClientPayload? {
    val payloads = listOfNotNull(
        sendMessage?.let(ClientPayload::SendMessagePayload),
        joinRoom?.let(ClientPayload::JoinRoomPayload),
        leaveRoom?.let(ClientPayload::LeaveRoomPayload),
        typing?.let(ClientPayload::TypingPayload),
        ack?.let(ClientPayload::AckPayload),
        ping?.let(ClientPayload::PingPayload),
    )
    require(payloads.size <= 1) { "ClientEnvelope can contain at most one payload" }
    return payloads.firstOrNull()
}

@Serializable
data class ServerEnvelope(
    @ProtoNumber(1) val eventId: ProtoUuid,
    @ProtoNumber(2) val serverTime: TimestampMillis,
    @ProtoOneOf val payload: ServerPayload? = null,
) {
    val messageCreated: MessageCreated?
        get() = (payload as? ServerPayload.MessageCreatedPayload)?.messageCreated
    val messageUpdated: MessageUpdated?
        get() = (payload as? ServerPayload.MessageUpdatedPayload)?.messageUpdated
    val messageDeleted: MessageDeleted?
        get() = (payload as? ServerPayload.MessageDeletedPayload)?.messageDeleted
    val presenceChanged: PresenceChanged?
        get() = (payload as? ServerPayload.PresenceChangedPayload)?.presenceChanged
    val typing: Typing?
        get() = (payload as? ServerPayload.TypingPayload)?.typing
    val mediaReady: MediaReady?
        get() = (payload as? ServerPayload.MediaReadyPayload)?.mediaReady
    val error: ErrorEvent?
        get() = (payload as? ServerPayload.ErrorPayload)?.error
    val pong: Pong?
        get() = (payload as? ServerPayload.PongPayload)?.pong
    val roomCreated: RoomCreated?
        get() = (payload as? ServerPayload.RoomCreatedPayload)?.roomCreated
}

@Serializable
sealed interface ServerPayload {
    @Serializable
    data class MessageCreatedPayload(@ProtoNumber(3) val messageCreated: MessageCreated) : ServerPayload

    @Serializable
    data class MessageUpdatedPayload(@ProtoNumber(4) val messageUpdated: MessageUpdated) : ServerPayload

    @Serializable
    data class MessageDeletedPayload(@ProtoNumber(5) val messageDeleted: MessageDeleted) : ServerPayload

    @Serializable
    data class PresenceChangedPayload(@ProtoNumber(6) val presenceChanged: PresenceChanged) : ServerPayload

    @Serializable
    data class TypingPayload(@ProtoNumber(7) val typing: Typing) : ServerPayload

    @Serializable
    data class MediaReadyPayload(@ProtoNumber(8) val mediaReady: MediaReady) : ServerPayload

    @Serializable
    data class ErrorPayload(@ProtoNumber(9) val error: ErrorEvent) : ServerPayload

    @Serializable
    data class PongPayload(@ProtoNumber(10) val pong: Pong) : ServerPayload

    @Serializable
    data class RoomCreatedPayload(@ProtoNumber(11) val roomCreated: RoomCreated) : ServerPayload
}

@Serializable
data class SendMessage(
    @ProtoNumber(1) val roomId: ProtoUuid,
    @ProtoNumber(2) val clientMessageId: ProtoUuid,
    @ProtoNumber(3) val kind: MessageKind,
    @ProtoNumber(4) val body: ByteArray,
    @ProtoNumber(5) val mediaUploadId: ProtoUuid? = null,
    @ProtoNumber(6) val replyToMessageId: ByteArray? = null,
    @ProtoNumber(7) val channelId: ProtoUuid? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as SendMessage
        if (roomId != other.roomId) return false
        if (clientMessageId != other.clientMessageId) return false
        if (kind != other.kind) return false
        if (!body.contentEquals(other.body)) return false
        if (mediaUploadId != other.mediaUploadId) return false
        if (replyToMessageId != null) {
            if (other.replyToMessageId == null) return false
            if (!replyToMessageId.contentEquals(other.replyToMessageId)) return false
        } else if (other.replyToMessageId != null) return false
        if (channelId != other.channelId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + clientMessageId.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + (mediaUploadId?.hashCode() ?: 0)
        result = 31 * result + (replyToMessageId?.contentHashCode() ?: 0)
        result = 31 * result + (channelId?.hashCode() ?: 0)
        return result
    }
}

@Serializable
data class MessageCreated(
    @ProtoNumber(1) val roomId: ProtoUuid,
    @ProtoNumber(2) val messageId: ByteArray,
    @ProtoNumber(3) val senderUserId: ProtoUuid,
    @ProtoNumber(4) val kind: MessageKind,
    @ProtoNumber(5) val body: ByteArray,
    @ProtoNumber(6) val createdAt: TimestampMillis,
    @ProtoNumber(7) val clientMessageId: ProtoUuid? = null,
    @ProtoNumber(8) val mediaUploadId: ProtoUuid? = null,
    @ProtoNumber(9) val channelId: ProtoUuid? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as MessageCreated
        if (roomId != other.roomId) return false
        if (!messageId.contentEquals(other.messageId)) return false
        if (senderUserId != other.senderUserId) return false
        if (kind != other.kind) return false
        if (!body.contentEquals(other.body)) return false
        if (createdAt != other.createdAt) return false
        if (clientMessageId != other.clientMessageId) return false
        if (mediaUploadId != other.mediaUploadId) return false
        if (channelId != other.channelId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + messageId.contentHashCode()
        result = 31 * result + senderUserId.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (clientMessageId?.hashCode() ?: 0)
        result = 31 * result + (mediaUploadId?.hashCode() ?: 0)
        result = 31 * result + (channelId?.hashCode() ?: 0)
        return result
    }
}

@Serializable
data class MessageUpdated(
    @ProtoNumber(1) val roomId: ProtoUuid,
    @ProtoNumber(2) val messageId: ByteArray,
    @ProtoNumber(3) val body: ByteArray,
    @ProtoNumber(4) val editedAt: TimestampMillis,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as MessageUpdated
        if (roomId != other.roomId) return false
        if (!messageId.contentEquals(other.messageId)) return false
        if (!body.contentEquals(other.body)) return false
        if (editedAt != other.editedAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + messageId.contentHashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + editedAt.hashCode()
        return result
    }
}

@Serializable
data class MessageDeleted(
    @ProtoNumber(1) val roomId: ProtoUuid,
    @ProtoNumber(2) val messageId: ByteArray,
    @ProtoNumber(3) val deletedAt: TimestampMillis,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as MessageDeleted
        if (roomId != other.roomId) return false
        if (!messageId.contentEquals(other.messageId)) return false
        if (deletedAt != other.deletedAt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + messageId.contentHashCode()
        result = 31 * result + deletedAt.hashCode()
        return result
    }
}

@Serializable
data class MediaReady(
    @ProtoNumber(1) val roomId: ProtoUuid,
    @ProtoNumber(2) val uploadId: ProtoUuid,
    @ProtoNumber(3) val messageId: ByteArray,
    @ProtoNumber(4) val publicUrl: String,
    @ProtoNumber(5) val manifestJson: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as MediaReady
        if (roomId != other.roomId) return false
        if (uploadId != other.uploadId) return false
        if (!messageId.contentEquals(other.messageId)) return false
        if (publicUrl != other.publicUrl) return false
        if (manifestJson != other.manifestJson) return false
        return true
    }

    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + uploadId.hashCode()
        result = 31 * result + messageId.contentHashCode()
        result = 31 * result + publicUrl.hashCode()
        result = 31 * result + manifestJson.hashCode()
        return result
    }
}

@Serializable
data class JoinRoom(
    @ProtoNumber(1) val roomId: ProtoUuid,
)

@Serializable
data class LeaveRoom(
    @ProtoNumber(1) val roomId: ProtoUuid,
)

@Serializable
data class Typing(
    @ProtoNumber(1) val roomId: ProtoUuid,
    @ProtoNumber(2) val userId: ProtoUuid,
    @ProtoNumber(3) val isTyping: Boolean,
)

@Serializable
data class Ack(
    @ProtoNumber(1) val roomId: ProtoUuid,
    @ProtoNumber(2) val messageId: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as Ack
        if (roomId != other.roomId) return false
        if (!messageId.contentEquals(other.messageId)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = roomId.hashCode()
        result = 31 * result + messageId.contentHashCode()
        return result
    }
}

@Serializable
data class PresenceChanged(
    @ProtoNumber(1) val roomId: ProtoUuid,
    @ProtoNumber(2) val userId: ProtoUuid,
    @ProtoNumber(3) val online: Boolean,
)

@Serializable
data class Ping(
    @ProtoNumber(1) val nonce: Long,
)

@Serializable
data class Pong(
    @ProtoNumber(1) val nonce: Long,
)

@Serializable
data class RoomCreated(
    @ProtoNumber(1) val roomId: ProtoUuid,
)

@Serializable
data class ErrorEvent(
    @ProtoNumber(1) val code: String,
    @ProtoNumber(2) val message: String,
    @ProtoNumber(3) val retryable: Boolean,
)

@Serializable
data class ContactDiscoveryRequest(
    @ProtoNumber(1) val contacts: List<ContactHash>
)

@Serializable
data class ContactHash(
    @ProtoNumber(1) val aliasType: String,
    @ProtoNumber(2) val sha256: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ContactHash
        if (aliasType != other.aliasType) return false
        if (!sha256.contentEquals(other.sha256)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = aliasType.hashCode()
        result = 31 * result + sha256.contentHashCode()
        return result
    }
}

@Serializable
data class ContactDiscoveryResponse(
    @ProtoNumber(1) val matches: List<ContactMatch>
)

@Serializable
data class ContactMatch(
    @ProtoNumber(1) val userId: ProtoUuid,
    @ProtoNumber(2) val displayName: String,
    @ProtoNumber(3) val username: String? = null,
    @ProtoNumber(4) val matchedAliasTypes: List<String>
)
