package app.flock.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.flock.shared.network.FlockClient
import app.flock.shared.network.FriendRequestSummary
import app.flock.shared.network.MessageSummary
import app.flock.shared.network.CreateTopicChannelRequest
import app.flock.shared.network.CreateRoomRequest
import app.flock.shared.network.PrivacyRequest
import app.flock.shared.network.RegisterUserRequest
import app.flock.shared.network.RoomSummary
import app.flock.shared.network.SessionEvent
import app.flock.shared.network.TopicChannelMembershipRequest
import app.flock.shared.network.TopicChannelSummary
import app.flock.shared.network.UpdateRoomRequest
import app.flock.shared.network.UserSummary
import app.flock.shared.protocol.ClientEnvelope
import app.flock.shared.protocol.JoinRoom
import app.flock.shared.protocol.MessageKind
import app.flock.shared.protocol.ProtoUuid
import app.flock.shared.protocol.SendMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PickedPhoto(
    val name: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedPhoto) return false
        return name == other.name && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}

private enum class AccountMode {
    Register,
    SignIn,
}

private enum class Status {
    SignedOut,
    Connecting,
    Online,
    Offline,
}

@Serializable
data class UserProfile(
    val name: String,
    val username: String,
    val contact: String,
    val userId: String,
)

@Serializable
data class ChatRoom(
    val id: String,
    val name: String,
    val people: List<ChatPerson>,
)

@Serializable
data class ChatChannel(
    val id: String,
    val roomId: String,
    val name: String,
    val emoji: String? = null,
    val quiet: Boolean = false,
    val joined: Boolean = true,
    val muted: Boolean = false,
) {
    val isGeneral: Boolean get() = id == GeneralChannelId
}

@Serializable
data class ChatPerson(
    val id: String,
    val name: String,
    val username: String,
)

@Serializable
data class ChatMessage(
    val localId: String,
    val sender: String,
    val text: String,
    val mine: Boolean,
    val state: String,
    val messageId: String? = null,
    val clientMessageId: String? = null,
    val createdAtMs: Long = 0L,
    @Transient
    val photo: PickedPhoto? = null,
)

@Serializable
data class ContactCandidate(
    val displayName: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
)

@Serializable
data class FlockPersistedState(
    val account: UserProfile?,
    val serverHost: String,
    val discoverableByPhone: Boolean,
    val discoverableByEmail: Boolean,
    val friends: List<ChatPerson>,
    val rooms: List<ChatRoom>,
    val messagesByRoom: Map<String, List<ChatMessage>>,
    val channelsByRoom: Map<String, List<ChatChannel>> = emptyMap(),
    val selectedChannelByRoom: Map<String, String> = emptyMap(),
    val unreadCountsByChannel: Map<String, Int> = emptyMap(),
    val likeCounts: Map<String, Int>,
    val likedMessageIds: Map<String, Boolean>,
)

private enum class HomeTab {
    Chats,
    Friends,
}

private const val MaxInlineMediaBytes = 1024 * 1024
private const val GeneralChannelId = "00000000-0000-0000-0000-000000000000"
private val CommonEmojiPickerList = listOf(
    "😀", "😃", "😄", "😁", "😆", "😂", "🤣", "😊", "😇", "🙂",
    "🙃", "😉", "😍", "😘", "😎", "🤩", "🥳", "😋", "🤔", "🤗",
    "🤯", "😴", "😤", "😭", "😡", "👍", "👎", "👏", "🙌", "🙏",
    "💪", "👀", "🧠", "🫶", "❤️", "🧡", "💛", "💚", "💙", "💜",
    "🖤", "🤍", "💯", "🔥", "✨", "⭐", "🌟", "⚡", "💥", "🎉",
    "🎊", "🎯", "🏆", "🥇", "🚀", "🛸", "🌈", "☀️", "🌙", "☁️",
    "🌧️", "❄️", "🌊", "🍕", "🍔", "🌮", "🍣", "🍩", "🍪", "🍿",
    "☕", "🍵", "🍻", "⚽", "🏀", "🏈", "⚾", "🎾", "🎮", "🎲",
    "🎸", "🎧", "🎬", "📚", "💡", "🔔", "📌", "✅", "❌", "❗",
    "❓", "💬", "📷", "🎨", "🧩", "🛠️", "✈️", "🚗", "🏖️", "🏔️",
)
private val FullEmojiPickerList = CommonEmojiPickerList + listOf(
    "😅", "🥰", "😜", "🤪", "😐", "😬", "🙄", "😮", "😱", "🥶",
    "🥵", "🤠", "🥸", "😈", "👻", "🤖", "💀", "👋", "🤚", "👌",
    "🤌", "🤞", "✌️", "🤟", "🤘", "👊", "✊", "💅", "🦾", "🦿",
    "👑", "💍", "🎒", "🕶️", "🐶", "🐱", "🐼", "🦊", "🐝", "🦋",
    "🌸", "🌻", "🌵", "🌲", "🍀", "🍎", "🍓", "🍉", "🥑", "🥨",
    "🍟", "🍜", "🍰", "🍭", "🧃", "🥤", "🍷", "🥂", "🏄", "🚴",
    "🏃", "🏋️", "🧘", "🎤", "🎹", "🥁", "🎻", "🎺", "🪩", "🎭",
    "🗺️", "⏰", "📅", "📎", "✏️", "🔒", "🔑", "🧪", "🩺", "💻",
    "📱", "⌚", "🛰️", "🏠", "🏙️", "🌋", "🗽", "🛶", "🚆", "🚲",
)

private object LocalFlockState {
    var account: UserProfile? = null
    var status: Status = Status.SignedOut
    var serverHost: String = defaultServerHost()
    var connectionProblem: String? = null
    var openRoomId: String? = null
    var showNewChat: Boolean = false
    var showAccount: Boolean = false
    var homeTab: HomeTab = HomeTab.Chats
    var messageCounter: Int = 0
    var discoverableByPhone: Boolean = false
    var discoverableByEmail: Boolean = false
    val friends = mutableStateListOf<ChatPerson>()
    val rooms = mutableStateListOf<ChatRoom>()
    val messagesByRoom = mutableStateMapOf<String, SnapshotStateList<ChatMessage>>()
    val channelsByRoom = mutableStateMapOf<String, SnapshotStateList<ChatChannel>>()
    val selectedChannelByRoom = mutableStateMapOf<String, String>()
    val unreadCountsByChannel = mutableStateMapOf<String, Int>()
    val likeCounts = mutableStateMapOf<String, Int>()
    val likedMessageIds = mutableStateMapOf<String, Boolean>()
}

private val appLightColors = lightColorScheme(
    primary = Color(0xFF0E6B57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F2E9),
    onPrimaryContainer = Color(0xFF063B30),
    secondary = Color(0xFF785B12),
    secondaryContainer = Color(0xFFFFE4A3),
    tertiary = Color(0xFF9A3F5F),
    background = Color(0xFFFAFBF8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7ECE7),
)

private val appDarkColors = darkColorScheme(
    primary = Color(0xFF7DDAC1),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFFA0F2D7),
    secondary = Color(0xFFE9C15E),
    onSecondary = Color(0xFF3E2E00),
    secondaryContainer = Color(0xFF594400),
    onSecondaryContainer = Color(0xFFFFE08B),
    tertiary = Color(0xFFFFB0C8),
    onTertiary = Color(0xFF5E1130),
    tertiaryContainer = Color(0xFF7A2946),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF0F1412),
    onBackground = Color(0xFFE0E5E1),
    surface = Color(0xFF151A18),
    onSurface = Color(0xFFE0E5E1),
    surfaceVariant = Color(0xFF29322F),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF89938F),
)

@Composable
fun FlockApp(
    pickedPhoto: PickedPhoto? = null,
    onPickedPhotoConsumed: () -> Unit = {},
    onPickPhoto: () -> Unit = {},
    onNotifyMessage: (String, String) -> Unit = { _, _ -> },
    restoreState: () -> FlockPersistedState? = { null },
    onStateChanged: (FlockPersistedState) -> Unit = {},
    onContactsRequested: suspend () -> List<ContactCandidate> = { emptyList() },
) {
    remember {
        restoreState()?.let { restored ->
            LocalFlockState.account = restored.account
            LocalFlockState.serverHost = restored.serverHost
            LocalFlockState.discoverableByPhone = restored.discoverableByPhone
            LocalFlockState.discoverableByEmail = restored.discoverableByEmail
            LocalFlockState.friends.replacePeopleWith(restored.friends)
            LocalFlockState.rooms.replaceRoomsWith(restored.rooms)
            LocalFlockState.messagesByRoom.clear()
            restored.messagesByRoom.forEach { (roomId, messages) ->
                LocalFlockState.messagesByRoom[roomId] = mutableStateListOf<ChatMessage>().apply {
                    addAll(messages.distinctByMessageIdentity())
                }
            }
            LocalFlockState.channelsByRoom.clear()
            restored.channelsByRoom.forEach { (roomId, channels) ->
                LocalFlockState.channelsByRoom[roomId] = mutableStateListOf<ChatChannel>().apply {
                    addAll(channels.withGeneral(roomId))
                }
            }
            LocalFlockState.selectedChannelByRoom.clear()
            LocalFlockState.selectedChannelByRoom.putAll(restored.selectedChannelByRoom)
            LocalFlockState.unreadCountsByChannel.clear()
            LocalFlockState.unreadCountsByChannel.putAll(restored.unreadCountsByChannel)
            LocalFlockState.likeCounts.clear()
            LocalFlockState.likeCounts.putAll(restored.likeCounts)
            LocalFlockState.likedMessageIds.clear()
            LocalFlockState.likedMessageIds.putAll(restored.likedMessageIds)
            LocalFlockState.status = if (restored.account == null) Status.SignedOut else Status.Offline
        }
        true
    }
    var account by remember { mutableStateOf(LocalFlockState.account) }
    var status by remember { mutableStateOf(LocalFlockState.status) }
    var serverHost by remember { mutableStateOf(LocalFlockState.serverHost) }
    var connectionProblem by remember { mutableStateOf(LocalFlockState.connectionProblem) }
    var openRoomId by remember { mutableStateOf(LocalFlockState.openRoomId) }
    var showNewChat by remember { mutableStateOf(LocalFlockState.showNewChat) }
    var showAccount by remember { mutableStateOf(LocalFlockState.showAccount) }
    var showRoomSettings by remember { mutableStateOf(false) }
    var homeTab by remember { mutableStateOf(LocalFlockState.homeTab) }
    var messageCounter by remember { mutableStateOf(LocalFlockState.messageCounter) }
    var discoverableByPhone by remember { mutableStateOf(LocalFlockState.discoverableByPhone) }
    var discoverableByEmail by remember { mutableStateOf(LocalFlockState.discoverableByEmail) }

    val friends = remember { LocalFlockState.friends }
    val rooms = remember { LocalFlockState.rooms }
    val messagesByRoom = remember { LocalFlockState.messagesByRoom }
    val channelsByRoom = remember { LocalFlockState.channelsByRoom }
    val selectedChannelByRoom = remember { LocalFlockState.selectedChannelByRoom }
    val unreadCountsByChannel = remember { LocalFlockState.unreadCountsByChannel }
    val likeCounts = remember { LocalFlockState.likeCounts }
    val likedMessageIds = remember { LocalFlockState.likedMessageIds }
    val outgoing = remember { MutableSharedFlow<ClientEnvelope>(extraBufferCapacity = 64) }
    val scope = rememberCoroutineScope()
    val openRoom = openRoomId?.let { id -> rooms.firstOrNull { it.id == id } }

    LaunchedEffect(account, status, serverHost, connectionProblem, openRoomId, showNewChat, showAccount, homeTab, messageCounter, discoverableByPhone, discoverableByEmail) {
        LocalFlockState.account = account
        LocalFlockState.status = status
        LocalFlockState.serverHost = serverHost
        LocalFlockState.connectionProblem = connectionProblem
        LocalFlockState.openRoomId = openRoomId
        LocalFlockState.showNewChat = showNewChat
        LocalFlockState.showAccount = showAccount
        LocalFlockState.homeTab = homeTab
        LocalFlockState.messageCounter = messageCounter
        LocalFlockState.discoverableByPhone = discoverableByPhone
        LocalFlockState.discoverableByEmail = discoverableByEmail
        onStateChanged(
            FlockPersistedState(
                account = account,
                serverHost = serverHost,
                discoverableByPhone = discoverableByPhone,
                discoverableByEmail = discoverableByEmail,
                friends = friends.toList(),
                rooms = rooms.toList(),
                messagesByRoom = messagesByRoom.mapValues { it.value.toList() },
                channelsByRoom = channelsByRoom.mapValues { it.value.toList() },
                selectedChannelByRoom = selectedChannelByRoom.toMap(),
                unreadCountsByChannel = unreadCountsByChannel.toMap(),
                likeCounts = likeCounts.toMap(),
                likedMessageIds = likedMessageIds.toMap(),
            ),
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            onStateChanged(
                FlockPersistedState(
                    account = account,
                    serverHost = serverHost,
                    discoverableByPhone = discoverableByPhone,
                    discoverableByEmail = discoverableByEmail,
                    friends = friends.toList(),
                    rooms = rooms.toList(),
                    messagesByRoom = messagesByRoom.mapValues { it.value.toList() },
                    channelsByRoom = channelsByRoom.mapValues { it.value.toList() },
                    selectedChannelByRoom = selectedChannelByRoom.toMap(),
                    unreadCountsByChannel = unreadCountsByChannel.toMap(),
                    likeCounts = likeCounts.toMap(),
                    likedMessageIds = likedMessageIds.toMap(),
                ),
            )
        }
    }

    LaunchedEffect(account, serverHost) {
        val profile = account ?: return@LaunchedEffect
        refreshFriendsAndRooms(
            client = FlockClient(baseHost = serverHost.trim(), secure = false),
            profile = profile,
            friends = friends,
            rooms = rooms,
            messagesByRoom = messagesByRoom,
            channelsByRoom = channelsByRoom,
            unreadCountsByChannel = unreadCountsByChannel,
            outgoing = outgoing,
        )
    }

    LaunchedEffect(account, serverHost) {
        val profile = account ?: return@LaunchedEffect
        while (true) {
            runCatching {
                FlockClient(baseHost = serverHost.trim(), secure = false)
                    .listFriends(profile.userId)
                    .map { it.toChatPerson() }
            }.onSuccess { serverFriends ->
                friends.replacePeopleWith(serverFriends)
            }
            delay(2000)
        }
    }

    LaunchedEffect(account, serverHost) {
        val profile = account ?: return@LaunchedEffect
        while (true) {
            status = Status.Connecting
            connectionProblem = null
            try {
                FlockClient(baseHost = serverHost.trim(), secure = false)
                    .connectUser(profile.userId, profile.userId, outgoing)
                    .collect { event ->
                        when (event) {
                            SessionEvent.Connected -> {
                                status = Status.Online
                                refreshFriendsAndRooms(
                                    client = FlockClient(baseHost = serverHost.trim(), secure = false),
                                    profile = profile,
                                    friends = friends,
                                    rooms = rooms,
                                    messagesByRoom = messagesByRoom,
                                    channelsByRoom = channelsByRoom,
                                    unreadCountsByChannel = unreadCountsByChannel,
                                    outgoing = outgoing,
                                )
                                rooms.forEach { room ->
                                    outgoing.emit(joinRoomEnvelope(room.id))
                                }
                            }
                            is SessionEvent.Message -> {
                                event.envelope.roomCreated?.let { created ->
                                    val roomId = created.roomId.toUuidString()
                                    refreshFriendsAndRooms(
                                        client = FlockClient(baseHost = serverHost.trim(), secure = false),
                                        profile = profile,
                                        friends = friends,
                                        rooms = rooms,
                                        messagesByRoom = messagesByRoom,
                                        channelsByRoom = channelsByRoom,
                                        unreadCountsByChannel = unreadCountsByChannel,
                                        outgoing = outgoing,
                                    )
                                    outgoing.emit(joinRoomEnvelope(roomId))
                                }
                                event.envelope.messageCreated?.let { created ->
                                    val roomId = created.roomId.toUuidString()
                                    val channelId = created.channelId?.toUuidString() ?: GeneralChannelId
                                    val messageKey = channelMessageKey(roomId, channelId)
                                    val list = messagesByRoom.getOrPut(messageKey) { mutableStateListOf() }
                                    val clientId = created.clientMessageId?.toUuidString()
                                    val messageId = created.messageId.toStableId()
                                    val existingIndex = list.indexByMessageIdentity(messageId, clientId)
                                    val senderId = created.senderUserId.toUuidString()
                                    val mine = senderId == profile.userId || existingIndex >= 0
                                    val kind = created.kind
                                    val room = rooms.firstOrNull { it.id == roomId }
                                    val channel = channelsByRoom[roomId]?.firstOrNull { it.id == channelId } ?: generalChannel(roomId)
                                    val incoming = ChatMessage(
                                        localId = if (existingIndex >= 0) list[existingIndex].localId else messageId,
                                        sender = senderNameFor(senderId, room, profile),
                                        text = if (kind == MessageKind.MESSAGE_KIND_IMAGE) "Photo" else created.body.decodeToString(),
                                        mine = mine,
                                        state = if (mine) "Delivered" else "Read",
                                        messageId = messageId,
                                        clientMessageId = clientId,
                                        createdAtMs = created.createdAt.value,
                                        photo = if (kind == MessageKind.MESSAGE_KIND_IMAGE) PickedPhoto("Photo", created.body) else null,
                                    )
                                    if (existingIndex >= 0) {
                                        list[existingIndex] = incoming
                                    } else {
                                        list.add(incoming)
                                    }
                                    val isOpenSelectedChannel =
                                        openRoomId == roomId && (selectedChannelByRoom[roomId] ?: GeneralChannelId) == channelId
                                    if (!mine) {
                                        launch {
                                            val client = FlockClient(baseHost = serverHost.trim(), secure = false)
                                            client.ackMessage(roomId, messageId, profile.userId, "delivered")
                                            if (isOpenSelectedChannel) {
                                                client.ackMessage(roomId, messageId, profile.userId, "read")
                                            }
                                        }
                                    }
                                    if (kind == MessageKind.MESSAGE_KIND_SYSTEM) {
                                        launch {
                                            val client = FlockClient(baseHost = serverHost.trim(), secure = false)
                                            runCatching {
                                                client.listRoomChannels(roomId, profile.userId).map { it.toChatChannel(roomId) }
                                            }.onSuccess { serverChannels ->
                                                val channelList = channelsByRoom.getOrPut(roomId) {
                                                    mutableStateListOf<ChatChannel>().apply { add(generalChannel(roomId)) }
                                                }
                                                channelList.replaceChannelsWith(serverChannels.withGeneral(roomId))
                                            }
                                        }
                                    }
                                    if (!mine && channel.joined && !channel.muted) {
                                        if (isOpenSelectedChannel) {
                                            unreadCountsByChannel[messageKey] = 0
                                        } else {
                                            unreadCountsByChannel[messageKey] = (unreadCountsByChannel[messageKey] ?: 0) + 1
                                            onNotifyMessage(
                                                room?.name ?: "New message",
                                                "${incoming.sender}: ${incoming.text}",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            } catch (error: Throwable) {
                status = Status.Offline
                connectionProblem = error.message ?: error::class.simpleName ?: "Could not reach the server"
                delay(1500)
            }
        }
    }

    LaunchedEffect(pickedPhoto, openRoomId) {
        val photo = pickedPhoto ?: return@LaunchedEffect
        val room = openRoomId ?: return@LaunchedEffect
        if (photo.bytes.size > MaxInlineMediaBytes) {
            val list = messagesByRoom.getOrPut(room) { mutableStateListOf() }
            list.add(
                ChatMessage(
                    localId = "local_${++messageCounter}",
                    sender = "Flock",
                    text = "That photo is too large to send here. Pick a photo under 1 MB while uploads are still local-only.",
                    mine = false,
                    state = "Not sent",
                )
            )
            onPickedPhotoConsumed()
            return@LaunchedEffect
        }
        sendMessage(
            roomId = channelMessageKey(room, selectedChannelByRoom[room] ?: GeneralChannelId),
            wireRoomId = room,
            profile = account ?: return@LaunchedEffect,
            text = "Photo",
            kind = MessageKind.MESSAGE_KIND_IMAGE,
            body = photo.bytes,
            photo = photo,
            status = status,
            messagesByRoom = messagesByRoom,
            outgoing = outgoing,
            nextCounter = { messageCounter += 1; messageCounter },
            channelId = selectedChannelByRoom[room] ?: GeneralChannelId,
        )
        onPickedPhotoConsumed()
    }

    val colorScheme = if (isSystemInDarkTheme()) appDarkColors else appLightColors
    MaterialTheme(colorScheme = colorScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (account == null) {
                AccountScreen(
                    serverHost = serverHost,
                    onServerHostChange = { serverHost = it },
                    onComplete = { profile, phonePrivacy, emailPrivacy ->
                        account = profile
                        discoverableByPhone = phonePrivacy
                        discoverableByEmail = emailPrivacy
                        status = Status.Offline
                    },
                )
                return@Surface
            }

            if (showAccount) {
                AccountSettingsScreen(
                    account = account!!,
                    serverHost = serverHost,
                    discoverableByPhone = discoverableByPhone,
                    discoverableByEmail = discoverableByEmail,
                    status = status,
                    onBack = { showAccount = false },
                    onPrivacyChange = { phone, email ->
                        discoverableByPhone = phone
                        discoverableByEmail = email
                        scope.launch {
                            runCatching {
                                FlockClient(baseHost = serverHost.trim(), secure = false).updatePrivacy(
                                    userId = account!!.userId,
                                    request = PrivacyRequest(
                                        discoverable_by_email = email,
                                        discoverable_by_phone = phone,
                                    ),
                                )
                            }.onSuccess { privacy ->
                                discoverableByPhone = privacy.discoverable_by_phone
                                discoverableByEmail = privacy.discoverable_by_email
                            }
                        }
                    },
                    onSignOut = {
                        account = null
                        openRoomId = null
                        showAccount = false
                        status = Status.SignedOut
                    },
                )
                return@Surface
            }

            if (showNewChat) {
                NewChatScreen(
                    account = account!!,
                    friends = friends,
                    serverHost = serverHost,
                    onContactsRequested = onContactsRequested,
                    onCancel = { showNewChat = false },
                    onCreate = { room ->
                        val existingIndex = rooms.indexOfFirst { it.id == room.id }
                        if (existingIndex >= 0) {
                            rooms[existingIndex] = room
                        } else {
                            rooms.add(room)
                        }
                        messagesByRoom.getOrPut(room.id) { mutableStateListOf() }
                        channelsByRoom.getOrPut(room.id) { mutableStateListOf<ChatChannel>().apply { add(generalChannel(room.id)) } }
                        openRoomId = room.id
                        showNewChat = false
                        scope.launch {
                            refreshFriendsAndRooms(
                                client = FlockClient(baseHost = serverHost.trim(), secure = false),
                                profile = account!!,
                                friends = friends,
                                rooms = rooms,
                                messagesByRoom = messagesByRoom,
                                channelsByRoom = channelsByRoom,
                                unreadCountsByChannel = unreadCountsByChannel,
                                outgoing = outgoing,
                            )
                            outgoing.emit(joinRoomEnvelope(room.id))
                        }
                    },
                )
                return@Surface
            }

            if (openRoom != null) {
                if (showRoomSettings) {
                    ManageChatScreen(
                        account = account!!,
                        room = openRoom,
                        channels = channelsByRoom.getOrPut(openRoom.id) { mutableStateListOf<ChatChannel>().apply { add(generalChannel(openRoom.id)) } },
                        friends = friends,
                        serverHost = serverHost,
                        onBack = { showRoomSettings = false },
                        onSave = { updatedRoom ->
                            val existingIndex = rooms.indexOfFirst { it.id == updatedRoom.id }
                            if (existingIndex >= 0) {
                                rooms[existingIndex] = updatedRoom
                            } else {
                                rooms.add(updatedRoom)
                            }
                            showRoomSettings = false
                        },
                        onChannelChanged = { updatedChannel ->
                            val list = channelsByRoom.getOrPut(openRoom.id) { mutableStateListOf<ChatChannel>().apply { add(generalChannel(openRoom.id)) } }
                            val index = list.indexOfFirst { it.id == updatedChannel.id }
                            if (index >= 0) list[index] = updatedChannel else list.add(updatedChannel)
                        },
                    )
                    return@Surface
                }
                val roomChannels = channelsByRoom.getOrPut(openRoom.id) {
                    mutableStateListOf<ChatChannel>().apply { add(generalChannel(openRoom.id)) }
                }
                val selectedChannelId = selectedChannelByRoom[openRoom.id]
                    ?.takeIf { id -> roomChannels.any { it.id == id } }
                    ?: GeneralChannelId
                ConversationScreen(
                    profile = account!!,
                    room = openRoom,
                    channels = roomChannels,
                    selectedChannelId = selectedChannelId,
                    messages = messagesByRoom.getOrPut(channelMessageKey(openRoom.id, selectedChannelId)) { mutableStateListOf() },
                    unreadCountsByChannel = unreadCountsByChannel,
                    likeCounts = likeCounts,
                    likedMessageIds = likedMessageIds,
                    status = status,
                    onBack = {
                        showRoomSettings = false
                        openRoomId = null
                    },
                    onManage = { showRoomSettings = true },
                    onChannelSelected = { channel ->
                        selectedChannelByRoom[openRoom.id] = channel.id
                        val key = channelMessageKey(openRoom.id, channel.id)
                        messagesByRoom.getOrPut(key) { mutableStateListOf() }
                        unreadCountsByChannel[key] = 0
                        if (channel.joined) scope.launch {
                            fetchAndMergeRoomHistory(
                                client = FlockClient(baseHost = serverHost.trim(), secure = false),
                                profile = account!!,
                                room = openRoom,
                                channelId = channel.id,
                                messagesByRoom = messagesByRoom,
                                markRead = true,
                            )
                        }
                    },
                    onJoinChannel = { channel ->
                        scope.launch {
                            val local = channel.copy(joined = true, muted = false)
                            val index = roomChannels.indexOfFirst { it.id == channel.id }
                            if (index >= 0) roomChannels[index] = local
                            runCatching {
                                FlockClient(baseHost = serverHost.trim(), secure = false).updateRoomChannelMembership(
                                    roomId = openRoom.id,
                                    channelId = channel.id,
                                    request = TopicChannelMembershipRequest(
                                        user_id = account!!.userId,
                                        joined = true,
                                        muted = false,
                                    ),
                                )
                            }.onSuccess { serverChannel ->
                                val updated = serverChannel.toChatChannel(openRoom.id)
                                val updatedIndex = roomChannels.indexOfFirst { it.id == updated.id }
                                if (updatedIndex >= 0) roomChannels[updatedIndex] = updated else roomChannels.add(updated)
                                fetchAndMergeRoomHistory(
                                    client = FlockClient(baseHost = serverHost.trim(), secure = false),
                                    profile = account!!,
                                    room = openRoom,
                                    channelId = updated.id,
                                    messagesByRoom = messagesByRoom,
                                    markRead = true,
                                )
                            }
                        }
                    },
                    onCreateChannel = { channelName, emoji, quiet ->
                        scope.launch {
                            val localChannel = ChatChannel(
                                id = "local_${ProtoUuid.randomUuid().toUuidString()}",
                                roomId = openRoom.id,
                                name = channelName.toChannelName(),
                                emoji = emoji.trim().ifBlank { null },
                                quiet = quiet,
                            )
                            roomChannels.add(localChannel)
                            selectedChannelByRoom[openRoom.id] = localChannel.id
                            messagesByRoom.getOrPut(channelMessageKey(openRoom.id, localChannel.id)) { mutableStateListOf() }
                            runCatching {
                                FlockClient(baseHost = serverHost.trim(), secure = false).createRoomChannel(
                                    roomId = openRoom.id,
                                    request = CreateTopicChannelRequest(
                                        creator_user_id = account!!.userId,
                                        name = localChannel.name,
                                        emoji = localChannel.emoji,
                                        quiet = quiet,
                                    ),
                                )
                            }.onSuccess { serverChannel ->
                                val updated = serverChannel.toChatChannel(openRoom.id)
                                val index = roomChannels.indexOfFirst { it.id == localChannel.id }
                                if (index >= 0) roomChannels[index] = updated
                                selectedChannelByRoom[openRoom.id] = updated.id
                                messagesByRoom.getOrPut(channelMessageKey(openRoom.id, updated.id)) { mutableStateListOf() }
                            }
                        }
                    },
                    onPickPhoto = onPickPhoto,
                    onToggleLike = { message ->
                        val currentlyLiked = likedMessageIds[message.localId] == true
                        likedMessageIds[message.localId] = !currentlyLiked
                        likeCounts[message.localId] = ((likeCounts[message.localId] ?: 0) + if (currentlyLiked) -1 else 1).coerceAtLeast(0)
                    },
                    onSendText = { text ->
                        scope.launch {
                            sendMessage(
                                roomId = channelMessageKey(openRoom.id, selectedChannelByRoom[openRoom.id] ?: GeneralChannelId),
                                wireRoomId = openRoom.id,
                                profile = account!!,
                                text = text,
                                kind = MessageKind.MESSAGE_KIND_TEXT,
                                body = text.encodeToByteArray(),
                                photo = null,
                                status = status,
                                messagesByRoom = messagesByRoom,
                                outgoing = outgoing,
                                nextCounter = { messageCounter += 1; messageCounter },
                                channelId = selectedChannelByRoom[openRoom.id] ?: GeneralChannelId,
                            )
                        }
                    },
                )
                return@Surface
            }

            HomeScreen(
                account = account!!,
                rooms = rooms,
                messagesByRoom = messagesByRoom,
                channelsByRoom = channelsByRoom,
                unreadCountsByChannel = unreadCountsByChannel,
                status = status,
                connectionProblem = connectionProblem,
                serverHost = serverHost,
                currentTab = homeTab,
                friends = friends,
                onTabChange = { homeTab = it },
                onOpenRoom = {
                    showRoomSettings = false
                    openRoomId = it.id
                    val selectedKey = channelMessageKey(it.id, selectedChannelByRoom[it.id] ?: GeneralChannelId)
                    unreadCountsByChannel[selectedKey] = 0
                        scope.launch { outgoing.emit(joinRoomEnvelope(it.id)) }
                    scope.launch {
                        val client = FlockClient(baseHost = serverHost.trim(), secure = false)
                        val channelList = channelsByRoom.getOrPut(it.id) {
                            mutableStateListOf<ChatChannel>().apply { add(generalChannel(it.id)) }
                        }
                        runCatching {
                            client.listRoomChannels(it.id, account!!.userId).map { summary -> summary.toChatChannel(it.id) }
                        }.onSuccess { serverChannels ->
                            channelList.replaceChannelsWith(serverChannels.withGeneral(it.id))
                        }
                        fetchAndMergeRoomHistory(
                            client = client,
                            profile = account!!,
                            room = it,
                            channelId = selectedChannelByRoom[it.id] ?: GeneralChannelId,
                            messagesByRoom = messagesByRoom,
                            markRead = true,
                        )
                    }
                },
                onNewChat = { showNewChat = true },
                onAccount = { showAccount = true },
                onFriendAdded = {
                    scope.launch {
                        refreshFriendsAndRooms(
                            client = FlockClient(baseHost = serverHost.trim(), secure = false),
                            profile = account!!,
                            friends = friends,
                            rooms = rooms,
                            messagesByRoom = messagesByRoom,
                            channelsByRoom = channelsByRoom,
                            unreadCountsByChannel = unreadCountsByChannel,
                            outgoing = outgoing,
                        )
                    }
                },
                onFriendChat = { friend ->
                    scope.launch {
                        runCatching {
                            FlockClient(baseHost = serverHost.trim(), secure = false).createRoom(
                                CreateRoomRequest(
                                    creator_user_id = account!!.userId,
                                    room_type = "direct",
                                    name = null,
                                    member_user_ids = listOf(friend.id),
                                ),
                            )
                        }.onSuccess { summary ->
                            val room = summary.toChatRoom(
                                currentUserId = account!!.userId,
                                fallbackPeople = listOf(friend),
                                fallbackName = friend.name,
                            )
                            val existingIndex = rooms.indexOfFirst { it.id == room.id }
                            if (existingIndex >= 0) rooms[existingIndex] = room else rooms.add(room)
                            channelsByRoom.getOrPut(room.id) { mutableStateListOf<ChatChannel>().apply { add(generalChannel(room.id)) } }
                            openRoomId = room.id
                            homeTab = HomeTab.Chats
                            outgoing.emit(joinRoomEnvelope(room.id))
                        }
                    }
                },
                onContactsRequested = onContactsRequested,
            )
        }
    }
}

private suspend fun sendMessage(
    roomId: String,
    wireRoomId: String,
    profile: UserProfile,
    text: String,
    kind: MessageKind,
    body: ByteArray,
    photo: PickedPhoto?,
    status: Status,
    messagesByRoom: MutableMap<String, SnapshotStateList<ChatMessage>>,
    outgoing: MutableSharedFlow<ClientEnvelope>,
    nextCounter: () -> Int,
    channelId: String,
) {
    val clientMessageId = ProtoUuid.randomUuid()
    val clientMessageIdString = clientMessageId.toUuidString()
    val list = messagesByRoom.getOrPut(roomId) { mutableStateListOf() }
    list.add(
        ChatMessage(
            localId = "local_${nextCounter()}",
            sender = "You",
            text = text,
            mine = true,
            state = if (status == Status.Online) "Sending" else "Waiting for server",
            clientMessageId = clientMessageIdString,
            photo = photo,
        )
    )
    outgoing.emit(joinRoomEnvelope(wireRoomId))
    outgoing.emit(
        ClientEnvelope(
            requestId = ProtoUuid.randomUuid(),
            sendMessage = SendMessage(
                roomId = ProtoUuid.fromString(wireRoomId),
                clientMessageId = clientMessageId,
                kind = kind,
                body = body,
                channelId = channelId.toProtoUuidOrNull(),
            ),
        )
    )
}

private fun joinRoomEnvelope(roomId: String): ClientEnvelope =
    ClientEnvelope(
        requestId = ProtoUuid.randomUuid(),
        joinRoom = JoinRoom(roomId = ProtoUuid.fromString(roomId)),
    )

private suspend fun refreshFriendsAndRooms(
    client: FlockClient,
    profile: UserProfile,
    friends: SnapshotStateList<ChatPerson>,
    rooms: SnapshotStateList<ChatRoom>,
    messagesByRoom: MutableMap<String, SnapshotStateList<ChatMessage>>,
    channelsByRoom: MutableMap<String, SnapshotStateList<ChatChannel>>,
    unreadCountsByChannel: MutableMap<String, Int>,
    outgoing: MutableSharedFlow<ClientEnvelope>,
) {
    ensureBackendUser(client, profile)
    runCatching {
        client.listFriends(profile.userId).map { it.toChatPerson() }
    }.onSuccess { serverFriends ->
        friends.replacePeopleWith(serverFriends)
    }
    runCatching {
        client.listUserRooms(profile.userId).map {
            it.toChatRoom(currentUserId = profile.userId, fallbackPeople = emptyList(), fallbackName = "")
        }
    }.onSuccess { serverRooms ->
        rooms.replaceRoomsWith(serverRooms)
        serverRooms.forEach { room ->
            messagesByRoom.getOrPut(room.id) { mutableStateListOf() }
            val channelList = channelsByRoom.getOrPut(room.id) {
                mutableStateListOf<ChatChannel>().apply { add(generalChannel(room.id)) }
            }
            runCatching {
                client.listRoomChannels(room.id, profile.userId).map { it.toChatChannel(room.id) }
            }.onSuccess { serverChannels ->
                channelList.replaceChannelsWith(serverChannels.withGeneral(room.id))
            }
            outgoing.emit(joinRoomEnvelope(room.id))
            fetchAndMergeRoomHistory(
                client = client,
                profile = profile,
                room = room,
                channelId = GeneralChannelId,
                messagesByRoom = messagesByRoom,
                unreadCountsByChannel = unreadCountsByChannel,
                markRead = false,
            )
            val joinedChannels = channelList.filter { it.joined }
            joinedChannels.filterNot { it.isGeneral }.forEach { channel ->
                fetchAndMergeRoomHistory(
                    client = client,
                    profile = profile,
                    room = room,
                    channelId = channel.id,
                    messagesByRoom = messagesByRoom,
                    unreadCountsByChannel = unreadCountsByChannel,
                    markRead = false,
                )
            }
        }
    }
}

private suspend fun ensureBackendUser(client: FlockClient, profile: UserProfile) {
    val contact = profile.contact.trim()
    if (profile.userId.isBlank() || profile.username.isBlank() || contact.isBlank()) return
    val isEmail = contact.contains("@")
    runCatching {
        client.registerUser(
            RegisterUserRequest(
                user_id = profile.userId,
                display_name = profile.name.ifBlank { profile.username },
                username = profile.username.removePrefix("@"),
                email = if (isEmail) contact else null,
                phone = if (!isEmail) contact else null,
            ),
        )
    }
}

private suspend fun fetchAndMergeRoomHistory(
    client: FlockClient,
    profile: UserProfile,
    room: ChatRoom,
    channelId: String,
    messagesByRoom: MutableMap<String, SnapshotStateList<ChatMessage>>,
    unreadCountsByChannel: MutableMap<String, Int>? = null,
    markRead: Boolean,
) {
    val list = messagesByRoom.getOrPut(channelMessageKey(room.id, channelId)) { mutableStateListOf() }
    val afterMs = list.maxOfOrNull { it.createdAtMs }?.takeIf { it > 0L } ?: 0L
    val hadExistingMessages = list.isNotEmpty()
    var newlyUnread = 0
    runCatching {
        client.fetchRoomMessages(room.id, profile.userId, afterMs, channelId)
    }.onSuccess { history ->
        history.forEach { summary ->
            val message = summary.toChatMessage(room, profile)
            val existingIndex = list.indexByMessageIdentity(message.messageId, message.clientMessageId)
            if (existingIndex >= 0) {
                val existing = list[existingIndex]
                list[existingIndex] = message.copy(
                    localId = existing.localId,
                    state = if (existing.mine) "Delivered" else if (markRead) "Read" else existing.state,
                    photo = existing.photo,
                )
            } else {
                list.add(message.copy(state = if (!message.mine && markRead) "Read" else message.state))
                if (hadExistingMessages && !markRead && !message.mine) {
                    newlyUnread += 1
                }
            }
            if (!message.mine && message.messageId != null) {
                runCatching {
                    client.ackMessage(room.id, message.messageId, profile.userId, "delivered")
                    if (markRead) client.ackMessage(room.id, message.messageId, profile.userId, "read")
                }
            }
        }
        list.sortBy { if (it.createdAtMs == 0L) Long.MAX_VALUE else it.createdAtMs }
        if (newlyUnread > 0) {
            val key = channelMessageKey(room.id, channelId)
            unreadCountsByChannel?.set(key, (unreadCountsByChannel[key] ?: 0) + newlyUnread)
        }
    }
}

private fun SnapshotStateList<ChatPerson>.replacePeopleWith(people: List<ChatPerson>) {
    clear()
    addAll(people.distinctBy { it.id })
}

private fun SnapshotStateList<ChatRoom>.replaceRoomsWith(serverRooms: List<ChatRoom>) {
    val currentById = associateBy { it.id }
    clear()
    addAll(
        serverRooms
            .filter { it.id.isNotBlank() }
            .map { room ->
                val current = currentById[room.id]
                if (current != null && room.people.isEmpty()) current else room
            }
            .distinctBy { it.id },
    )
}

private fun SnapshotStateList<ChatChannel>.replaceChannelsWith(channels: List<ChatChannel>) {
    val currentById = associateBy { it.id }
    clear()
    addAll(
        channels
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .map { channel ->
                val current = currentById[channel.id]
                if (current == null) channel else channel.copy(emoji = channel.emoji ?: current.emoji)
            }
            .withGeneral(channels.firstOrNull()?.roomId.orEmpty())
            .distinctBy { it.id },
    )
}

private fun List<ChatMessage>.distinctByMessageIdentity(): List<ChatMessage> =
    fold(emptyList()) { acc, message ->
        if (acc.indexByMessageIdentity(message.messageId, message.clientMessageId) >= 0) acc else acc + message
    }

private fun List<ChatMessage>.indexByMessageIdentity(messageId: String?, clientMessageId: String?): Int =
    indexOfFirst { existing ->
        (messageId != null && existing.messageId == messageId) ||
            (clientMessageId != null && existing.clientMessageId == clientMessageId)
    }

private fun MessageSummary.toChatMessage(room: ChatRoom, profile: UserProfile): ChatMessage {
    val senderId = sender_user_id
    val mine = senderId == profile.userId
    val kind = kind.uppercase()
    val bodyText = body ?: text ?: ""
    val isImage = kind.contains("IMAGE")
    return ChatMessage(
        localId = message_id ?: client_message_id ?: "history_${created_at_ms}_${senderId}_${bodyText.hashCode()}",
        sender = senderNameFor(senderId, room, profile),
        text = if (isImage) "Photo" else bodyText,
        mine = mine,
        state = if (mine) receipt_state ?: "Delivered" else "Delivered",
        messageId = message_id,
        clientMessageId = client_message_id,
        createdAtMs = created_at_ms ?: 0L,
        photo = null,
    )
}

private fun UserSummary.toUserProfile(fallbackContact: String, fallbackUsername: String): UserProfile {
    val resolvedUsername = username.ifBlank { fallbackUsername }.removePrefix("@")
    return UserProfile(
        name = display_name.ifBlank { resolvedUsername.ifBlank { "You" } },
        username = resolvedUsername,
        contact = email ?: phone ?: fallbackContact,
        userId = user_id,
    )
}

private fun UserSummary.toChatPerson(): ChatPerson {
    val resolvedUsername = username.removePrefix("@")
    val resolvedName = display_name.ifBlank {
        resolvedUsername.ifBlank { "Friend" }.replaceFirstChar { it.uppercase() }
    }
    return ChatPerson(
        id = user_id,
        name = resolvedName,
        username = if (resolvedUsername.isBlank()) "" else "@$resolvedUsername",
    )
}

private fun RoomSummary.toChatRoom(
    currentUserId: String,
    fallbackPeople: List<ChatPerson>,
    fallbackName: String,
): ChatRoom {
    val people = members.map { it.toChatPerson() }.filter { it.id != currentUserId }
    return ChatRoom(
        id = room_id,
        name = name?.takeIf { it.isNotBlank() } ?: fallbackName.ifBlank { people.defaultChatName().ifBlank { fallbackPeople.defaultChatName().ifBlank { "Chat" } } },
        people = if (people.isEmpty()) fallbackPeople else people,
    )
}

private fun TopicChannelSummary.toChatChannel(fallbackRoomId: String): ChatChannel =
    ChatChannel(
        id = channel_id.ifBlank { name.toChannelName() },
        roomId = room_id.ifBlank { fallbackRoomId },
        name = name.toChannelName(),
        emoji = emoji?.trim()?.ifBlank { null },
        quiet = quiet,
        joined = joined,
        muted = muted,
    )

private fun generalChannel(roomId: String): ChatChannel =
    ChatChannel(
        id = GeneralChannelId,
        roomId = roomId,
        name = "general",
        joined = true,
        muted = false,
    )

private fun List<ChatChannel>.withGeneral(roomId: String): List<ChatChannel> {
    val resolvedRoomId = roomId.ifBlank { firstOrNull()?.roomId.orEmpty() }
    val normalized = map { it.copy(name = it.name.toChannelName()) }
    return if (normalized.any { it.id == GeneralChannelId }) normalized else listOf(generalChannel(resolvedRoomId)) + normalized
}

private fun List<ChatPerson>.matchingPeople(query: String): List<ChatPerson> {
    val normalized = query.trim().removePrefix("@").lowercase()
    return filter {
        normalized.isBlank() ||
            it.name.lowercase().contains(normalized) ||
            it.username.removePrefix("@").lowercase().contains(normalized)
    }
}

private fun List<ChatPerson>.rankPeopleForQuery(
    query: String,
    friends: List<ChatPerson>,
    contacts: List<ChatPerson> = emptyList(),
): List<ChatPerson> {
    val normalized = query.trim().removePrefix("@").lowercase()
    val friendIds = friends.map { it.id }.toSet()
    val contactIds = contacts.map { it.id }.toSet()
    return distinctBy { it.id }.sortedWith(
        compareBy<ChatPerson>(
            { if (friendIds.contains(it.id)) 0 else 1 },
            { if (contactIds.contains(it.id)) 0 else 1 },
            { if (it.name.lowercase().startsWith(normalized) || it.username.removePrefix("@").lowercase().startsWith(normalized)) 0 else 1 },
            { it.name.lowercase() },
        ),
    )
}

private fun channelMessageKey(roomId: String, channelId: String): String =
    if (channelId == GeneralChannelId) roomId else "$roomId::$channelId"

private fun unreadForRoom(
    roomId: String,
    channels: List<ChatChannel>,
    unreadCountsByChannel: Map<String, Int>,
): Int {
    val effectiveChannels = channels.withGeneral(roomId).filter { it.joined && !it.muted }
    return effectiveChannels.sumOf { channel ->
        unreadCountsByChannel[channelMessageKey(roomId, channel.id)] ?: 0
    }
}

private fun String.toProtoUuidOrNull(): ProtoUuid? =
    runCatching {
        takeIf { it != GeneralChannelId && !it.startsWith("local_") }?.let { ProtoUuid.fromString(it) }
    }.getOrNull()

private fun String.toChannelName(): String =
    trim()
        .removePrefix("#")
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .ifBlank { "topic" }

private sealed interface TimelineItem {
    val key: String

    data class Message(val message: ChatMessage) : TimelineItem {
        override val key: String = message.localId
    }

    data class WordleGroup(val messages: List<ChatMessage>) : TimelineItem {
        override val key: String = "wordle_${messages.firstOrNull()?.localId.orEmpty()}_${messages.size}"
    }
}

private data class MentionToken(val prefix: Char, val query: String, val startIndex: Int)

private data class MentionSuggestion(val label: String, val replacement: String)

private fun activeMentionToken(text: String): MentionToken? {
    val cursor = text.length
    val start = text.lastIndexOfAny(charArrayOf('@', '#'), cursor - 1)
    if (start < 0) return null
    if (start > 0 && !text[start - 1].isWhitespace()) return null
    val token = text.substring(start + 1, cursor)
    if (token.any { it == '\n' || it == '\t' }) return null
    return MentionToken(text[start], token, start)
}

private fun String.replaceActiveMention(replacement: String): String {
    val token = activeMentionToken(this) ?: return this
    return substring(0, token.startIndex) + replacement + " "
}

private fun List<ChatMessage>.toTimelineItems(): List<TimelineItem> {
    val items = mutableListOf<TimelineItem>()
    val group = mutableListOf<ChatMessage>()
    fun flushGroup() {
        if (group.size >= 2) {
            items += TimelineItem.WordleGroup(group.toList())
        } else {
            items += group.map { TimelineItem.Message(it) }
        }
        group.clear()
    }
    forEach { message ->
        if (message.isWordleStylePost()) {
            group += message
        } else {
            flushGroup()
            items += TimelineItem.Message(message)
        }
    }
    flushGroup()
    return items
}

private fun ChatMessage.isWordleStylePost(): Boolean {
    val normalized = text.trim().lowercase()
    return normalized.startsWith("wordle ") ||
        normalized.startsWith("[wordle ") ||
        (normalized.contains("wordle") && Regex("\\b[1-6x]/6\\b").containsMatchIn(normalized))
}

@Composable
private fun AccountScreen(
    serverHost: String,
    onServerHostChange: (String) -> Unit,
    onComplete: (UserProfile, Boolean, Boolean) -> Unit,
) {
    var mode by remember { mutableStateOf(AccountMode.Register) }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var autoUsername by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var signupStep by remember { mutableStateOf(1) }
    var discoverPhone by remember { mutableStateOf(false) }
    var discoverEmail by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cleanUsername = username.trim().removePrefix("@")
    val isEmail = contact.contains("@")
    val isPhone = contact.isNotBlank() && !isEmail
    val canContinue = contact.isNotBlank() &&
        code == "1234" &&
        (mode == AccountMode.SignIn || (name.isNotBlank() && cleanUsername.isNotBlank() && (isEmail || isPhone)))

    LaunchedEffect(contact) {
        val derived = contact.substringBefore("@").trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (isEmail && derived.isNotBlank() && (username.isBlank() || username == autoUsername)) {
            username = derived
            autoUsername = derived
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text("Flock", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Private group chats for people you actually know.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == AccountMode.Register, { mode = AccountMode.Register; signupStep = 1 }, label = { Text("Create account") })
            FilterChip(mode == AccountMode.SignIn, { mode = AccountMode.SignIn; signupStep = 1 }, label = { Text("Sign in") })
        }
        if (mode == AccountMode.Register && signupStep == 2) {
            Text("Who can discover you?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Friends can only find you from backend search results when the matching contact method is enabled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("Discoverable by phone", discoverPhone) { discoverPhone = it }
            ToggleRow("Discoverable by email", discoverEmail) { discoverEmail = it }
        } else {
            if (mode == AccountMode.Register) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim().removePrefix("@") },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    placeholder = { Text("maya") },
                    singleLine = true,
                )
            }
            OutlinedTextField(contact, { contact = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Phone or email") }, singleLine = true)
            OutlinedTextField(code, { code = it.take(6) }, Modifier.fillMaxWidth(), label = { Text("Verification code") }, placeholder = { Text("Use 1234 while running locally") }, singleLine = true)
            OutlinedTextField(serverHost, onServerHostChange, Modifier.fillMaxWidth(), label = { Text("Server") }, singleLine = true)
        }
        if (errorText != null) {
            GentleCard("Could not continue", errorText ?: "")
        }
        Button(
            enabled = canContinue && !isSubmitting,
            onClick = {
                if (mode == AccountMode.Register && signupStep == 1) {
                    signupStep = 2
                    return@Button
                }
                scope.launch {
                    isSubmitting = true
                    errorText = null
                    val client = FlockClient(baseHost = serverHost.trim(), secure = false)
                    runCatching {
                        if (mode == AccountMode.Register) {
                            val registered = client.registerUser(
                                RegisterUserRequest(
                                    display_name = name.trim(),
                                    username = cleanUsername,
                                    phone = if (isPhone) contact else null,
                                    email = if (isEmail) contact else null,
                                ),
                            )
                            client.updatePrivacy(
                                userId = registered.user_id,
                                request = PrivacyRequest(
                                    discoverable_by_email = discoverEmail,
                                    discoverable_by_phone = discoverPhone,
                                ),
                            )
                        } else {
                            client.searchUsers(contact, requesterId = null).firstOrNull()
                                ?: error("No account found for that phone, email, or username.")
                        }
                    }.onSuccess { user ->
                        val profile = user.toUserProfile(fallbackContact = contact, fallbackUsername = cleanUsername)
                        onComplete(
                            profile,
                            if (mode == AccountMode.Register) discoverPhone else user.discoverable_by_phone,
                            if (mode == AccountMode.Register) discoverEmail else user.discoverable_by_email,
                        )
                    }.onFailure { error ->
                        errorText = error.message ?: "The server rejected the request."
                    }
                    isSubmitting = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                when {
                    isSubmitting -> "Please wait…"
                    mode == AccountMode.Register && signupStep == 1 -> "Next"
                    mode == AccountMode.Register -> "Create account"
                    else -> "Sign in"
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    account: UserProfile,
    rooms: List<ChatRoom>,
    messagesByRoom: Map<String, List<ChatMessage>>,
    channelsByRoom: Map<String, List<ChatChannel>>,
    unreadCountsByChannel: Map<String, Int>,
    status: Status,
    connectionProblem: String?,
    serverHost: String,
    currentTab: HomeTab,
    friends: SnapshotStateList<ChatPerson>,
    onTabChange: (HomeTab) -> Unit,
    onOpenRoom: (ChatRoom) -> Unit,
    onNewChat: () -> Unit,
    onAccount: () -> Unit,
    onFriendAdded: () -> Unit,
    onFriendChat: (ChatPerson) -> Unit,
    onContactsRequested: suspend () -> List<ContactCandidate>,
) {
    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        topBar = {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hi, ${account.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(status.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Filled.Add, contentDescription = "New chat")
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onAccount) {
                    Icon(Icons.Filled.Person, contentDescription = "Account")
                }
            }
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PillNavButton(
                    selected = currentTab == HomeTab.Chats,
                    onClick = { onTabChange(HomeTab.Chats) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chats") },
                    modifier = Modifier.weight(1f),
                )
                PillNavButton(
                    selected = currentTab == HomeTab.Friends,
                    onClick = { onTabChange(HomeTab.Friends) },
                    icon = { Icon(Icons.Filled.Group, contentDescription = "Friends") },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (currentTab == HomeTab.Chats) "Chats" else "Friends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (connectionProblem != null) {
                item {
                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Server not reached", fontWeight = FontWeight.SemiBold)
                            Text(connectionProblem)
                        }
                    }
                }
            }
            if (currentTab == HomeTab.Friends) {
                item {
                    FriendsPanel(
                        account = account,
                        serverHost = serverHost,
                        friends = friends,
                        onFriendAdded = onFriendAdded,
                        onFriendChat = onFriendChat,
                        onContactsRequested = onContactsRequested,
                    )
                }
            } else if (rooms.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("No chats yet", fontWeight = FontWeight.SemiBold)
                            Text("Start a group or send a direct message.")
                            Button(onNewChat, shape = RoundedCornerShape(10.dp), modifier = Modifier.height(48.dp)) {
                                Icon(Icons.Filled.Add, contentDescription = "New group or message")
                            }
                        }
                    }
                }
            } else {
                items(rooms, key = { it.id }) { room ->
                    ChatCard(
                        room = room,
                        messages = messagesByRoom[room.id].orEmpty(),
                        unreadCount = unreadForRoom(room.id, channelsByRoom[room.id].orEmpty(), unreadCountsByChannel),
                        onClick = { onOpenRoom(room) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PillNavButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(48.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

@Composable
private fun AccountSettingsScreen(
    account: UserProfile,
    serverHost: String,
    discoverableByPhone: Boolean,
    discoverableByEmail: Boolean,
    status: Status,
    onBack: () -> Unit,
    onPrivacyChange: (Boolean, Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Card(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(account.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("@${account.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(account.contact, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Server: $serverHost", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Connection: ${status.label()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ToggleRow("Let friends find me by phone", discoverableByPhone) {
            onPrivacyChange(it, discoverableByEmail)
        }
        ToggleRow("Let friends find me by email", discoverableByEmail) {
            onPrivacyChange(discoverableByPhone, it)
        }
        GentleCard(
            "Notifications",
            "Android can show local message notifications while the app process is running. Reliable notifications after the app is fully closed need push setup such as Firebase Cloud Messaging credentials.",
        )
        OutlinedButton(onSignOut, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

@Composable
private fun FriendsPanel(
    account: UserProfile,
    serverHost: String,
    friends: SnapshotStateList<ChatPerson>,
    onFriendAdded: () -> Unit,
    onFriendChat: (ChatPerson) -> Unit,
    onContactsRequested: suspend () -> List<ContactCandidate>,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    var contactSuggestions by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    var incomingRequests by remember { mutableStateOf<List<FriendRequestSummary>>(emptyList()) }
    var outgoingRequests by remember { mutableStateOf<List<FriendRequestSummary>>(emptyList()) }
    var stateText by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun refreshRequests() {
        scope.launch {
            runCatching {
                FlockClient(baseHost = serverHost.trim(), secure = false).listFriendRequests(account.userId)
            }.onSuccess { requests ->
                incomingRequests = requests.incoming
                outgoingRequests = requests.outgoing
            }
        }
    }

    LaunchedEffect(account.userId, serverHost) {
        while (true) {
            runCatching {
                FlockClient(baseHost = serverHost.trim(), secure = false).listFriendRequests(account.userId)
            }.onSuccess { requests ->
                incomingRequests = requests.incoming
                outgoingRequests = requests.outgoing
            }
            delay(2000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (friends.isEmpty()) {
            GentleCard("No friends yet", "Search for existing users by username, phone, or email.")
        } else {
            friends.forEach { friend ->
                PersonSummaryRow(friend, onChat = { onFriendChat(friend) })
            }
        }
        if (incomingRequests.isNotEmpty()) {
            Text("Friend requests", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            incomingRequests.forEach { request ->
                IncomingFriendRequestRow(
                    person = request.requester.toChatPerson(),
                    onAccept = {
                        scope.launch {
                            runCatching {
                                FlockClient(baseHost = serverHost.trim(), secure = false)
                                    .acceptFriendRequest(account.userId, request.requester_user_id)
                            }.onSuccess { response ->
                                if (friends.none { it.id == response.friend.user_id }) friends.add(response.friend.toChatPerson())
                                stateText = "Friend added."
                                refreshRequests()
                                onFriendAdded()
                            }.onFailure { error ->
                                stateText = error.message ?: "Could not accept request."
                            }
                        }
                    },
                    onReject = {
                        scope.launch {
                            runCatching {
                                FlockClient(baseHost = serverHost.trim(), secure = false)
                                    .rejectFriendRequest(account.userId, request.requester_user_id)
                            }.onSuccess {
                                stateText = "Request rejected."
                                refreshRequests()
                            }.onFailure { error ->
                                stateText = error.message ?: "Could not reject request."
                            }
                        }
                    },
                )
            }
        }
        if (outgoingRequests.isNotEmpty()) {
            outgoingRequests.forEach { request ->
                FriendSearchResultRow(
                    person = request.recipient.toChatPerson(),
                    friends = friends,
                    pending = true,
                    onAdd = {},
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("Find friends", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            enabled = !isSearching,
            onClick = {
                scope.launch {
                    isSearching = true
                    stateText = "Checking your contacts…"
                    contactSuggestions = emptyList()
                    val contacts = runCatching { onContactsRequested() }.getOrElse {
                        stateText = it.message ?: "Contacts permission was not granted."
                        isSearching = false
                        return@launch
                    }
                    val client = FlockClient(baseHost = serverHost.trim(), secure = false)
                    ensureBackendUser(client, account)
                    runCatching {
                        client.discoverContacts(
                                userId = account.userId,
                                phones = contacts.flatMap { it.phones },
                                emails = contacts.flatMap { it.emails },
                            )
                            .map { it.toChatPerson() }
                            .filter { it.id != account.userId && friends.none { friend -> friend.id == it.id } }
                    }.recoverCatching {
                        val exactQueries = contacts.flatMap { it.emails + it.phones }.distinct().take(40)
                        exactQueries.flatMap { exact ->
                            client.searchUsers(exact, requesterId = account.userId)
                                .map { it.toChatPerson() }
                        }.distinctBy { it.id }
                            .filter { it.id != account.userId && friends.none { friend -> friend.id == it.id } }
                    }.onSuccess { people ->
                        contactSuggestions = people
                        stateText = if (people.isEmpty()) "No discoverable Flock users found in contacts." else "Suggested from your contacts."
                    }.onFailure { error ->
                        stateText = error.message ?: "Contacts discovery failed."
                    }
                    isSearching = false
                }
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (isSearching) "Checking…" else "Suggest from contacts") }
        contactSuggestions.forEach { person ->
            FriendSearchResultRow(
                person = person,
                friends = friends,
                pending = outgoingRequests.any { it.recipient_user_id == person.id },
                onAdd = {
                    scope.launch {
                        runCatching {
                            FlockClient(baseHost = serverHost.trim(), secure = false).addFriend(account.userId, person.id)
                        }.onSuccess { response ->
                            if (response.request.status == "accepted" && friends.none { it.id == person.id }) {
                                friends.add(response.friend.toChatPerson())
                            }
                            stateText = if (response.request.status == "accepted") "Friend added." else "Request pending."
                            refreshRequests()
                            onFriendAdded()
                        }.onFailure { error ->
                            stateText = error.message ?: "Could not add friend."
                        }
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text("Search users") },
                placeholder = { Text("username, phone, or email") },
                singleLine = true,
            )
            Button(
                enabled = query.isNotBlank() && !isSearching,
                onClick = {
                    scope.launch {
                        isSearching = true
                        stateText = ""
                        results = emptyList()
                        val client = FlockClient(baseHost = serverHost.trim(), secure = false)
                        ensureBackendUser(client, account)
                        runCatching {
                            client.searchUsers(query.trim().removePrefix("@"), requesterId = account.userId)
                                .map { it.toChatPerson() }
                                .filter { it.id != account.userId }
                        }.onSuccess { people ->
                            results = people
                            stateText = if (people.isEmpty()) "No user found." else ""
                        }.onFailure { error ->
                            stateText = error.message ?: "Search failed."
                        }
                        isSearching = false
                    }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(52.dp),
            ) { Text(if (isSearching) "Searching…" else "Search") }
        }
        if (stateText.isNotBlank()) {
            Text(stateText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        results.forEach { person ->
            FriendSearchResultRow(
                person = person,
                friends = friends,
                pending = outgoingRequests.any { it.recipient_user_id == person.id },
                onAdd = {
                    scope.launch {
                        runCatching {
                            FlockClient(baseHost = serverHost.trim(), secure = false).addFriend(account.userId, person.id)
                        }.onSuccess { response ->
                            if (response.request.status == "accepted" && friends.none { it.id == person.id }) {
                                friends.add(response.friend.toChatPerson())
                            }
                            stateText = if (response.request.status == "accepted") "Friend added." else "Request pending."
                            refreshRequests()
                            onFriendAdded()
                        }.onFailure { error ->
                            stateText = error.message ?: "Could not add friend."
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun FriendSearchResultRow(
    person: ChatPerson,
    friends: List<ChatPerson>,
    pending: Boolean = false,
    onAdd: () -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(person.name.take(1))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(person.name, fontWeight = FontWeight.SemiBold)
                Text(person.username, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (pending) "Request Pending" else "Friend request required",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                enabled = !pending && friends.none { it.id == person.id },
                onClick = onAdd,
                shape = RoundedCornerShape(10.dp),
            ) { Text(if (friends.any { it.id == person.id }) "Added" else if (pending) "Request Pending" else "Add") }
        }
    }
}

@Composable
private fun IncomingFriendRequestRow(person: ChatPerson, onAccept: () -> Unit, onReject: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(person.name.take(1))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(person.name, fontWeight = FontWeight.SemiBold)
                Text(person.username, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onAccept) {
                Icon(Icons.Filled.Check, contentDescription = "Accept friend request", tint = Color(0xFF11875D))
            }
            IconButton(onClick = onReject) {
                Icon(Icons.Filled.Close, contentDescription = "Reject friend request", tint = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun PersonSummaryRow(person: ChatPerson, onChat: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(person.name.take(1))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(person.name, fontWeight = FontWeight.SemiBold)
                Text(person.username, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onChat) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Start direct message")
            }
        }
    }
}

@Composable
private fun NewChatScreen(
    account: UserProfile,
    friends: List<ChatPerson>,
    serverHost: String,
    onContactsRequested: suspend () -> List<ContactCandidate>,
    onCancel: () -> Unit,
    onCreate: (ChatRoom) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var usernameSearch by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    var contactPeople by remember { mutableStateOf<List<ChatPerson>>(emptyList()) }
    var stateText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val selectedPeople = remember { mutableStateListOf<ChatPerson>() }
    val scope = rememberCoroutineScope()
    val canCreate = selectedPeople.isNotEmpty()
    val defaultChatName = selectedPeople.defaultChatName()

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("New group or message", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Pick one person for a direct message, or pick several for a group.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Group name (optional)") }, placeholder = { Text(defaultChatName.ifBlank { "Family, Trip, Book club" }) }, singleLine = true)
        Text("People", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (friends.isEmpty()) {
            GentleCard("No saved friends yet", "Search a username below to add someone to this chat.")
        } else {
            friends.forEach { friend ->
                PersonPickerRow(
                    person = friend,
                    selected = selectedPeople.any { it.id == friend.id },
                    onToggle = {
                        if (selectedPeople.any { it.id == friend.id }) {
                            selectedPeople.removeAll { it.id == friend.id }
                        } else {
                            selectedPeople.add(friend)
                        }
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = usernameSearch,
                onValueChange = { usernameSearch = it },
                modifier = Modifier.weight(1f),
                label = { Text("Search users") },
                placeholder = { Text("name, username, phone, or email") },
                singleLine = true,
            )
            Button(
                enabled = usernameSearch.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        stateText = ""
                        searchResults = emptyList()
                        val query = usernameSearch.trim()
                        val client = FlockClient(baseHost = serverHost.trim(), secure = false)
                        runCatching {
                            if (contactPeople.isEmpty()) {
                                val contacts = runCatching { onContactsRequested() }.getOrDefault(emptyList())
                                contactPeople = client.discoverContacts(
                                    userId = account.userId,
                                    phones = contacts.flatMap { it.phones },
                                    emails = contacts.flatMap { it.emails },
                                ).map { it.toChatPerson() }.filter { it.id != account.userId }
                            }
                            val global = client.searchUsers(query.removePrefix("@"), requesterId = account.userId)
                                .map { it.toChatPerson() }
                                .filter { it.id != account.userId }
                            (contactPeople.matchingPeople(query) + global)
                                .rankPeopleForQuery(query, friends, contactPeople)
                        }.onSuccess { people ->
                            searchResults = people
                            stateText = if (people.isEmpty()) "No user found." else ""
                        }.onFailure { error ->
                            stateText = error.message ?: "Search failed."
                        }
                        busy = false
                    }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(52.dp),
            ) {
                Text(if (busy) "Searching…" else "Search")
            }
        }
        if (stateText.isNotBlank()) {
            Text(stateText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        searchResults.forEach { person ->
            PersonPickerRow(
                person = person,
                selected = selectedPeople.any { it.id == person.id },
                onToggle = {
                    if (selectedPeople.any { it.id == person.id }) {
                        selectedPeople.removeAll { it.id == person.id }
                    } else {
                        selectedPeople.add(person)
                    }
                },
            )
        }
        if (selectedPeople.isNotEmpty()) {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("Selected: ${selectedPeople.displayNames()}", modifier = Modifier.padding(14.dp))
            }
        }
        Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                "We’ll ask the server to create the chat and use the returned room/member summary.",
                modifier = Modifier.padding(14.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onCancel, Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel")
            }
            Button(
                enabled = canCreate,
                onClick = {
                    scope.launch {
                        busy = true
                        stateText = ""
                        val people = selectedPeople.toList()
                        val chatName = name.trim().ifBlank { defaultChatName }
                        runCatching {
                            FlockClient(baseHost = serverHost.trim(), secure = false).createRoom(
                                CreateRoomRequest(
                                    creator_user_id = account.userId,
                                    room_type = if (people.size == 1) "direct" else "group",
                                    name = chatName.ifBlank { null },
                                    member_user_ids = people.map { it.id },
                                ),
                            )
                        }.onSuccess { room ->
                            onCreate(room.toChatRoom(currentUserId = account.userId, fallbackPeople = people, fallbackName = chatName))
                        }.onFailure { error ->
                            stateText = error.message ?: "Could not create chat."
                        }
                        busy = false
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (selectedPeople.size == 1) "Message" else "Create group")
            }
        }
    }
}

@Composable
private fun ManageChatScreen(
    account: UserProfile,
    room: ChatRoom,
    channels: List<ChatChannel>,
    friends: List<ChatPerson>,
    serverHost: String,
    onBack: () -> Unit,
    onSave: (ChatRoom) -> Unit,
    onChannelChanged: (ChatChannel) -> Unit,
) {
    var name by remember(room.id) { mutableStateOf(room.name) }
    var stateText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selectablePeople = remember(room.id, friends.size) {
        (room.people + friends).distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }
    val selectedPeople = remember(room.id) {
        mutableStateListOf<ChatPerson>().apply { addAll(room.people) }
    }
    val canSave = selectedPeople.isNotEmpty() && !busy

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Manage chat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Chat name") },
            singleLine = true,
        )
        Text("People", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (selectablePeople.isEmpty()) {
            GentleCard("No friends to add", "Add friends first, then manage this chat.")
        } else {
            selectablePeople.forEach { person ->
                PersonPickerRow(
                    person = person,
                    selected = selectedPeople.any { it.id == person.id },
                    onToggle = {
                        if (selectedPeople.any { it.id == person.id }) {
                            selectedPeople.removeAll { it.id == person.id }
                        } else {
                            selectedPeople.add(person)
                        }
                    },
                )
            }
        }
        Text("Topics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        channels.withGeneral(room.id).forEach { channel ->
            ChannelManageRow(
                channel = channel,
                enabled = !channel.isGeneral && !busy,
                onJoinToggle = {
                    val updated = channel.copy(joined = !channel.joined, muted = if (channel.joined) true else channel.muted)
                    onChannelChanged(updated)
                    scope.launch {
                        runCatching {
                            FlockClient(baseHost = serverHost.trim(), secure = false).updateRoomChannelMembership(
                                roomId = room.id,
                                channelId = channel.id,
                                request = TopicChannelMembershipRequest(
                                    user_id = account.userId,
                                    joined = updated.joined,
                                    muted = updated.muted,
                                ),
                            )
                        }.onSuccess { onChannelChanged(it.toChatChannel(room.id)) }
                    }
                },
                onMuteToggle = {
                    val updated = channel.copy(muted = !channel.muted)
                    onChannelChanged(updated)
                    scope.launch {
                        runCatching {
                            FlockClient(baseHost = serverHost.trim(), secure = false).updateRoomChannelMembership(
                                roomId = room.id,
                                channelId = channel.id,
                                request = TopicChannelMembershipRequest(
                                    user_id = account.userId,
                                    joined = updated.joined,
                                    muted = updated.muted,
                                ),
                            )
                        }.onSuccess { onChannelChanged(it.toChatChannel(room.id)) }
                    }
                },
            )
        }
        if (stateText.isNotBlank()) {
            Text(stateText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onBack, Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel")
            }
            Button(
                enabled = canSave,
                onClick = {
                    scope.launch {
                        busy = true
                        stateText = ""
                        val people = selectedPeople.toList()
                        runCatching {
                            FlockClient(baseHost = serverHost.trim(), secure = false).updateRoom(
                                roomId = room.id,
                                request = UpdateRoomRequest(
                                    requester_user_id = account.userId,
                                    name = name.trim().ifBlank { null },
                                    member_user_ids = people.map { it.id },
                                ),
                            )
                        }.onSuccess { updated ->
                            onSave(updated.toChatRoom(currentUserId = account.userId, fallbackPeople = people, fallbackName = name.trim()))
                        }.onFailure { error ->
                            stateText = error.message ?: "Could not update chat."
                        }
                        busy = false
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Save")
            }
        }
    }
}

@Composable
private fun ConversationScreen(
    profile: UserProfile,
    room: ChatRoom,
    channels: List<ChatChannel>,
    selectedChannelId: String,
    messages: List<ChatMessage>,
    unreadCountsByChannel: Map<String, Int>,
    likeCounts: Map<String, Int>,
    likedMessageIds: Map<String, Boolean>,
    status: Status,
    onBack: () -> Unit,
    onManage: () -> Unit,
    onChannelSelected: (ChatChannel) -> Unit,
    onJoinChannel: (ChatChannel) -> Unit,
    onCreateChannel: (String, String, Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onToggleLike: (ChatMessage) -> Unit,
    onSendText: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showCreateChannel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selectedChannel = channels.firstOrNull { it.id == selectedChannelId } ?: generalChannel(room.id)
    val mentionToken = activeMentionToken(draft)
    val mentionSuggestions = remember(draft, room.people, channels) {
        mentionToken?.let { token ->
            if (token.prefix == '@') {
                room.people
                    .filter { it.name.contains(token.query, ignoreCase = true) || it.username.contains(token.query, ignoreCase = true) }
                    .take(8)
                    .map { MentionSuggestion("@${it.name}", "@${it.name}") }
            } else {
                channels.withGeneral(room.id)
                    .filter { it.name.contains(token.query, ignoreCase = true) }
                    .take(8)
                    .map { MentionSuggestion("#${it.name}", "#${it.name}") }
            }
        }.orEmpty()
    }
    val timeline = remember(messages.size, messages.lastOrNull()?.localId, selectedChannelId) {
        if (selectedChannelId == GeneralChannelId) messages.toTimelineItems() else messages.map { TimelineItem.Message(it) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    if (showCreateChannel) {
        CreateTopicChannelSheet(
            onDismiss = { showCreateChannel = false },
            onCreate = { name, emoji, quiet ->
                showCreateChannel = false
                onCreateChannel(name, emoji, quiet)
            },
        )
    }

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        topBar = {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Surface(
                    modifier = Modifier.weight(1f).height(64.dp).clickable(onClick = onManage),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(room.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(room.people.displayNames(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Filled.Settings, contentDescription = "Manage chat", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TopicPillBar(
                channels = channels.withGeneral(room.id),
                selectedChannelId = selectedChannel.id,
                unreadCountsByChannel = unreadCountsByChannel,
                onChannelSelected = onChannelSelected,
                onCreateChannel = { showCreateChannel = true },
            )
            if (status != Status.Online) {
                OfflineBanner(status = status)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (!selectedChannel.joined) {
                    item {
                        JoinTopicPrompt(channel = selectedChannel, onJoin = { onJoinChannel(selectedChannel) })
                    }
                } else if (messages.isEmpty()) {
                    item {
                        GentleCard("No messages yet", "Say hello in #${selectedChannel.name}.")
                    }
                } else {
                    items(timeline, key = { it.key }) { item ->
                        when (item) {
                            is TimelineItem.Message -> {
                                MessageBubble(
                                    message = item.message,
                                    likeCount = likeCounts[item.message.localId] ?: 0,
                                    liked = likedMessageIds[item.message.localId] == true,
                                    onToggleLike = { onToggleLike(item.message) },
                                )
                            }
                            is TimelineItem.WordleGroup -> {
                                WordleAccordion(
                                    messages = item.messages,
                                    likeCounts = likeCounts,
                                    likedMessageIds = likedMessageIds,
                                    onToggleLike = onToggleLike,
                                )
                            }
                        }
                    }
                }
            }
            if (mentionSuggestions.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(mentionSuggestions, key = { it.replacement }) { suggestion ->
                        AssistChip(
                            onClick = { draft = draft.replaceActiveMention(suggestion.replacement) },
                            label = { Text(suggestion.label) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onPickPhoto, modifier = Modifier.height(56.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Add photo")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = selectedChannel.joined,
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed && draft.isNotBlank() && selectedChannel.joined) {
                            onSendText(draft.trim())
                            draft = ""
                            true
                        } else {
                            false
                        }
                    },
                    label = { Text("Message #${selectedChannel.name}") },
                    placeholder = { Text(if (status == Status.Online) "Type here" else "Will send when connected") },
                    maxLines = 4,
                )
                Button(
                    enabled = draft.isNotBlank() && selectedChannel.joined,
                    onClick = {
                        onSendText(draft.trim())
                        draft = ""
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(56.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun TopicPillBar(
    channels: List<ChatChannel>,
    selectedChannelId: String,
    unreadCountsByChannel: Map<String, Int>,
    onChannelSelected: (ChatChannel) -> Unit,
    onCreateChannel: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(channels, key = { it.id }) { channel ->
            FilterChip(
                selected = channel.id == selectedChannelId,
                onClick = { onChannelSelected(channel) },
                leadingIcon = {
                    Text(channel.emoji ?: "#", style = MaterialTheme.typography.labelLarge)
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            channel.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val unread = unreadCountsByChannel[channelMessageKey(channel.roomId, channel.id)] ?: 0
                        if (unread > 0) {
                            UnreadBadge(unread)
                        }
                    }
                },
            )
        }
        item {
            IconButton(onClick = onCreateChannel) {
                Icon(Icons.Filled.Add, contentDescription = "Create topic")
            }
        }
    }
}

@Composable
private fun JoinTopicPrompt(channel: ChatChannel, onJoin: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Tag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("#${channel.name}", fontWeight = FontWeight.SemiBold)
                Text("Join quietly to send and receive this topic.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onJoin, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = "Join topic")
            }
        }
    }
}

@Composable
private fun EmojiPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val emojis = if (expanded) FullEmojiPickerList else CommonEmojiPickerList
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(emojis, key = { it }) { emoji ->
                FilterChip(
                    selected = selected == emoji,
                    onClick = { onSelect(emoji) },
                    label = { Text(emoji) },
                )
            }
            item {
                AssistChip(
                    onClick = { expanded = !expanded },
                    label = { Text(if (expanded) "Less" else "More") },
                )
            }
        }
    }
}

@Composable
private fun TopicNotifyChoice(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(if (selected) "●" else "○", modifier = Modifier.padding(top = 1.dp), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTopicChannelSheet(
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("") }
    var quiet by remember { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Create topic", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Channel name") },
                placeholder = { Text("germany trip") },
                singleLine = true,
            )
            OutlinedTextField(
                value = emoji,
                onValueChange = { emoji = it.take(4) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Emoji") },
                placeholder = { Text("Pick one below") },
                singleLine = true,
            )
            EmojiPicker(selected = emoji, onSelect = { emoji = it })
            TopicNotifyChoice(
                selected = !quiet,
                title = "Notify Everyone",
                body = "Notifies everyone when a message is sent.",
                onClick = { quiet = false },
            )
            TopicNotifyChoice(
                selected = quiet,
                title = "Quiet (Opt-In)",
                body = "Spawns silently. People can join when they want.",
                onClick = { quiet = true },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onDismiss, Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                }
                Button(
                    enabled = name.trim().isNotBlank(),
                    onClick = { onCreate(name.trim(), emoji.trim(), quiet) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Create topic")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ChannelManageRow(
    channel: ChatChannel,
    enabled: Boolean,
    onJoinToggle: () -> Unit,
    onMuteToggle: () -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Tag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${channel.emoji?.let { "$it " } ?: ""}#${channel.name}", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        channel.isGeneral -> "Home base"
                        !channel.joined -> "Left quietly"
                        channel.muted -> "Joined · muted"
                        channel.quiet -> "Quiet topic"
                        else -> "Public topic"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(enabled = enabled && channel.joined, onClick = onMuteToggle) {
                Icon(
                    if (channel.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (channel.muted) "Unmute topic" else "Mute topic",
                )
            }
            IconButton(enabled = enabled, onClick = onJoinToggle) {
                Icon(
                    if (channel.joined) Icons.AutoMirrored.Filled.Logout else Icons.AutoMirrored.Filled.Login,
                    contentDescription = if (channel.joined) "Leave topic" else "Join topic",
                )
            }
        }
    }
}

@Composable
private fun WordleAccordion(
    messages: List<ChatMessage>,
    likeCounts: Map<String, Int>,
    likedMessageIds: Map<String, Boolean>,
    onToggleLike: (ChatMessage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
                Spacer(Modifier.width(6.dp))
                Text("${messages.size} Wordle scores posted today", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            if (expanded) {
                messages.forEach { message ->
                    MessageBubble(
                        message = message,
                        likeCount = likeCounts[message.localId] ?: 0,
                        liked = likedMessageIds[message.localId] == true,
                        onToggleLike = { onToggleLike(message) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    likeCount: Int,
    liked: Boolean,
    onToggleLike: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(0.74f).pointerInput(message.localId) {
                detectTapGestures(onDoubleTap = { onToggleLike() })
            },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = if (message.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (!message.mine) {
                    Text(message.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                message.photo?.let { PlatformPhoto(it.bytes, it.name, Modifier.fillMaxWidth().aspectRatio(4f / 3f)) }
                if (message.photo == null) Text(message.text, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (likeCount > 0 || liked) {
                        Text("♥ ${likeCount.coerceAtLeast(if (liked) 1 else 0)}", style = MaterialTheme.typography.labelSmall, color = if (liked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (message.mine) {
                        Text(message.state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatCard(room: ChatRoom, messages: List<ChatMessage>, unreadCount: Int, onClick: () -> Unit) {
    val lastMessage = messages.lastOrNull()
    Card(onClick = onClick, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(room.name.take(1))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(room.name, fontWeight = FontWeight.SemiBold)
                Text(
                    lastMessage?.let { "${if (it.mine) "You" else it.sender}: ${it.text}" } ?: room.people.displayNames(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                unreadCount > 0 -> UnreadBadge(unreadCount)
                messages.isNotEmpty() -> Text("${messages.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PersonPickerRow(person: ChatPerson, selected: Boolean, onToggle: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Avatar(person.name.take(1))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(person.name, fontWeight = FontWeight.SemiBold)
                Text(person.username, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OfflineBanner(status: Status) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            text = if (status == Status.Connecting) "Connecting to the server…" else "Offline right now. Messages will wait here until the server is back.",
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun GentleCard(title: String, body: String) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body)
        }
    }
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(checked, onCheckedChange)
        }
    }
}

@Composable
private fun StatusDot(status: Status) {
    Box(
        Modifier.size(10.dp).background(if (status == Status.Online) Color(0xFF11875D) else Color(0xFFB7791F), CircleShape)
    )
}

@Composable
private fun Avatar(label: String) {
    Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(label.uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
expect fun PlatformPhoto(bytes: ByteArray, contentDescription: String, modifier: Modifier = Modifier)

private fun Status.label(): String = when (this) {
    Status.SignedOut -> "Signed out"
    Status.Connecting -> "Connecting"
    Status.Online -> "Online"
    Status.Offline -> "Offline — waiting for server"
}

private fun senderNameFor(senderId: String, room: ChatRoom?, profile: UserProfile): String {
    if (senderId == profile.userId) return "You"
    val person = room?.people?.firstOrNull { it.id == senderId }
    return when {
        person == null -> "Friend"
        person.name.isNotBlank() -> person.name
        person.username.isNotBlank() -> person.username
        else -> "Friend"
    }
}

private fun ByteArray.toStableId(): String = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

private fun List<ChatPerson>.displayNames(): String =
    if (isEmpty()) "Joined by invite code" else joinToString(", ") { it.name }

private fun List<ChatPerson>.defaultChatName(): String =
    when (size) {
        0 -> ""
        1 -> first().name
        else -> joinToString(", ") { it.name }
    }
