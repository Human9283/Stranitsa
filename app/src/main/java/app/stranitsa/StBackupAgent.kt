package app.stranitsa

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor

/**
 * Системная резервная копия Android (облако Google и перенос на новый телефон) —
 * только если пользователь сам включил её в настройках. По умолчанию выключена.
 * Что именно копируется, задают backup_rules.xml / data_extraction_rules.xml (только stranitsa.json).
 */
class StBackupAgent : BackupAgent() {
    override fun onFullBackup(data: FullBackupDataOutput) {
        if (StApplication.isBackupAllowed(this)) super.onFullBackup(data)
        // Иначе — пустая копия: ничего не уходит с устройства.
    }

    // Режим «ключ-значение» не используется (fullBackupOnly = true).
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) {}
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) {}
}
