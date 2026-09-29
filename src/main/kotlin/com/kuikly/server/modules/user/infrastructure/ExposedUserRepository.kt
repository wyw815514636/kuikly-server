package com.kuikly.server.modules.user.infrastructure

import com.kuikly.server.modules.user.domain.User
import com.kuikly.server.modules.user.domain.UserRepository
import com.kuikly.server.modules.user.domain.UserRole
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

class ExposedUserRepository : UserRepository {
    override fun findByUsername(username: String): User? = Users.selectAll().where { Users.username eq username }.singleOrNull()?.user()
    override fun findById(id: String, lock: Boolean): User? {
        val query = Users.selectAll().where { Users.id eq id }
        if (lock) query.forUpdate()
        return query.singleOrNull()?.user()
    }
    override fun insert(user: User): Boolean = Users.insertIgnore {
        it[id] = user.id
        it[username] = user.username
        it[passwordHash] = user.passwordHash
        it[role] = user.role.name
        it[enabled] = user.enabled
    }.insertedCount == 1
}

private object Users : Table("app.users") {
    val id = varchar("id", 36)
    val username = varchar("username", 64)
    val passwordHash = varchar("password_hash", 256)
    val role = varchar("role", 16)
    val enabled = bool("enabled")
    override val primaryKey = PrimaryKey(id)
}

private fun ResultRow.user() = User(this[Users.id], this[Users.username], this[Users.passwordHash],
    UserRole.valueOf(this[Users.role]), this[Users.enabled])
