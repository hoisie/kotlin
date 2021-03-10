/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.utils

/**
 * General purpose implementation of a transitive closure
 * - Recursion free
 * - Predictable amount of allocations
 * - Handles loops and self references gracefully
 * @param edges: Producer function from one node to all its children. This implementation can handle loops and self references gracefully.
 * @return Note: No guarantees given about the order ot this [Set]
 */
@OptIn(ExperimentalStdlibApi::class)
internal inline fun <T> T.transitiveClosure(edges: T.() -> Iterable<T>): Set<T> {
    // Fast path when initial edges are empty
    val initialEdges = edges()
    if (initialEdges is Collection && initialEdges.isEmpty()) return emptySet()

    val queue = ArrayDeque<T>()
    val results = mutableSetOf<T>()
    queue.addAll(initialEdges)
    while (queue.isNotEmpty()) {
        val resolved = queue.removeFirst()
        if (results.add(resolved)) {
            queue.addAll(resolved.edges())
        }
    }

    return results.toSet()
}
