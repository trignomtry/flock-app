package app.flock.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import app.flock.ui.FlockApp
import app.flock.ui.FlockPersistedState
import app.flock.ui.PickedPhoto
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private const val LocalStateKey = "flock.web.state"

private val stateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    CanvasBasedWindow(canvasElementId = "ComposeTarget") {
        var pickedPhoto by remember { mutableStateOf<PickedPhoto?>(null) }
        FlockApp(
            pickedPhoto = pickedPhoto,
            onPickedPhotoConsumed = { pickedPhoto = null },
            onPickPhoto = { pickPhoto { pickedPhoto = it } },
            restoreState = { loadState() },
            onStateChanged = { saveState(it.lightweightForWeb()) },
        )
    }
}

private fun loadState(): FlockPersistedState? =
    runCatching {
        window.localStorage.getItem(LocalStateKey)?.let { stateJson.decodeFromString<FlockPersistedState>(it) }
    }.getOrNull()

private fun saveState(state: FlockPersistedState) {
    runCatching {
        window.localStorage.setItem(LocalStateKey, stateJson.encodeToString(state))
    }
}

private fun FlockPersistedState.lightweightForWeb(): FlockPersistedState =
    copy(
        messagesByRoom = messagesByRoom.mapValues { (_, messages) -> messages.takeLast(100) },
        likeCounts = emptyMap(),
        likedMessageIds = emptyMap(),
    )

private fun pickPhoto(onPicked: (PickedPhoto) -> Unit) {
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.accept = "image/*"
    input.style.display = "none"
    input.onchange = {
        val file = input.files?.item(0)
        if (file != null) {
            val reader = FileReader()
            reader.onload = {
                val buffer = reader.result as? ArrayBuffer
                if (buffer != null) {
                    val array = Int8Array(buffer)
                    val bytes = ByteArray(array.length) { index -> array.asDynamic()[index].unsafeCast<Byte>() }
                    onPicked(PickedPhoto(name = file.name, bytes = bytes))
                }
                document.body?.removeChild(input)
                null
            }
            reader.readAsArrayBuffer(file)
        } else {
            document.body?.removeChild(input)
        }
        null
    }
    document.body?.appendChild(input)
    input.click()
}
