package com.mediavault.feature.storage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import com.mediavault.core.database.DestinationEntity
import com.mediavault.core.database.DestinationType
import com.mediavault.storage.api.StorageResult
import com.mediavault.storage.drive.GoogleDriveAuth
import com.mediavault.storage.saf.SafStorageProvider
import java.util.UUID

private val selectable = DestinationType.entries.filter { it != DestinationType.NFS }

@Composable
fun DestinationEditor(initial: DestinationEntity?, vm: StorageViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf(initial?.let { DestinationType.valueOf(it.type) } ?: DestinationType.SFTP) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var path by remember { mutableStateOf(initial?.path ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf(initial?.opt("domain") ?: "") }
    var share by remember { mutableStateOf(initial?.opt("share") ?: "") }
    var implicitTls by remember { mutableStateOf(initial?.opt("ftpsImplicit") == "true") }
    var useKey by remember { mutableStateOf(initial?.opt("authMethod") == "key") }
    var fingerprint by remember { mutableStateOf(initial?.opt("hostKeyFingerprint") ?: "") }
    var treeUri by remember { mutableStateOf(initial?.opt("treeUri") ?: "") }
    var googleOk by remember { mutableStateOf(initial?.opt("googleConnected") == "true") }
    var pendingFp by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val hadPassword = initial?.let { vm.hasPassword(it.id) } == true
    val hadKey = initial?.let { vm.hasKey(it.id) } == true

    fun takeTree(uri: Uri?, flags: Int) {
        if (uri == null) return
        ctx.contentResolver.takePersistableUriPermission(uri, flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        treeUri = uri.toString()
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        takeTree(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    val pickSd = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) takeTree(r.data?.data, r.data?.flags ?: 0)
    }
    val googleConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        try {
            Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(r.data)
            googleOk = true; message = null
        } catch (e: Exception) { message = "Google access was not granted." }
    }
    val sdVolumes = remember { SafStorageProvider.removableVolumes(ctx) }

    fun resetTrust() { fingerprint = "" }

    val portNum = port.toIntOrNull()
    val portOk = portNum != null && portNum in 1..65535
    val valid = name.isNotBlank() && when (type) {
        DestinationType.LOCAL_APP -> true
        DestinationType.SAF_FOLDER -> treeUri.isNotBlank()
        DestinationType.SMB -> host.isNotBlank() && share.isNotBlank() && (port.isBlank() || portOk)
        DestinationType.FTP, DestinationType.FTPS -> host.isNotBlank() && user.isNotBlank() && (port.isBlank() || portOk)
        DestinationType.SFTP -> host.isNotBlank() && user.isNotBlank() && (port.isBlank() || portOk) && fingerprint.isNotBlank() &&
            (if (useKey) (privateKey.isNotBlank() || hadKey) else (password.isNotBlank() || hadPassword))
        DestinationType.WEBDAV -> host.startsWith("http://") || host.startsWith("https://")
        DestinationType.GOOGLE_DRIVE -> googleOk
        DestinationType.NFS -> false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add destination" else "Edit destination") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        selectable.forEach { t ->
                            FilterChip(selected = type == t, onClick = { type = t; message = null }, label = { Text(t.label) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                    Text("NFS is not offered: standard Android cannot mount NFS without root. Share the folder over SMB or WebDAV instead.", style = MaterialTheme.typography.bodySmall)
                } else Text(type.label, style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                when (type) {
                    DestinationType.LOCAL_APP -> OutlinedTextField(path, { path = it }, label = { Text("Subfolder in app storage") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    DestinationType.SAF_FOLDER -> {
                        Text(if (treeUri.isBlank()) "No folder chosen" else "Folder chosen ✓", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pickTree.launch(null) }) { Text("Choose folder") }
                            if (sdVolumes.isNotEmpty() && Build.VERSION.SDK_INT >= 29) {
                                OutlinedButton(onClick = { pickSd.launch(sdVolumes.first().createOpenDocumentTreeIntent()) }) { Text("SD card") }
                            }
                        }
                        Text("Android only lets MediaVault write inside the folder you pick. If the SD card is removed, uploads wait and retry.", style = MaterialTheme.typography.bodySmall)
                    }

                    DestinationType.GOOGLE_DRIVE -> {
                        Text(if (googleOk) "Google account connected ✓" else "Not connected", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            busy = true
                            Identity.getAuthorizationClient(ctx).authorize(GoogleDriveAuth.request())
                                .addOnSuccessListener { res ->
                                    busy = false
                                    val pi = res.pendingIntent
                                    if (res.hasResolution() && pi != null) googleConsent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                                    else { googleOk = true; message = null }
                                }
                                .addOnFailureListener { e -> busy = false; message = "Google sign-in failed: ${e.message}. This build needs an OAuth Android client configured in Google Cloud (see README)." }
                        }, enabled = !busy) { Text(if (googleOk) "Reconnect account" else "Connect Google account") }
                        OutlinedTextField(path, { path = it }, label = { Text("Drive folder name (default MediaVault)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Only the narrow drive.file permission is requested: MediaVault can see only files it creates.", style = MaterialTheme.typography.bodySmall)
                    }

                    DestinationType.WEBDAV -> {
                        OutlinedTextField(host, { host = it }, label = { Text("URL (https://server/path)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (host.startsWith("http://")) Text("Plain HTTP is not encrypted — the password can be read on the network.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text(if (hadPassword) "Password (unchanged)" else "Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }

                    else -> {
                        OutlinedTextField(host, { host = it; resetTrust() }, label = { Text("Host or IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val defPort = when (type) { DestinationType.SMB -> "445"; DestinationType.SFTP -> "22"; DestinationType.FTPS -> if (implicitTls) "990" else "21"; else -> "21" }
                        OutlinedTextField(port, { port = it.filter(Char::isDigit); resetTrust() }, label = { Text("Port (default $defPort)") }, singleLine = true, isError = port.isNotBlank() && !portOk, modifier = Modifier.fillMaxWidth())
                        if (type == DestinationType.SMB) {
                            OutlinedTextField(share, { share = it }, label = { Text("Share name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(domain, { domain = it }, label = { Text("Domain (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (type == DestinationType.FTP) Text("FTP is NOT encrypted: your password and files can be read on the network. Use FTPS or SFTP if the server supports them.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (type == DestinationType.FTPS) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Switch(implicitTls, { implicitTls = it })
                                Text(if (implicitTls) "  Implicit TLS" else "  Explicit TLS (AUTH TLS)")
                            }
                            Text("The server certificate is always validated; self-signed certificates are rejected.", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (type == DestinationType.SFTP) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                FilterChip(!useKey, { useKey = false }, label = { Text("Password") }, modifier = Modifier.padding(end = 6.dp))
                                FilterChip(useKey, { useKey = true }, label = { Text("Private key") })
                            }
                        }
                        if (type == DestinationType.SFTP && useKey) {
                            OutlinedTextField(privateKey, { privateKey = it }, label = { Text(if (hadKey) "Private key (unchanged)" else "Private key (PEM text)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Key passphrase (if any)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(password, { password = it }, label = { Text(if (hadPassword) "Password (unchanged)" else "Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(path, { path = it }, label = { Text("Remote folder (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                        if (type == DestinationType.SFTP) {
                            Text(if (fingerprint.isBlank()) "Server host key not verified" else "Trusted host key:\n$fingerprint", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                enabled = host.isNotBlank() && (port.isBlank() || portOk) && !busy,
                                onClick = {
                                    busy = true; message = null
                                    vm.fetchSftpFingerprint(host.trim(), portNum ?: 22) { r ->
                                        busy = false
                                        when (r) {
                                            is StorageResult.Success -> pendingFp = r.value
                                            is StorageResult.Failure -> message = r.error.message
                                        }
                                    }
                                },
                            ) { Text(if (fingerprint.isBlank()) "Verify host key" else "Re-verify host key") }
                        }
                    }
                }
                if (initial != null && (hadPassword || hadKey)) {
                    TextButton(onClick = { vm.clearCredentials(initial.id); message = "Stored credentials removed. Enter them again before saving." }) { Text("Remove stored credentials") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                var d = DestinationEntity(
                    id = initial?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(), type = type.name, host = host.trim(), port = portNum ?: 0,
                    path = path.trim(), username = user.trim(), options = "{}",
                    createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                )
                d = when (type) {
                    DestinationType.SMB -> d.withOpt("share", share.trim()).withOpt("domain", domain.trim())
                    DestinationType.FTPS -> d.withOpt("ftpsImplicit", implicitTls.toString())
                    DestinationType.SFTP -> d.withOpt("authMethod", if (useKey) "key" else "password").withOpt("hostKeyFingerprint", fingerprint)
                    DestinationType.SAF_FOLDER -> d.withOpt("treeUri", treeUri)
                    DestinationType.GOOGLE_DRIVE -> d.withOpt("googleConnected", "true")
                    else -> d
                }
                vm.save(d, mapOf("password" to password, "privateKey" to privateKey, "passphrase" to passphrase), onDismiss)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    pendingFp?.let { fp ->
        AlertDialog(
            onDismissRequest = { pendingFp = null },
            title = { Text("Trust this server?") },
            text = { Text("The server presented this host key fingerprint:\n\n$fp\n\nCompare it with your server (ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub) before trusting. MediaVault will refuse to connect if it ever changes.") },
            confirmButton = { TextButton(onClick = { fingerprint = fp; pendingFp = null }) { Text("Trust") } },
            dismissButton = { TextButton(onClick = { pendingFp = null }) { Text("Don't trust") } },
        )
    }
}
