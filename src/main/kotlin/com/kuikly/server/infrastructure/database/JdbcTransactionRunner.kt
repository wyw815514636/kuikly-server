package com.kuikly.server.infrastructure.database

import com.kuikly.server.application.TransactionRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

internal class JdbcTransactionRunner(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher,
    private val queryTimeoutSeconds: Int,
) : TransactionRunner {
    override suspend fun <T> execute(block: () -> T): T = withContext(dispatcher) {
        val context = currentCoroutineContext()
        context.ensureActive()
        transaction(db = database) {
            maxAttempts = 1
            queryTimeout = queryTimeoutSeconds
            context.ensureActive()
            val result = block()
            context.ensureActive()
            result
        }
    }
}
