package com.kuikly.server.modules.admin.application
import com.kuikly.server.modules.admin.domain.*
import com.kuikly.server.modules.admin.infrastructure.AdminSql
import kotlinx.serialization.json.*

internal suspend fun AdminService.updateProfile(token:String,request:JsonObject,trace:String):JsonObject=asActor(token){a->
 request.fields("displayName","version");checkVersion(a.account.version,request)
 AdminSql.update("UPDATE app.admin_accounts SET display_name=?,version=version+1 WHERE id=?",validName(request.text("displayName")),a.account.id)
 repository.audit(a.account.id,"profile.update",a.account.id,"success",trace,now());repository.identity(repository.account(a.account.id)!!)
}
internal suspend fun AdminService.reauthenticate(token:String,password:String,code:String?){
 val before=asActor(token){it.account}
 limit("reauth:${before.id}","reauth:${before.id}")
 val matched=passwordWork { crypto.verify(password,before.hash) }
 asActor(token){a->
  if(!matched||before.hash!=a.account.hash)denied()
  if(a.account.secret!=null){val step=crypto.matchingStep(crypto.decrypt(a.account.secret,a.account.id),code.orEmpty(),now(),a.account.step)?:denied();AdminSql.update("UPDATE app.admin_accounts SET last_totp_step=? WHERE id=?",step,a.account.id)}
  AdminSql.update("UPDATE app.admin_sessions SET recent_at=? WHERE id=?",now(),a.session.id)
 }
}
internal suspend fun AdminService.changePassword(token:String,old:String,password:String,trace:String){
 if(password.length !in 12..128)bad("密码长度应为 12–128 个字符")
 val before=asActor(token){recent(it);it.account}
 val matched=passwordWork { crypto.verify(old,before.hash) };if(!matched)unauthorized()
 val hash=passwordWork { crypto.hash(password) }
 asActor(token){a->recent(a);if(before.hash!=a.account.hash)conflict()
  AdminSql.update("UPDATE app.admin_accounts SET password_hash=?,version=version+1,reset_digest=NULL,reset_expires=NULL WHERE id=?",hash,a.account.id)
  repository.revoke(a.account.id);repository.audit(a.account.id,"profile.password",a.account.id,"success",trace,now())
 }
}
internal suspend fun AdminService.sessions(token:String):JsonArray=asActor(token){a->JsonArray(AdminSql.query("SELECT * FROM app.admin_sessions WHERE account_id=? AND revoked=false AND expires_at>? AND last_active>? ORDER BY created_at DESC,id",a.account.id,now(),now()-idleMillis){r->json("id" to r.getString("id"),"createdAt" to iso(r.getLong("created_at")),"lastActiveAt" to iso(r.getLong("last_active")),"expiresAt" to iso(r.getLong("expires_at")),"current" to (r.getString("id")==a.session.id),"device" to r.getString("device"))})}
internal suspend fun AdminService.revokeSession(token:String,id:String?,others:Boolean,trace:String){asActor(token){a->
 if(others)AdminSql.update("UPDATE app.admin_sessions SET revoked=true WHERE account_id=? AND id<>?",a.account.id,a.session.id)
 else {validId(id?:bad());if(AdminSql.update("UPDATE app.admin_sessions SET revoked=true WHERE account_id=? AND id=?",a.account.id,id)!=1)missing()}
 repository.audit(a.account.id,"session.revoke",id,"success",trace,now())
}}
internal suspend fun AdminService.disableMfa(token:String,trace:String){asActor(token){a->
 recent(a);if(a.superAdmin)conflict("超级管理员必须保留两步验证，请使用受控恢复流程")
 AdminSql.update("UPDATE app.admin_accounts SET mfa_secret=NULL,last_totp_step=-1,version=version+1 WHERE id=?",a.account.id)
 AdminSql.update("DELETE FROM app.admin_mfa_recovery WHERE account_id=?",a.account.id)
 repository.revoke(a.account.id);repository.audit(a.account.id,"mfa.disabled",a.account.id,"success",trace,now())
}}
