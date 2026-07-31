package app.flock.android

import android.content.Context
import app.flock.ui.ChatChannel
import app.flock.ui.ChatMessage
import app.flock.ui.ChatPerson
import app.flock.ui.ChatRoom
import app.flock.ui.FlockPersistedState
import app.flock.ui.UserProfile
import app.flock.ui.defaultServerHost
import org.json.JSONArray
import org.json.JSONObject

object FlockLocalStore {
    private const val PREFS = "flock_local_state"
    private const val KEY_STATE = "state"

    fun load(context: Context): FlockPersistedState? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null) ?: return null
        return runCatching { JSONObject(json).toState() }.getOrNull()
    }

    fun save(context: Context, state: FlockPersistedState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, state.toJson().toString())
            .apply()
    }

    private fun FlockPersistedState.toJson() = JSONObject().apply {
        put("account", account?.toJson())
        put("serverHost", serverHost)
        put("discoverableByPhone", discoverableByPhone)
        put("discoverableByEmail", discoverableByEmail)
        put("friends", friends.toJsonArray { it.toJson() })
        put("rooms", rooms.toJsonArray { it.toJson() })
        put("messagesByRoom", JSONObject().apply {
            messagesByRoom.forEach { (roomId, messages) ->
                put(roomId, messages.toJsonArray { it.toJson() })
            }
        })
        put("channelsByRoom", JSONObject().apply {
            channelsByRoom.forEach { (roomId, channels) ->
                put(roomId, channels.toJsonArray { it.toJson() })
            }
        })
        put("selectedChannelByRoom", JSONObject().apply {
            selectedChannelByRoom.forEach { (roomId, channelId) -> put(roomId, channelId) }
        })
        put("unreadCountsByChannel", JSONObject().apply {
            unreadCountsByChannel.forEach { (key, value) -> put(key, value) }
        })
        put("likeCounts", JSONObject().apply { likeCounts.forEach { (key, value) -> put(key, value) } })
        put("likedMessageIds", JSONObject().apply { likedMessageIds.forEach { (key, value) -> put(key, value) } })
    }

    private fun JSONObject.toState(): FlockPersistedState {
        val messageRooms = optJSONObject("messagesByRoom") ?: JSONObject()
        val channelRooms = optJSONObject("channelsByRoom") ?: JSONObject()
        return FlockPersistedState(
            account = optJSONObject("account")?.toUserProfile(),
            serverHost = defaultServerHost(),
            discoverableByPhone = optBoolean("discoverableByPhone", false),
            discoverableByEmail = optBoolean("discoverableByEmail", false),
            friends = optJSONArray("friends").toList { it.toChatPerson() },
            rooms = optJSONArray("rooms").toList { it.toChatRoom() },
            messagesByRoom = messageRooms.keys().asSequence().associateWith { roomId ->
                messageRooms.optJSONArray(roomId).toList { it.toChatMessage() }
            },
            channelsByRoom = channelRooms.keys().asSequence().associateWith { roomId ->
                channelRooms.optJSONArray(roomId).toList { it.toChatChannel() }
            },
            selectedChannelByRoom = (optJSONObject("selectedChannelByRoom") ?: JSONObject()).toMapValues { optString(it, "general") },
            unreadCountsByChannel = (optJSONObject("unreadCountsByChannel") ?: JSONObject()).toMapValues { optInt(it, 0) },
            likeCounts = (optJSONObject("likeCounts") ?: JSONObject()).toMapValues { optInt(it, 0) },
            likedMessageIds = (optJSONObject("likedMessageIds") ?: JSONObject()).toMapValues { optBoolean(it, false) },
        )
    }

    private fun UserProfile.toJson() = JSONObject()
        .put("name", name)
        .put("username", username)
        .put("contact", contact)
        .put("userId", userId)

    private fun JSONObject.toUserProfile() = UserProfile(
        name = optString("name"),
        username = optString("username"),
        contact = optString("contact"),
        userId = optString("userId"),
    )

    private fun ChatPerson.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("username", username)

    private fun JSONObject.toChatPerson() = ChatPerson(
        id = optString("id"),
        name = optString("name"),
        username = optString("username"),
    )

    private fun ChatRoom.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("people", people.toJsonArray { it.toJson() })

    private fun JSONObject.toChatRoom() = ChatRoom(
        id = optString("id"),
        name = optString("name"),
        people = optJSONArray("people").toList { it.toChatPerson() },
    )

    private fun ChatChannel.toJson() = JSONObject()
        .put("id", id)
        .put("roomId", roomId)
        .put("name", name)
        .put("emoji", emoji)
        .put("quiet", quiet)
        .put("joined", joined)
        .put("muted", muted)

    private fun JSONObject.toChatChannel() = ChatChannel(
        id = optString("id", "general"),
        roomId = optString("roomId"),
        name = optString("name", "general"),
        emoji = optString("emoji").ifBlank { null },
        quiet = optBoolean("quiet", false),
        joined = optBoolean("joined", true),
        muted = optBoolean("muted", false),
    )

    private fun ChatMessage.toJson() = JSONObject()
        .put("localId", localId)
        .put("sender", sender)
        .put("text", text)
        .put("mine", mine)
        .put("state", state)
        .put("messageId", messageId)
        .put("clientMessageId", clientMessageId)
        .put("createdAtMs", createdAtMs)

    private fun JSONObject.toChatMessage() = ChatMessage(
        localId = optString("localId"),
        sender = optString("sender"),
        text = optString("text"),
        mine = optBoolean("mine", false),
        state = optString("state", "Delivered"),
        messageId = optString("messageId").ifBlank { null },
        clientMessageId = optString("clientMessageId").ifBlank { null },
        createdAtMs = optLong("createdAtMs", 0L),
    )

    private fun <T> List<T>.toJsonArray(map: (T) -> JSONObject) = JSONArray().also { array ->
        forEach { array.put(map(it)) }
    }

    private fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(map) }
    }

    private fun <T> JSONObject.toMapValues(value: JSONObject.(String) -> T): Map<String, T> =
        keys().asSequence().associateWith { key -> value(key) }
}
