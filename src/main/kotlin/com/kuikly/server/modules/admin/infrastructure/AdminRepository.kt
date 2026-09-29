package com.kuikly.server.modules.admin.infrastructure
import com.kuikly.server.modules.admin.domain.*
import kotlinx.serialization.json.*
import java.sql.ResultSet
import java.util.UUID
internal class AdminRepository {
 fun account(id:String):AdminAccount?=AdminSql.query("SELECT * FROM app.admin_accounts WHERE id=?",id,map=::accountRow).singleOrNull()
 fun byUsername(username:String):AdminAccount?=AdminSql.query("SELECT * FROM app.admin_accounts WHERE username=?",username,map=::accountRow).singleOrNull()
 private fun accountRow(r:ResultSet)=AdminAccount(r.getString("id"),r.getString("username"),r.getString("display_name"),r.getString("password_hash"),r.getBoolean("enabled"),r.getLong("version"),r.getBoolean("must_change"),r.getString("mfa_secret"),r.getLong("last_totp_step"),r.getString("reset_digest"),r.getObject("reset_expires")?.let { (it as Number).toLong() })
 fun roleIds(id:String)=AdminSql.query("SELECT role_id FROM app.admin_account_roles WHERE account_id=?",id){it.getString(1)}
 fun permissions(id:String)=AdminSql.query("SELECT DISTINCT p.permission FROM app.admin_role_permissions p JOIN app.admin_account_roles r ON p.role_id=r.role_id WHERE r.account_id=?",id){it.getString(1)}.toSet()
 fun role(id:String):JsonObject=AdminSql.query("SELECT r.*, (SELECT count(*) FROM app.admin_account_roles a WHERE a.role_id=r.id) AS members FROM app.admin_roles r WHERE r.id=?",id){r->json("id" to r.getString("id"),"name" to r.getString("name"),"system" to r.getBoolean("system"),"version" to r.getLong("version"),"memberCount" to r.getInt("members"),"permissions" to AdminSql.query("SELECT permission FROM app.admin_role_permissions WHERE role_id=? ORDER BY permission",id){it.getString(1)})}.singleOrNull()?:missing()
 fun summary(a:AdminAccount)=json("id" to a.id,"username" to a.username,"displayName" to a.displayName,"enabled" to a.enabled,"version" to a.version,"roles" to roleIds(a.id).map(::role))
 fun identity(a:AdminAccount)=json("administrator" to summary(a),"permissions" to permissions(a.id).sorted(),"mfaEnabled" to (a.secret!=null))
 fun session(digest:String):AdminSession?=AdminSql.query("SELECT * FROM app.admin_sessions WHERE digest=?",digest){r->AdminSession(r.getString("id"),r.getString("account_id"),r.getString("csrf"),r.getLong("created_at"),r.getLong("last_active"),r.getLong("expires_at"),r.getLong("recent_at"),r.getBoolean("revoked"))}.singleOrNull()
 fun revoke(id:String){AdminSql.update("UPDATE app.admin_sessions SET revoked=true WHERE account_id=?",id);AdminSql.update("DELETE FROM app.admin_challenges WHERE account_id=?",id)}
 fun audit(actor:String?,action:String,target:String?,result:String,trace:String,now:Long,metadata:String="{}") {AdminSql.update("INSERT INTO app.admin_audit_events(id,actor_id,action,target_id,result,trace_id,occurred_at,metadata) VALUES (?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),actor,action,target,result,trace.take(128),now,metadata)}
 fun count(sql:String,vararg args:Any?)=AdminSql.query(sql,*args){it.getLong(1)}.single()
}
