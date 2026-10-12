package com.accessbridgeai.agent

import com.accessbridgeai.screen.RawScreen

/** The only way primitives touch a phone. Node ids always refer to the LAST screen returned. */
interface Device {
    suspend fun read(): RawScreen?                       // null = the target app is not in front
    suspend fun tap(nodeId: Int): Boolean
    suspend fun type(nodeId: Int, text: String): Boolean
    suspend fun submit(nodeId: Int): Boolean             // press the keyboard's Enter/Search key in this field
    suspend fun settle(before: RawScreen): RawScreen?    // wait until the screen differs from `before`
}