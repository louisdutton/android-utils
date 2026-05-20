package com.kunzisoft.keepass.vault

import android.content.Context
import android.net.Uri
import java.io.File

object VaultFile {
    private const val FILE_NAME = "vault.kdbx"

    fun file(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    fun uri(context: Context): Uri {
        return Uri.fromFile(file(context))
    }

    fun exists(context: Context): Boolean {
        return file(context).let { file ->
            file.isFile && file.length() > 0L
        }
    }
}
