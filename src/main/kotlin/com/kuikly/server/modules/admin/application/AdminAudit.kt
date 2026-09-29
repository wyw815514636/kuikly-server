package com.kuikly.server.modules.admin.application
import com.kuikly.server.modules.admin.domain.*
import com.kuikly.server.modules.admin.infrastructure.AdminSql
import kotlinx.serialization.json.*
import java.sql.ResultSet
import java.time.Instant
private fun auditRow(r:ResultSet)=json("id" to r.getString("id"),"actorId" to r.getString("actor_id"),"action" to r.getString("action"),"targetId" to r.getString("target_id"),"result" to r.getString("result"),"traceId" to r.getString("trace_id"),"occurredAt" to iso(r.getLong("occurred_at")),"metadata" to r.getString("metadata"))
internal suspend fun AdminService.listAudit(token:String,page:Int,size:Int,filters:Map<String,String>):JsonObject=asActor(token,"audit:read") {
 pagination(page,size);val where=mutableListOf("1=1");val args=mutableListOf<Any?>()
 for((key,column) in listOf("actorId" to "actor_id","action" to "action","result" to "result")){filters[key]?.takeIf{it.isNotBlank()}?.let{if(it.length>128)bad();where+="$column=?";args+=it}}
 for((key,operator) in listOf("from" to ">=","to" to "<=")){filters[key]?.takeIf{it.isNotBlank()}?.let{val time=runCatching {Instant.parse(it).toEpochMilli()}.getOrElse{bad()};where+="occurred_at$operator?";args+=time}}
 val clause=where.joinToString(" AND ")
 json("items" to AdminSql.query("SELECT * FROM app.admin_audit_events WHERE $clause ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",*(args+listOf(size,(page-1)*size)).toTypedArray(),map=::auditRow),"total" to repository.count("SELECT count(*) FROM app.admin_audit_events WHERE $clause",*args.toTypedArray()),"page" to page,"pageSize" to size)
}
internal suspend fun AdminService.getAudit(token:String,id:String):JsonObject=asActor(token,"audit:read"){validId(id);AdminSql.query("SELECT * FROM app.admin_audit_events WHERE id=?",id,map=::auditRow).singleOrNull()?:missing()}
internal suspend fun AdminService.prune(){locked {
 AdminSql.update("DELETE FROM app.admin_challenges WHERE digest IN (SELECT digest FROM app.admin_challenges WHERE expires_at<? LIMIT 100)",now())
 AdminSql.update("UPDATE app.admin_idempotency SET ciphertext=NULL WHERE (account_id,operation,key) IN (SELECT account_id,operation,key FROM app.admin_idempotency WHERE expires_at<? AND ciphertext IS NOT NULL LIMIT 100)",now())
 AdminSql.update("DELETE FROM app.admin_idempotency WHERE (account_id,operation,key) IN (SELECT account_id,operation,key FROM app.admin_idempotency WHERE retain_until<? LIMIT 100)",now())
 AdminSql.update("DELETE FROM app.admin_rate_limits WHERE key IN (SELECT key FROM app.admin_rate_limits WHERE bucket<? LIMIT 100)",now()/60000-2)
 AdminSql.update("DELETE FROM app.admin_sessions WHERE id IN (SELECT id FROM app.admin_sessions WHERE expires_at<? OR revoked=true LIMIT 100)",now())
}}
