package com.kuikly.server.modules.user.application

import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import com.kuikly.server.application.TransactionRunner
import com.kuikly.server.modules.user.domain.User
import com.kuikly.server.modules.user.domain.UserRepository
import com.kuikly.server.modules.user.domain.UserRole

class UserService(private val transactions: TransactionRunner, private val users: UserRepository) {
    suspend fun findVisible(actor: User, id: String): User {
        if (actor.id != id && actor.role != UserRole.ADMIN) notFound()
        return transactions.execute { users.findById(id) } ?: notFound()
    }

    fun requireAdmin(actor: User) {
        if (actor.role != UserRole.ADMIN) throw BusinessException(FailureKind.FORBIDDEN, "FORBIDDEN", "没有访问权限")
    }

    private fun notFound(): Nothing = throw BusinessException(FailureKind.NOT_FOUND, "USER_NOT_FOUND", "用户不存在")
}
