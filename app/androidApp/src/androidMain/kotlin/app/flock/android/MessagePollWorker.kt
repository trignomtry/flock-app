package app.flock.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.flock.shared.network.FlockClient
import app.flock.ui.ChatMessage
import app.flock.ui.FlockPersistedState
import java.util.concurrent.TimeUnit

class MessagePollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val state = FlockLocalStore.load(applicationContext) ?: return Result.success()
        val account = state.account ?: return Result.success()
        val client = FlockClient(baseHost = state.serverHost.trim(), secure = true)
        val updatedMessages = state.messagesByRoom.toMutableMap()
        val updatedUnread = state.unreadCountsByChannel.toMutableMap()
        var changed = false

        state.rooms.forEach { room ->
            val channels = state.channelsByRoom[room.id].orEmpty().ifEmpty {
                listOf(app.flock.ui.ChatChannel(id = GeneralChannelId, roomId = room.id, name = "general"))
            }.filter { it.joined && !it.muted }

            channels.forEach { channel ->
            val messageKey = channelMessageKey(room.id, channel.id)
            val local = updatedMessages[messageKey].orEmpty()
            val afterMs = local.maxOfOrNull { it.createdAtMs }?.takeIf { it > 0L } ?: 0L
            runCatching { client.fetchRoomMessages(room.id, account.userId, afterMs, channel.id) }
                .onSuccess { messages ->
                    val merged = local.toMutableList()
                    messages.forEach { summary ->
                        val messageId = summary.message_id ?: return@forEach
                        if (merged.any { it.messageId == messageId || it.clientMessageId == summary.client_message_id }) return@forEach
                        val mine = summary.sender_user_id == account.userId
                        val text = summary.body ?: summary.text ?: "New message"
                        val message = ChatMessage(
                            localId = messageId,
                            sender = room.people.firstOrNull { it.id == summary.sender_user_id }?.name ?: "Friend",
                            text = if (summary.kind.uppercase().contains("IMAGE")) "Photo" else text,
                            mine = mine,
                            state = if (mine) summary.receipt_state ?: "Delivered" else "Delivered",
                            messageId = messageId,
                            clientMessageId = summary.client_message_id,
                            createdAtMs = summary.created_at_ms ?: System.currentTimeMillis(),
                        )
                        merged += message
                        changed = true
                        if (!mine) {
                            updatedUnread[messageKey] = (updatedUnread[messageKey] ?: 0) + 1
                            showNotification(room.name, "${message.sender}: ${message.text}")
                            runCatching { client.ackMessage(room.id, messageId, account.userId, "delivered") }
                        }
                    }
                    updatedMessages[messageKey] = merged.sortedBy { if (it.createdAtMs == 0L) Long.MAX_VALUE else it.createdAtMs }
                }
            }
        }

        if (changed) {
            FlockLocalStore.save(
                applicationContext,
                FlockPersistedState(
                    account = state.account,
                    serverHost = state.serverHost,
                    discoverableByPhone = state.discoverableByPhone,
                    discoverableByEmail = state.discoverableByEmail,
                    friends = state.friends,
                    rooms = state.rooms,
                    messagesByRoom = updatedMessages,
                    channelsByRoom = state.channelsByRoom,
                    selectedChannelByRoom = state.selectedChannelByRoom,
                    unreadCountsByChannel = updatedUnread,
                    likeCounts = state.likeCounts,
                    likedMessageIds = state.likedMessageIds,
                ),
            )
        }
        return Result.success()
    }

    private fun showNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel()
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(applicationContext, MESSAGE_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(applicationContext)
        }
        notificationManager().notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            builder
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun notificationManager() =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val MESSAGE_CHANNEL_ID = "flock_messages"
        private const val WORK_NAME = "flock_message_poll"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MessagePollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

private const val GeneralChannelId = "00000000-0000-0000-0000-000000000000"

private fun channelMessageKey(roomId: String, channelId: String): String =
    if (channelId == GeneralChannelId) roomId else "$roomId::$channelId"
