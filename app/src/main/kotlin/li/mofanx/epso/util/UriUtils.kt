package li.mofanx.epso.util

import android.net.Uri
import li.mofanx.epso.app

object UriUtils {
    fun uri2Bytes(uri: Uri): ByteArray {
        app.contentResolver.openInputStream(uri)?.use {
            return it.readBytes()
        }
        return ByteArray(0)
    }
}