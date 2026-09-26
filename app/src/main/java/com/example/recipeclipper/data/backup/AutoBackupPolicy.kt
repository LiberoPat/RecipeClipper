package com.example.recipeclipper.data.backup

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the automatic copy (#150) goes, as the platform sees it right now.
 * - [NOT_CHOSEN]: Android, before the user has picked a folder.
 * - [READY]: a folder (Android) or iCloud Drive (iOS) the app can write to.
 * - [LOST]: Android, a folder whose permission is gone (revoked, or the provider uninstalled).
 * - [UNAVAILABLE]: iOS, no iCloud Drive (signed out, turned off, or the app has no container).
 */
enum class BackupDestination { NOT_CHOSEN, READY, LOST, UNAVAILABLE }

/**
 * What the app remembers about the automatic copy (#150). Kept outside the cloud-backed
 * settings on Android: a folder's permission belongs to this phone.
 *
 * [lastBackupAt] is the last copy written (automatically or by "Back up now"); a manual Export
 * doesn't count, since the app never learns where the share sheet put it. [lastFingerprint]
 * and [lastFile] say what that copy held and what it is called, so an unchanged library isn't
 * written again. [lastFailed] is true when the latest attempt couldn't write.
 */
data class AutoBackupRecord(
    val enabled: Boolean = true,
    val folderUri: String? = null,
    val folderName: String? = null,
    val lastBackupAt: Long? = null,
    val lastFingerprint: String? = null,
    val lastFile: String? = null,
    val lastFailed: Boolean = false,
    /** Android's one-time "Keep a backup copy?" on Home has been answered (either way). */
    val folderPromptDone: Boolean = false
)

/**
 * The automatic copy's rules, pure so both platforms test them the same way (iOS mirrors this in
 * `AutoBackupPolicy.swift`). The platform decides *when* to ask ([isDue]); this decides whether
 * a copy is worth writing, what it is called, which old copies go, and what Settings says.
 */
object AutoBackupPolicy {
    /** Copies kept in the folder: the newest three, so one bad write never leaves nothing. */
    const val KEEP = 3

    /** A changed library is copied at most this often, however many times the app is left. */
    const val MIN_GAP_MS = 60L * 60 * 1000

    /** An unchanged library is still copied again this often, so the date stays true. */
    const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000

    /** Settings nudges when the last copy is older than this and nothing is copying. */
    const val NUDGE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    const val FILE_PREFIX = "recipe-clipper-backup-"
    private val FILE_NAME = Regex("""recipe-clipper-backup-\d{4}-\d{2}-\d{2}-\d{4}\.zip""")

    /** The automatic copy is actually being kept: on, and somewhere to put it. */
    fun isWorking(record: AutoBackupRecord, destination: BackupDestination): Boolean =
        record.enabled && destination == BackupDestination.READY

    /**
     * Whether a copy should be written now. Always for [force] ("Back up now"). Otherwise: never
     * copied, the last copy is gone from the folder, the library changed and the last copy is
     * at least [MIN_GAP_MS] old, or the last copy is [REFRESH_MS] old.
     */
    fun isDue(record: AutoBackupRecord, fingerprint: String, existing: List<String>, now: Long, force: Boolean = false): Boolean {
        if (force) return true
        val last = record.lastBackupAt ?: return true
        if (record.lastFile == null || record.lastFile !in existing) return true
        val age = now - last
        if (age >= REFRESH_MS || age < 0) return true
        return fingerprint != record.lastFingerprint && age >= MIN_GAP_MS
    }

    /** A gentle line in Settings: the last copy is over 30 days old and nothing is copying. */
    fun needsNudge(record: AutoBackupRecord, destination: BackupDestination, now: Long): Boolean {
        if (isWorking(record, destination)) return false
        val last = record.lastBackupAt ?: return false
        return now - last > NUDGE_AFTER_MS
    }

    /**
     * Android's one-time Home card ("Keep a backup copy?"): once there is a recipe to lose, the
     * copy is on but has no folder, and the card hasn't been answered.
     */
    fun offersFolderPrompt(record: AutoBackupRecord, destination: BackupDestination, libraryEmpty: Boolean): Boolean =
        !libraryEmpty && record.enabled && !record.folderPromptDone && destination == BackupDestination.NOT_CHOSEN

    /** `recipe-clipper-backup-2026-09-26-1430.zip`, in the phone's time zone: sorts by time. */
    fun fileName(now: Long): String =
        FILE_PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now)) + ".zip"

    /** True for a name this feature wrote; nothing else in the folder is ever touched. */
    fun isBackupFile(name: String): Boolean = FILE_NAME.matches(name)

    /** The app's own copies beyond the newest [KEEP], never [written] and never anything else. */
    fun toDelete(existing: List<String>, written: String): List<String> {
        val ours = existing.filter(::isBackupFile).distinct().sortedDescending()
        val keep = (listOf(written) + ours.filter { it != written }.take(KEEP - 1)).toSet()
        return ours.filter { it !in keep }
    }

    /**
     * What a copy holds, without the moment it was written: the export's JSON with its
     * `exportedAt` blanked, and the pictures' names. Equal fingerprints mean nothing changed.
     */
    fun fingerprint(json: String, photos: Collection<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(EXPORTED_AT.replace(json, "\"exportedAt\":0").toByteArray(Charsets.UTF_8))
        photos.sorted().forEach { digest.update(0); digest.update(it.toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val EXPORTED_AT = Regex(""""exportedAt"\s*:\s*-?\d+""")
}
