package com.accessbridgeai.utils

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

data class ContactMatch(
    val id: String,
    val displayName: String,
    val phoneNumber: String
)

object ContactResolver {

    fun searchContactByName(context: Context, nameQuery: String): List<ContactMatch> {
        val matches = mutableListOf<ContactMatch>()
        if (nameQuery.isBlank()) return matches

        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%${nameQuery.trim()}%")

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.let {
                val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getString(idIndex) else ""
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else ""
                    val number = if (numberIndex >= 0) it.getString(numberIndex) else ""

                    if (name.isNotBlank() && number.isNotBlank()) {
                        matches.add(ContactMatch(id, name, number))
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("ContactResolver", "Permission READ_CONTACTS not granted", e)
        } catch (e: Exception) {
            Log.e("ContactResolver", "Error searching contacts", e)
        } finally {
            cursor?.close()
        }

        return matches.distinctBy { it.phoneNumber }
    }
}
