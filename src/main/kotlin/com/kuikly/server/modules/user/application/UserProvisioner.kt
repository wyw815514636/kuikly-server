package com.kuikly.server.modules.user.application

import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import com.kuikly.server.application.TransactionRunner
import com.kuikly.server.modules.auth.domain.Passwords
import com.kuikly.server.modules.user.domain.User
import com.kuikly.server.modules.user.domain.UserRepository
import com.kuikly.server.modules.user.domain.UserRole

class UserProvisioner(private val transactions: TransactionRunner, private val users: UserRepository,
    private val passwords: Passwords, private val newId: () -> String) {
    suspend fun create(username: String, password: String, role: UserRole): User {
        require(username.matches(Regex("[a-z0-9][a-z0-9_.-]{2,63}"))) { "Username must contain 3 to 64 lowercase ASCII letters, digits, dots, underscores or hyphens" }
        val user = User(newId(), username, passwords.hash(password), role, true)
        if (!transactions.execute { users.insert(user) })
            throw BusinessException(FailureKind.CONFLICT, "USERNAME_EXISTS", "账号已存在")
        return user
    }
}
