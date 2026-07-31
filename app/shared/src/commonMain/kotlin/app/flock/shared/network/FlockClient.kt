package app.flock.shared.network

import app.flock.shared.protocol.ClientEnvelope
import app.flock.shared.protocol.ServerEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf

sealed interface SessionEvent {
    object Connected : SessionEvent
    data class Message(val envelope: ServerEnvelope) : SessionEvent
}

@OptIn(ExperimentalSerializationApi::class)
class FlockClient(
    private val baseHost: String,
    private val secure: Boolean = true,
    private val client: HttpClient = defaultHttpClient(),
) {
    private val httpScheme: String = if (secure) "https" else "http"

    suspend fun registerUser(request: RegisterUserRequest): UserSummary =
        client.post("$httpScheme://$baseHost/v1/users/register") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun updatePrivacy(userId: String, request: PrivacyRequest): UserSummary =
        client.put("$httpScheme://$baseHost/v1/users/$userId/privacy") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun searchUsers(query: String, requesterId: String? = null): List<UserSummary> {
        val requester = requesterId?.let { "&viewer_user_id=$it" }.orEmpty()
        return client.get("$httpScheme://$baseHost/v1/users/search?q=${query.urlQuery()}$requester")
            .body<UserSearchResponse>()
            .users
    }

    suspend fun listFriends(userId: String): List<UserSummary> =
        client.get("$httpScheme://$baseHost/v1/users/$userId/friends")
            .body<FriendsResponse>()
            .friends

    suspend fun addFriend(userId: String, friendId: String): FriendActionResponse =
        client.post("$httpScheme://$baseHost/v1/users/$userId/friends/$friendId") {
            contentType(ContentType.Application.Json)
        }.body()

    suspend fun listFriendRequests(userId: String): FriendRequestsResponse =
        client.get("$httpScheme://$baseHost/v1/users/$userId/friend-requests").body()

    suspend fun acceptFriendRequest(userId: String, requesterId: String): FriendActionResponse =
        client.post("$httpScheme://$baseHost/v1/users/$userId/friend-requests/$requesterId/accept") {
            contentType(ContentType.Application.Json)
        }.body()

    suspend fun rejectFriendRequest(userId: String, requesterId: String): FriendActionResponse =
        client.post("$httpScheme://$baseHost/v1/users/$userId/friend-requests/$requesterId/reject") {
            contentType(ContentType.Application.Json)
        }.body()

    suspend fun createRoom(request: CreateRoomRequest): RoomSummary =
        client.post("$httpScheme://$baseHost/v1/rooms") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun updateRoom(roomId: String, request: UpdateRoomRequest): RoomSummary =
        client.put("$httpScheme://$baseHost/v1/rooms/$roomId") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun listUserRooms(userId: String): List<RoomSummary> =
        client.get("$httpScheme://$baseHost/v1/users/$userId/rooms")
            .body<RoomsResponse>()
            .rooms

    suspend fun listRoomChannels(roomId: String, userId: String): List<TopicChannelSummary> =
        client.get("$httpScheme://$baseHost/v1/rooms/$roomId/channels?user_id=${userId.urlQuery()}")
            .body<TopicChannelsResponse>()
            .channels

    suspend fun createRoomChannel(roomId: String, request: CreateTopicChannelRequest): TopicChannelSummary =
        client.post("$httpScheme://$baseHost/v1/rooms/$roomId/channels") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun updateRoomChannelMembership(
        roomId: String,
        channelId: String,
        request: TopicChannelMembershipRequest,
    ): TopicChannelSummary =
        client.put("$httpScheme://$baseHost/v1/rooms/$roomId/channels/$channelId/membership") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun fetchRoomMessages(roomId: String, userId: String, afterMs: Long, channelId: String? = null): List<MessageSummary> {
        val channel = channelId?.let { "&channel_id=${it.urlQuery()}" }.orEmpty()
        return client.get("$httpScheme://$baseHost/v1/rooms/$roomId/messages?user_id=${userId.urlQuery()}&after_ms=$afterMs$channel")
            .body<MessagesResponse>()
            .messages
    }

    suspend fun ackMessage(roomId: String, messageId: String, userId: String, receiptKind: String) {
        client.post("$httpScheme://$baseHost/v1/rooms/$roomId/messages/$messageId/ack") {
            contentType(ContentType.Application.Json)
            setBody(AckReceiptRequest(user_id = userId, receipt_kind = receiptKind))
        }
    }

    suspend fun discoverContacts(userId: String, phones: List<String>, emails: List<String>): List<UserSummary> {
        val canonicalPhones = phones.map { it.canonicalPhone() }.filter { it.isNotBlank() }.distinct()
        val canonicalEmails = emails.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        val contacts = canonicalEmails.map { ContactHashRequest(alias_type = "email", sha256_hex = it.sha256Hex()) } +
            canonicalPhones.map { ContactHashRequest(alias_type = "phone", sha256_hex = it.sha256Hex()) }
        runCatching {
            client.post("$httpScheme://$baseHost/v1/contacts/discover") {
                contentType(ContentType.Application.Json)
                setBody(ContactDiscoveryRequest(viewer_user_id = userId, contacts = contacts.take(2000)))
            }.body<ContactDiscoveryResponse>()
        }.onSuccess { response ->
            return response.matches.map { it.toUserSummary() }.distinctBy { it.user_id }
        }

        val users = mutableListOf<UserSummary>()
        (canonicalEmails + canonicalPhones).distinct().take(80).forEach { query ->
            runCatching {
                searchUsers(query, requesterId = userId)
            }.onSuccess { matches ->
                users += matches
            }
        }
        return users.distinctBy { it.user_id }
    }

    fun connectRoom(
        roomId: String,
        authToken: String,
        outgoing: Flow<ClientEnvelope>,
    ): Flow<SessionEvent> = connect(
        path = "/v1/ws/rooms/$roomId",
        authToken = authToken,
        outgoing = outgoing,
    )

    fun connectUser(
        userId: String,
        authToken: String,
        outgoing: Flow<ClientEnvelope>,
    ): Flow<SessionEvent> = connect(
        path = "/v1/ws/users/$userId",
        authToken = authToken,
        outgoing = outgoing,
    )

    private fun connect(
        path: String,
        authToken: String,
        outgoing: Flow<ClientEnvelope>,
    ): Flow<SessionEvent> = flow {
        val scheme = if (secure) "wss" else "ws"
        client.webSocket(
            urlString = "$scheme://$baseHost$path",
            request = {
                headers.append("Authorization", "Bearer $authToken")
            },
        ) {
            emit(SessionEvent.Connected)
            val sendJob = launch {
                outgoing.collect { envelope ->
                    val bytes = ProtoBuf.encodeToByteArray(envelope)
                    send(Frame.Binary(true, bytes))
                }
            }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Binary) {
                        val envelope = ProtoBuf.decodeFromByteArray<ServerEnvelope>(frame.readBytes())
                        emit(SessionEvent.Message(envelope))
                    }
                }
            } finally {
                sendJob.cancel()
            }
        }
    }
}

@Serializable
data class RegisterUserRequest(
    val user_id: String? = null,
    val display_name: String,
    val username: String,
    val phone: String? = null,
    val email: String? = null,
)

@Serializable
data class PrivacyRequest(
    val discoverable_by_email: Boolean,
    val discoverable_by_phone: Boolean,
)

@Serializable
data class UserSearchResponse(
    val users: List<UserSummary> = emptyList(),
)

@Serializable
data class FriendsResponse(
    val friends: List<UserSummary> = emptyList(),
)

@Serializable
data class FriendRequestsResponse(
    val incoming: List<FriendRequestSummary> = emptyList(),
    val outgoing: List<FriendRequestSummary> = emptyList(),
)

@Serializable
data class FriendRequestSummary(
    val requester_user_id: String,
    val recipient_user_id: String,
    val status: String,
    val requester: UserSummary,
    val recipient: UserSummary,
)

@Serializable
data class FriendActionResponse(
    val friend: UserSummary,
    val request: FriendRequestSummary,
)

@Serializable
data class UserSummary(
    val user_id: String,
    val display_name: String,
    val username: String = "",
    val phone: String? = null,
    val email: String? = null,
    val discoverable_by_email: Boolean = false,
    val discoverable_by_phone: Boolean = false,
)

@Serializable
data class ContactDiscoveryRequest(
    val viewer_user_id: String,
    val contacts: List<ContactHashRequest> = emptyList(),
)

@Serializable
data class ContactHashRequest(
    val alias_type: String,
    val sha256_hex: String,
)

@Serializable
data class ContactDiscoveryResponse(
    val matches: List<ContactMatchSummary> = emptyList(),
)

@Serializable
data class ContactMatchSummary(
    val user_id: String,
    val display_name: String,
    val username: String? = null,
)

private fun ContactMatchSummary.toUserSummary(): UserSummary =
    UserSummary(
        user_id = user_id,
        display_name = display_name,
        username = username.orEmpty(),
    )

@Serializable
data class CreateRoomRequest(
    val creator_user_id: String,
    val room_type: String,
    val name: String? = null,
    val member_user_ids: List<String>,
)

@Serializable
data class UpdateRoomRequest(
    val requester_user_id: String,
    val name: String? = null,
    val member_user_ids: List<String>,
)

@Serializable
data class RoomSummary(
    val room_id: String,
    val room_type: String = "group",
    val name: String? = null,
    val members: List<UserSummary> = emptyList(),
    val created_at: String = "",
)

@Serializable
data class RoomsResponse(
    val rooms: List<RoomSummary> = emptyList(),
)

@Serializable
data class TopicChannelsResponse(
    val channels: List<TopicChannelSummary> = emptyList(),
)

@Serializable
data class TopicChannelSummary(
    val channel_id: String,
    val room_id: String = "",
    val name: String,
    val emoji: String? = null,
    val quiet: Boolean = false,
    val is_default: Boolean = false,
    @SerialName("is_member")
    val joined: Boolean = true,
    val muted: Boolean = false,
    val created_at: String = "",
)

@Serializable
data class CreateTopicChannelRequest(
    val creator_user_id: String,
    val name: String,
    val emoji: String? = null,
    val quiet: Boolean = false,
)

@Serializable
data class TopicChannelMembershipRequest(
    val user_id: String,
    val joined: Boolean? = null,
    val muted: Boolean? = null,
)

@Serializable
data class MessagesResponse(
    val messages: List<MessageSummary> = emptyList(),
)

@Serializable
data class MessageSummary(
    val message_id: String? = null,
    val channel_id: String? = null,
    val client_message_id: String? = null,
    val sender_user_id: String = "",
    val kind: String = "TEXT",
    val body: String? = null,
    val text: String? = null,
    val created_at_ms: Long? = null,
    val receipt_state: String? = null,
)

@Serializable
data class AckReceiptRequest(
    val user_id: String,
    val receipt_kind: String,
)

private fun String.urlQuery(): String =
    buildString {
        this@urlQuery.forEach { char ->
            when {
                char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' || char == '~' -> append(char)
                char == ' ' -> append("%20")
                else -> {
                    val bytes = char.toString().encodeToByteArray()
                    bytes.forEach { byte ->
                        append('%')
                        append(byte.toUByte().toString(16).padStart(2, '0').uppercase())
                    }
                }
            }
        }
    }

fun defaultHttpClient(): HttpClient =
    HttpClient {
        expectSuccess = true
        install(WebSockets)
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }
    }

private fun String.canonicalPhone(): String = filter { it.isDigit() || it == '+' }

private fun String.sha256Hex(): String {
    val bytes = encodeToByteArray()
    val hash = Sha256.digest(bytes)
    return hash.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}

private object Sha256 {
    private val k = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    fun digest(input: ByteArray): ByteArray {
        var h0 = 0x6a09e667
        var h1 = -0x4498517b
        var h2 = 0x3c6ef372
        var h3 = -0x5ab00ac6
        var h4 = 0x510e527f
        var h5 = -0x64fa9774
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19
        val bitLength = input.size.toLong() * 8L
        val paddedLength = (((input.size + 9 + 63) / 64) * 64)
        val padded = ByteArray(paddedLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.lastIndex - i] = (bitLength ushr (8 * i)).toByte()
        }
        val w = IntArray(64)
        for (offset in padded.indices step 64) {
            for (i in 0 until 16) {
                val j = offset + i * 4
                w[i] = ((padded[j].toInt() and 0xff) shl 24) or
                    ((padded[j + 1].toInt() and 0xff) shl 16) or
                    ((padded[j + 2].toInt() and 0xff) shl 8) or
                    (padded[j + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + k[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
        }
        return intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).flatMap { value ->
            listOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            )
        }.toByteArray()
    }
}
