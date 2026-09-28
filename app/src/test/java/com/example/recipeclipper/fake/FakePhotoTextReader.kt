package com.example.recipeclipper.fake

import com.example.recipeclipper.data.PhotoTextReader
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.remote.PhotoLine
import kotlinx.coroutines.CompletableDeferred

/**
 * Answers [result] for every read, recording the pictures asked for. With [gate] set, a read
 * waits for it to complete first, so a test can look at the state mid-read or cancel it.
 */
class FakePhotoTextReader(var result: PhotoTextResult = PhotoTextResult.Read(emptyList())) : PhotoTextReader {

    val calls = mutableListOf<List<String>>()
    var gate: CompletableDeferred<Unit>? = null

    constructor(vararg lines: PhotoLine) : this(PhotoTextResult.Read(lines.toList()))

    override suspend fun read(imageUrls: List<String>): PhotoTextResult {
        calls += imageUrls
        gate?.await()
        return result
    }
}
