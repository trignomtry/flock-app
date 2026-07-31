package app.flock.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.flock.ui.ContactCandidate

fun readDeviceContacts(context: Context): List<ContactCandidate> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    val people = linkedMapOf<String, MutableContact>()
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cursor.moveToNext()) {
            val id = cursor.getString(idIndex)
            val contact = people.getOrPut(id) { MutableContact(cursor.getString(nameIndex).orEmpty()) }
            contact.phones += cursor.getString(phoneIndex).orEmpty().filter { it.isDigit() || it == '+' }
        }
    }
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
        val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
        val emailIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
        while (cursor.moveToNext()) {
            val id = cursor.getString(idIndex)
            val contact = people.getOrPut(id) { MutableContact(cursor.getString(nameIndex).orEmpty()) }
            contact.emails += cursor.getString(emailIndex).orEmpty().trim().lowercase()
        }
    }
    return people.values.map {
        ContactCandidate(
            displayName = it.name,
            phones = it.phones.distinct(),
            emails = it.emails.distinct(),
        )
    }.filter { it.phones.isNotEmpty() || it.emails.isNotEmpty() }
}

private data class MutableContact(
    val name: String,
    val phones: MutableList<String> = mutableListOf(),
    val emails: MutableList<String> = mutableListOf(),
)
