package com.example.recipeclipper.fake

import com.example.recipeclipper.data.ShareFileRepository
import com.example.recipeclipper.data.backup.Backup
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ImportSummary
import com.example.recipeclipper.data.backup.ShareChoice

/** Answers with what the test staged (#149), recording what it was asked. */
class FakeShareFileRepository : ShareFileRepository {

    /** The file text per recipe id; a missing one reads as gone. */
    val recipeFiles = mutableMapOf<Long, String>()

    /** The grocery list's file; null when nothing is left to buy. */
    var groceriesFile: String? = null

    var receiveResult: BackupResult<ImportSummary> = BackupResult.Success(ImportSummary(0, 0, 0, 0))

    /** Every [receive] call's choice. */
    val received = mutableListOf<ShareChoice>()

    override suspend fun recipeFile(recipeId: Long): String? = recipeFiles[recipeId]

    override suspend fun groceriesFile(): String? = groceriesFile

    override suspend fun receive(file: Backup, choice: ShareChoice): BackupResult<ImportSummary> {
        received += choice
        return receiveResult
    }
}
