package com.kuikly.server.application

interface TransactionRunner {
    suspend fun <T> execute(block: () -> T): T
}
