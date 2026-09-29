package com.kuikly.server.modules.user.domain

enum class UserRole { USER, ADMIN }

data class User(val id: String, val username: String, val passwordHash: String, val role: UserRole, val enabled: Boolean) {
    override fun toString(): String = "User(id=$id, role=$role, enabled=$enabled)"
}

interface UserRepository {
    fun findByUsername(username: String): User?
    fun findById(id: String, lock: Boolean = false): User?
    fun insert(user: User): Boolean
}
