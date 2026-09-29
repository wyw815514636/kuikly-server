package com.kuikly.server.modules.admin.infrastructure

import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.sql.ResultSet

/** Prepared SQL bound to the existing Exposed transaction; never owns a separate connection. */
internal object AdminSql {
 private fun connection() = TransactionManager.current().connection.connection as Connection
 fun update(sql:String,vararg args:Any?):Int = connection().prepareStatement(sql).use { s ->
  s.queryTimeout=5; args.forEachIndexed { i,v -> s.setObject(i+1,v) };s.executeUpdate()
 }
 fun <T> query(sql:String,vararg args:Any?,map:(ResultSet)->T):List<T> = connection().prepareStatement(sql).use { s ->
  s.queryTimeout=5;args.forEachIndexed { i,v -> s.setObject(i+1,v) };s.executeQuery().use { r -> buildList { while(r.next())add(map(r)) } }
 }
 fun lock() { query("SELECT pg_advisory_xact_lock(891514636)") { true } }
}
