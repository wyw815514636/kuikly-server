package com.kuikly.server.modules.admin.application

import com.kuikly.server.application.TransactionRunner
import com.kuikly.server.application.FailureKind
import com.kuikly.server.modules.admin.domain.*
import com.kuikly.server.modules.admin.infrastructure.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

internal class AdminService(val transactions:TransactionRunner,val crypto:AdminCrypto,val now:()->Long=System::currentTimeMillis,val idleMillis:Long=1_800_000,val sessionMillis:Long=43_200_000) {
 val repository=AdminRepository()
 private val hashCapacity=Semaphore(8)
 private val hashDispatcher=Dispatchers.Default.limitedParallelism(2)
 suspend fun <T> passwordWork(block:()->T):T {
  if(!hashCapacity.tryAcquire())fail(FailureKind.UNAVAILABLE,"AUTH_BUSY","认证服务繁忙，请稍后重试")
  return try { withContext(hashDispatcher){block()} } finally { hashCapacity.release() }
 }
 suspend fun <T> locked(block:()->T):T=transactions.execute { AdminSql.lock();block() }
 suspend fun bootstrap(username:String,password:String):String {
  if(!username.matches(Regex("[a-z0-9][a-z0-9_.-]{2,63}")))bad()
  val hash=passwordWork { crypto.hash(password) }
  return locked {
   if(repository.count("SELECT count(*) FROM app.admin_accounts")!=0L)conflict("已存在管理员，请通过后台创建账号")
   val id=UUID.randomUUID().toString()
   AdminSql.update("INSERT INTO app.admin_accounts(id,username,display_name,password_hash,created_at) VALUES (?,?,?,?,?)",id,username,username,hash,now())
   AdminSql.update("INSERT INTO app.admin_account_roles(account_id,role_id) VALUES (?,?)",id,SUPER_ROLE)
   repository.audit(id,"admin.bootstrap",id,"success","cli",now());id
  }
 }
 fun actor(token:String):AdminActor {
  val session=repository.session(crypto.digest(token))?:unauthorized()
  val account=repository.account(session.accountId)?:unauthorized()
  if(session.revoked||!account.enabled||account.mustChange||now()>=session.expires||now()-session.active>=idleMillis)unauthorized()
  val superAdmin=SUPER_ROLE in repository.roleIds(account.id)
  if(superAdmin&&account.secret==null)unauthorized()
  AdminSql.update("UPDATE app.admin_sessions SET last_active=? WHERE id=?",now(),session.id)
  return AdminActor(account,session,repository.permissions(account.id),superAdmin)
 }
 suspend fun <T> asActor(token:String,permission:String?=null,block:(AdminActor)->T):T=locked {
  val actor=actor(token);if(permission!=null&&permission !in actor.permissions)denied();block(actor)
 }
 suspend fun me(token:String):JsonObject=asActor(token){repository.identity(it.account)}
 suspend fun csrf(token:String):String=asActor(token){it.session.csrf}
 suspend fun logout(token:String){asActor(token){AdminSql.update("UPDATE app.admin_sessions SET revoked=true WHERE id=?",it.session.id)}}
 suspend fun limit(username:String,source:String) {
  val accepted=locked {
   listOf("account:$username" to 5,"source:$source" to 30).map { (raw,max)->
    val key=crypto.digest(raw);val bucket=now()/60000
    val count=AdminSql.query("INSERT INTO app.admin_rate_limits(key,bucket,attempts) VALUES (?,?,1) ON CONFLICT(key) DO UPDATE SET bucket=EXCLUDED.bucket,attempts=CASE WHEN app.admin_rate_limits.bucket=EXCLUDED.bucket THEN app.admin_rate_limits.attempts+1 ELSE 1 END RETURNING attempts",key,bucket){it.getInt(1)}.single()
    count<=max
   }.all { it }
  }
  if(!accepted)fail(FailureKind.TOO_MANY_REQUESTS,"LOGIN_THROTTLED","尝试过于频繁，请稍后重试")
 }
 suspend fun login(username:String,password:String,trace:String,source:String,device:String):AdminOutcome {
  if(username.length !in 3..64||password.length !in 1..128)bad()
  limit(username,source)
  val before=transactions.execute { repository.byUsername(username) }
  val matched=passwordWork { crypto.verify(password,before?.hash) }
  return locked {
   val a=repository.byUsername(username)
   val reset=a?.resetDigest!=null&&crypto.digest(password)==a.resetDigest&&now()<(a.resetExpires?:0)
   if(a==null||!a.enabled||a.hash!=before?.hash||(!matched&&!reset)) {
    repository.audit(null,"auth.login",crypto.digest(username),"failure",trace,now())
    null
   } else {
    if(reset)AdminSql.update("UPDATE app.admin_accounts SET reset_digest=NULL,reset_expires=NULL WHERE id=?",a.id)
    repository.audit(a.id,"auth.login",a.id,"success",trace,now())
    advance(a,device)
   }
  }?:fail(FailureKind.UNAUTHENTICATED,"INVALID_CREDENTIALS","账号或凭据不正确")
 }
 fun advance(a:AdminAccount,device:String):AdminOutcome {
  val stage=when {a.mustChange->"password-change";a.secret!=null->"mfa-verify";SUPER_ROLE in repository.roleIds(a.id)->"mfa-enroll";else->null}
  if(stage==null)return issue(a,device)
  val token=crypto.randomToken()
  AdminSql.update("INSERT INTO app.admin_challenges(digest,account_id,stage,expires_at) VALUES (?,?,?,?)",crypto.digest(token),a.id,stage,now()+300000)
  return AdminOutcome(json("next" to stage),challenge=token)
 }
 fun issue(a:AdminAccount,device:String):AdminOutcome {
  val token=crypto.randomToken();val csrf=crypto.randomToken()
  AdminSql.update("INSERT INTO app.admin_sessions(id,digest,account_id,csrf,created_at,last_active,expires_at,recent_at,device) VALUES (?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),crypto.digest(token),a.id,csrf,now(),now(),now()+sessionMillis,now(),device.filter { !it.isISOControl() }.take(160))
  return AdminOutcome(json("next" to "authenticated","identity" to repository.identity(a)),cookie=token)
 }
 fun challenge(raw:String,stage:String?=null):Pair<AdminAccount,String> {
  val row=AdminSql.query("SELECT account_id,stage,expires_at,attempts FROM app.admin_challenges WHERE digest=?",crypto.digest(raw)){r->listOf(r.getString(1),r.getString(2),r.getLong(3).toString(),r.getInt(4).toString())}.singleOrNull()?:unauthorized()
  if(now()>=row[2].toLong()||row[3].toInt()>5||(stage!=null&&row[1]!=stage))unauthorized()
  val account=repository.account(row[0])?:unauthorized();if(!account.enabled)unauthorized()
  return account to row[1]
 }
 suspend fun challengeAttempt(raw:String) {
  val count=locked {AdminSql.query("UPDATE app.admin_challenges SET attempts=attempts+1 WHERE digest=? AND expires_at>? RETURNING attempts",crypto.digest(raw),now()){it.getInt(1)}.singleOrNull()}
  if(count==null||count>5)unauthorized()
 }
 suspend fun initialPassword(raw:String,password:String,trace:String,device:String):AdminOutcome {
  if(password.length !in 12..128)bad("密码长度应为 12–128 个字符")
  locked { challenge(raw,"password-change") }
  challengeAttempt(raw)
  val hash=passwordWork { crypto.hash(password) }
  return locked {
   val a=challenge(raw,"password-change").first
   AdminSql.update("UPDATE app.admin_accounts SET password_hash=?,must_change=false,reset_digest=NULL,reset_expires=NULL,version=version+1 WHERE id=?",hash,a.id)
   repository.revoke(a.id);repository.audit(a.id,"auth.initial-password",a.id,"success",trace,now())
   advance(repository.account(a.id)!!,device)
  }
 }
 fun recent(actor:AdminActor){if(now()-actor.session.recent>=300000)fail(FailureKind.FORBIDDEN,"REAUTH_REQUIRED","请先重新验证身份")}
 fun <T:JsonElement> idempotent(actorId:String,operation:String,key:String,fingerprint:String,block:()->T):JsonElement {
  validId(key)
  val found=AdminSql.query("SELECT fingerprint,ciphertext,expires_at FROM app.admin_idempotency WHERE account_id=? AND operation=? AND key=?",actorId,operation,key){r->Triple(r.getString(1),r.getString(2),r.getLong(3))}.singleOrNull()
  if(found!=null){if(found.first!=crypto.digest(fingerprint)||found.third<=now()||found.second==null)conflict("操作凭据已使用或结果已过期，请刷新状态");return Json.parseToJsonElement(crypto.decrypt(found.second,"$actorId:$operation:$key"))}
  val result=block()
  AdminSql.update("INSERT INTO app.admin_idempotency(account_id,operation,key,fingerprint,ciphertext,expires_at,retain_until) VALUES (?,?,?,?,?,?,?)",actorId,operation,key,crypto.digest(fingerprint),crypto.encrypt(result.toString(),"$actorId:$operation:$key"),now()+300000,now()+86_400_000)
  return result
 }
 suspend fun deniedAudit(trace:String,action:String){locked {repository.audit(null,action,null,"denied",trace,now())}}
}
