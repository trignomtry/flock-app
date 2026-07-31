package app.flock.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.flock.ui.ContactCandidate
import app.flock.ui.FlockApp
import app.flock.ui.FlockPersistedState
import app.flock.ui.PickedPhoto
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {
    private var pickedPhoto by mutableStateOf<PickedPhoto?>(null)
    private var notificationId = 1
    private var pendingContactsContinuation: (Boolean) -> Unit = {}

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The account screen explains that notifications are optional. If denied, chat still works.
        }

    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingContactsContinuation(granted)
            pendingContactsContinuation = {}
        }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@registerForActivityResult
        pickedPhoto = PickedPhoto(name = "Selected photo", bytes = bytes)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        MessagePollWorker.schedule(this)
        setContent {
            FlockApp(
                pickedPhoto = pickedPhoto,
                onPickedPhotoConsumed = { pickedPhoto = null },
                onPickPhoto = { photoPicker.launch("image/*") },
                onNotifyMessage = { title, body -> showMessageNotification(title, body) },
                restoreState = { FlockLocalStore.load(this) },
                onStateChanged = { state: FlockPersistedState ->
                    FlockLocalStore.save(this, state)
                    if (state.account != null) MessagePollWorker.schedule(this)
                },
                onContactsRequested = { requestAndReadContacts() },
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications for new Flock messages while the app is running."
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun requestAndReadContacts(): List<ContactCandidate> {
        val granted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        ) {
            true
        } else {
            suspendCancellableCoroutine { continuation ->
                pendingContactsContinuation = { continuation.resume(it) }
                contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            }
        }
        return if (granted) readDeviceContacts(this) else emptyList()
    }

    private fun showMessageNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, MESSAGE_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager().notify(notificationId++, notification)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private companion object {
        const val MESSAGE_CHANNEL_ID = "flock_messages"
    }
}
