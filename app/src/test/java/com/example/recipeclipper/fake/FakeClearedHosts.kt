package com.example.recipeclipper.fake

import com.example.recipeclipper.data.ClearedHosts

/** In-memory [ClearedHosts] (#220): [cleared] start cleared; [recorded] lists every record. */
class FakeClearedHosts(vararg cleared: String) : ClearedHosts {
    private val hosts = cleared.toMutableSet()
    val recorded = mutableListOf<String>()

    override fun isCleared(host: String) = host in hosts

    override fun record(host: String) {
        recorded += host
        hosts += host
    }
}
