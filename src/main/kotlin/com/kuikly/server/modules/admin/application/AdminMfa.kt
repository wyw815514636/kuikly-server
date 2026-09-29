package com.kuikly.server.modules.admin.application
import com.kuikly.server.modules.admin.domain.*
import com.kuikly.server.modules.admin.infrastructure.AdminSql
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal suspend fun AdminService.enroll(token:String?,rawChallenge:String?):JsonObject=locked {
 val a=if(rawChallenge!=null)challenge(rawChallenge,"mfa-enroll").first else {val actor=actor(token?:unauthorized());recent(actor);actor.account}
 if(a.secret!=null)conflict("已启用两步验证")
 val key=if(rawChallenge!=null)crypto.digest(rawChallenge) else "enroll:${a.id}"
 val existing=AdminSql.query("SELECT secret FROM app.admin_challenges WHERE digest=? AND expires_at>?",key,now()){it.getString(1)}.singleOrNull()
 val secret=if(existing!=null)crypto.decrypt(existing,a.id) else crypto.secret()
 if(rawChallenge!=null)AdminSql.update("UPDATE app.admin_challenges SET secret=? WHERE digest=?",crypto.encrypt(secret,a.id),key)
 else AdminSql.update("INSERT INTO app.admin_challenges(digest,account_id,stage,expires_at,secret) VALUES (?,?,'mfa-enroll',?,?) ON CONFLICT(digest) DO UPDATE SET secret=EXCLUDED.secret,expires_at=EXCLUDED.expires_at,attempts=0",key,a.id,now()+300000,crypto.encrypt(secret,a.id))
 json("secret" to secret,"uri" to "otpauth://totp/Kuikly:${URLEncoder.encode(a.username,StandardCharsets.UTF_8)}?secret=$secret&issuer=Kuikly&algorithm=SHA1&digits=6&period=30")
}
internal suspend fun AdminService.confirmMfa(token:String?,rawChallenge:String?,code:String,key:String,trace:String,device:String):AdminOutcome {
 if(rawChallenge!=null)challengeAttempt(rawChallenge)
 return locked {
  val a=if(rawChallenge!=null){val state=challenge(rawChallenge);if(state.second !in setOf("mfa-enroll","mfa-finished"))unauthorized();state.first} else {val actor=actor(token?:unauthorized());recent(actor);actor.account}
  val challengeKey=if(rawChallenge!=null)crypto.digest(rawChallenge) else "enroll:${a.id}"
  val envelope=idempotent(a.id,"mfa-confirm",key,code) {
   if(a.secret!=null)conflict("已启用两步验证")
   val stored=AdminSql.query("SELECT secret FROM app.admin_challenges WHERE digest=? AND stage='mfa-enroll' AND expires_at>?",challengeKey,now()){it.getString(1)}.singleOrNull()?:unauthorized()
   val secret=crypto.decrypt(stored,a.id)
   val step=crypto.matchingStep(secret,code,now(),a.step)?:unauthorized()
   AdminSql.update("UPDATE app.admin_accounts SET mfa_secret=?,last_totp_step=?,version=version+1 WHERE id=?",crypto.encrypt(secret,a.id),step,a.id)
   AdminSql.update("DELETE FROM app.admin_mfa_recovery WHERE account_id=?",a.id)
   val codes=(1..10).map{crypto.randomToken()}
   codes.forEach{AdminSql.update("INSERT INTO app.admin_mfa_recovery(account_id,digest) VALUES (?,?)",a.id,crypto.digest(it))}
   repository.revoke(a.id)
   val issued=issue(repository.account(a.id)!!,device)
   if(rawChallenge!=null)AdminSql.update("INSERT INTO app.admin_challenges(digest,account_id,stage,expires_at) VALUES (?,?,'mfa-finished',?)",challengeKey,a.id,now()+300000)
   repository.audit(a.id,"mfa.enabled",a.id,"success",trace,now())
   json("body" to json("codes" to codes,"auth" to issued.body),"cookie" to issued.cookie)
  }.jsonObject
  val cookie=envelope.text("cookie")
  actor(cookie) // Never restore a session revoked after the original operation.
  AdminOutcome(envelope.getValue("body").jsonObject,cookie=cookie)
 }
}
internal suspend fun AdminService.enrollmentChallenge(token:String):String=asActor(token){a->
 recent(a);if(a.account.secret!=null)conflict("已启用两步验证")
 val raw=crypto.randomToken()
 AdminSql.update("INSERT INTO app.admin_challenges(digest,account_id,stage,expires_at) VALUES (?,?,'mfa-enroll',?)",crypto.digest(raw),a.account.id,now()+300000)
 raw
}
internal suspend fun AdminService.verifyMfa(raw:String,code:String,recovery:Boolean,trace:String,device:String):AdminOutcome {
 challengeAttempt(raw)
 return locked {
  val a=challenge(raw,"mfa-verify").first
  if(recovery){
   if(AdminSql.update("DELETE FROM app.admin_mfa_recovery WHERE account_id=? AND digest=?",a.id,crypto.digest(code))!=1)unauthorized()
  }else{
   val secret=crypto.decrypt(a.secret?:unauthorized(),a.id)
   val step=crypto.matchingStep(secret,code,now(),a.step)?:unauthorized()
   AdminSql.update("UPDATE app.admin_accounts SET last_totp_step=? WHERE id=?",step,a.id)
  }
  AdminSql.update("DELETE FROM app.admin_challenges WHERE digest=?",crypto.digest(raw))
  repository.audit(a.id,if(recovery)"mfa.recovery" else "mfa.verify",a.id,"success",trace,now())
  issue(a,device)
 }
}
