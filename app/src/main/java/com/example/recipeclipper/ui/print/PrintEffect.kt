package com.example.recipeclipper.ui.print

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.recipeclipper.data.backup.ShareFile
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.ui.common.LocalFlagValues
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The platform half of printing (#318): draws [PrintViewModel]'s job into a PDF, then opens the
 * system print dialog on a recipe (which also offers "Save as PDF") or the share sheet on a
 * cookbook, as Send as file does. [onFailed] (a snackbar) when it couldn't be made. Leaving the
 * screen (not rotating it) drops the job (#331): the dialog or the sheet must never pop up on
 * return, minutes later; the cook asks again.
 */
@Composable
fun PrintEffect(viewModel: PrintViewModel, onFailed: suspend () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val printer = LocalPrinter.current
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.onLeave() }
    }
    LaunchedEffect(state.job) {
        val job = state.job ?: return@LaunchedEffect
        val ok = try {
            printer.print(context, job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (ok) viewModel.onDone() else viewModel.onFailed()
    }
    // The line stays up for its whole time: the flag is lowered only once it has gone (#331).
    LaunchedEffect(state.failed) {
        if (!state.failed) return@LaunchedEffect
        onFailed()
        viewModel.onFailureShown()
    }
}

/**
 * The route's [PrintViewModel], or null with the `print` flag off (#87): what every route that
 * shows a recipe or a list passes, so none forgets the menu entry (#331; `PrintWiringTest`).
 */
@Composable
fun printViewModelIfOn(): PrintViewModel? = if (LocalFlagValues.current.isOn(Flag.PRINT)) hiltViewModel() else null

/**
 * Draws a [PrintJob] and hands it on: the print dialog or the share sheet. True once handed on.
 * A screen test provides its own through [LocalPrinter], since a JVM test has no PDF engine.
 */
fun interface Printer {
    suspend fun print(context: Context, job: PrintJob): Boolean
}

/** The system's: [PrintPdf], then the print dialog or the share sheet. */
val SystemPrinter = Printer { context, job ->
    when (job) {
        is PrintJob.Recipe -> printRecipe(context, job)
        is PrintJob.Cookbook -> shareCookbook(context, job)
    }
}

val LocalPrinter = staticCompositionLocalOf { SystemPrinter }

private suspend fun printRecipe(context: Context, job: PrintJob.Recipe): Boolean {
    val file = File(File(context.cacheDir, PRINT_DIR).apply { deleteRecursively() }, ShareFile.fileName(job.title, "pdf"))
    if (!PrintPdf(context).recipe(job.blocks, file)) return false
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
    printManager.print(job.title, PdfFileAdapter(file, file.name), PrintAttributes.Builder().build())
    return true
}

private suspend fun shareCookbook(context: Context, job: PrintJob.Cookbook): Boolean {
    val file = File(File(context.cacheDir, COOKBOOK_DIR).apply { deleteRecursively() }, ShareFile.fileName(job.title, "pdf"))
    if (!PrintPdf(context).cookbook(job.title, job.contents, job.recipes, file)) return false
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = PDF
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(send, file.name))
    } catch (e: ActivityNotFoundException) {
        // Nothing can receive a file: nothing to do.
    }
    return true
}

/** Hands the print framework the PDF already drawn, whatever paper is picked (it scales to fit). */
private class PdfFileAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) return callback.onLayoutCancelled()
        val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        try {
            file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            callback.onWriteFailed(e.message)
        }
    }
}

private const val PRINT_DIR = "exports/print"
private const val COOKBOOK_DIR = "exports/cookbook"
private const val PDF = "application/pdf"
