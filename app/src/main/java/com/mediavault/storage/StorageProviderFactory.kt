package com.mediavault.storage

import android.content.Context
import android.net.Uri
import com.mediavault.core.database.DestinationEntity
import com.mediavault.core.database.DestinationType
import com.mediavault.core.security.CredentialStore
import com.mediavault.storage.api.StorageProvider
import com.mediavault.storage.drive.DriveStorageProvider
import com.mediavault.storage.drive.GoogleDriveAuth
import com.mediavault.storage.ftp.FtpSecurity
import com.mediavault.storage.ftp.FtpStorageProvider
import com.mediavault.storage.local.LocalStorageProvider
import com.mediavault.storage.nfs.NfsStorageProvider
import com.mediavault.storage.saf.SafStorageProvider
import com.mediavault.storage.sftp.SftpStorageProvider
import com.mediavault.storage.smb.SmbStorageProvider
import com.mediavault.storage.webdav.WebDavStorageProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Single place that turns saved configuration + secrets into a live [StorageProvider]. */
@Singleton
class StorageProviderFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: CredentialStore,
) {
    fun create(d: DestinationEntity): StorageProvider {
        val password = credentials.get(d.id, "password")
        return when (DestinationType.valueOf(d.type)) {
            DestinationType.LOCAL_APP -> LocalStorageProvider(
                File(context.getExternalFilesDir(null) ?: context.filesDir, d.path.trim('/').ifEmpty { "Destination" }).also { it.mkdirs() },
            )
            DestinationType.SAF_FOLDER -> SafStorageProvider(context, Uri.parse(d.opt("treeUri")))
            DestinationType.SMB -> SmbStorageProvider(
                d.host, d.port.takeIf { it > 0 } ?: 445, d.opt("share"), d.username, password.orEmpty(),
                d.opt("domain").ifBlank { null }, d.path,
            )
            DestinationType.FTP -> FtpStorageProvider(d.host, d.port.takeIf { it > 0 } ?: 21, d.username, password.orEmpty(), FtpSecurity.PLAIN, d.path)
            DestinationType.FTPS -> {
                val implicit = d.opt("ftpsImplicit") == "true"
                FtpStorageProvider(
                    d.host, d.port.takeIf { it > 0 } ?: if (implicit) 990 else 21, d.username, password.orEmpty(),
                    if (implicit) FtpSecurity.IMPLICIT_TLS else FtpSecurity.EXPLICIT_TLS, d.path,
                )
            }
            DestinationType.SFTP -> {
                val useKey = d.opt("authMethod") == "key"
                SftpStorageProvider(
                    d.host, d.port.takeIf { it > 0 } ?: 22, d.username,
                    password = if (useKey) null else password,
                    privateKeyPem = if (useKey) credentials.get(d.id, "privateKey") else null,
                    passphrase = credentials.get(d.id, "passphrase"),
                    trustedFingerprint = d.opt("hostKeyFingerprint").ifBlank { null },
                    rootDir = d.path,
                )
            }
            DestinationType.WEBDAV -> WebDavStorageProvider(d.host, d.username, password.orEmpty())
            DestinationType.GOOGLE_DRIVE -> DriveStorageProvider(
                token = { runBlocking { GoogleDriveAuth.silentToken(context) } },
                rootFolderName = d.path.ifBlank { "MediaVault" },
            )
            DestinationType.NFS -> NfsStorageProvider()
        }
    }
}
