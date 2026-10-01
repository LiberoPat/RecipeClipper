package com.example.recipeclipper.walkthrough

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.core.content.FileProvider
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.StepShortener
import com.example.recipeclipper.data.backup.ShareFile
import com.example.recipeclipper.di.OnDeviceModelModule
import com.example.recipeclipper.fake.FakeDecisionModel
import com.example.recipeclipper.fake.FakeStepShortener
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Test
import java.io.File

/**
 * Walkthroughs 14–16 and 19 (#106): the automatic backup copy and restoring on an empty Home
 * (#150), sending and pasting a grocery list (#149), a recipe sent as a file and a file received
 * (#149), and the Pantry's Send list (#149). The system pickers and the share sheet show but
 * aren't driven: each is closed with Back. The model supports nothing here.
 */
@HiltAndroidTest
@UninstallModules(OnDeviceModelModule::class)
class SharingWalkthroughTest : WalkthroughBase() {

    @BindValue @JvmField
    val decisionModel: DecisionModel = FakeDecisionModel(languages = emptySet())

    @BindValue @JvmField
    val shortener: StepShortener = FakeStepShortener(support = ChefSupport.Unsupported)

    /** [text] on the clipboard, as if copied from a message. */
    private fun copy(text: String) {
        scenario!!.onActivity {
            it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("list", text))
        }
        pause(2500)
    }

    /** A Recipe Clipper file opened from another app (Messages, Files): the VIEW intent it sends. */
    private fun receive(bytes: ByteArray, name: String) {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "exports/$name").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        startFromApp(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, ShareFile.MIME_TYPE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    /** The automatic backup copy (#150): restore on an empty Home, then Settings' rows. */
    @Test
    fun test14_automaticBackup() {
        start(seeded = false)
        waitFor(hasText("Restore from a backup file"))
        pause(1500)
        tap("Restore from a backup file", 0)
        waitForSystemScreen() // the system's file picker
        systemBack()
        tapDescription("Settings")
        show("Back up now", 3000)
        tap("Backup folder", 0)
        waitForSystemScreen() // the system's folder picker
        systemBack(2000)
    }

    /** "Send list" (the share sheet, with the list as text), then a list pasted in (#149). */
    @Test
    fun test15_sendAndPasteAList() {
        start(kitchen = true)
        tap("Groceries", 2000)
        menu("Send list")
        waitForSystemScreen() // the share sheet
        systemBack()
        copy(FRIENDS_LIST)
        menu("Paste a list")
        waitFor(hasText("Add this list"))
        pause(2000)
        tapTag("receiveLine-2") // unticked: stays out
        tapTag("receiveToGroceries")
        pause(2500)
    }

    /** A recipe's "Send as file" (the share sheet), then a file received: "Add from this file" (#149). */
    @Test
    fun test16_sendAndReceiveAFile() {
        start()
        tap("Chicken Adobo")
        menu("Send as file")
        waitForSystemScreen() // the share sheet, with "Chicken Adobo.recipeclipper"
        systemBack()
        back()
        receive(deviceFile(SHARE_FIXTURE), "Sheet-pan chicken.recipeclipper")
        waitFor(hasText("Add from this file"))
        pause(2500)
        tapTag("receiveRow-g:g-paper") // unticked: stays out
        tapTag("receiveFileAdd")
        pause(3000)
    }

    /** The Pantry's menu sends what's in stock (#149): Send list, then Send as file. */
    @Test
    fun test19_pantrySendList() {
        start(kitchen = true)
        tap("Pantry", 2000)
        menu("Send list")
        waitForSystemScreen() // the share sheet: what's in stock, by aisle
        systemBack()
        menu("Send as file")
        waitForSystemScreen() // the share sheet, with "Pantry.recipeclipper"
        systemBack()
    }

    companion object {
        /** Where the recording script puts `shared/fixtures/backup/share-v1.recipeclipper`. */
        const val SHARE_FIXTURE = "/data/local/tmp/share-v1.recipeclipper"

        /** A list someone sent, as "Send list" writes one. */
        const val FRIENDS_LIST = "Groceries\n\nDairy & eggs\n- 6 eggs\n- 1 cup plain yogurt\n\nFruit & vegetables\n- 2 lemons"
    }
}
