package com.kuikly.server

import com.kuikly.server.config.DatabaseSettings
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import com.kuikly.server.modules.admin.application.*
import com.kuikly.server.modules.admin.domain.*
import com.kuikly.server.modules.admin.infrastructure.AdminCrypto
import com.kuikly.server.application.BusinessException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

@Tag("integration") @Testcontainers
class AdminSecurityRegressionTest {
 companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:17.11-alpine") }
 @Test fun `MFA confirmation retry returns same recovery codes and session only for identical operation`()=fixture {s,c,clock->
  s.bootstrap("root","initial test password")
  val start=s.login("root","initial test password","trace","local","browser")
  val change=s.initialPassword(start.challenge!!,"replacement test password","trace","browser")
  val enrollment=s.enroll(null,change.challenge)
  val key=UUID.randomUUID().toString();val code=c.totp(enrollment.text("secret"),clock.get())
  val result=s.confirmMfa(null,change.challenge,code,key,"trace","browser")
  val retry=s.confirmMfa(null,change.challenge,code,key,"trace","browser")
  assertEquals(result.body,retry.body);assertEquals(result.cookie,retry.cookie)
  assertFailsWith<BusinessException>{s.confirmMfa(null,change.challenge,code,UUID.randomUUID().toString(),"trace","browser")}
 }
 @Test fun `concurrent last-superadministrator role changes leave a usable administrator`()=fixture {s,c,clock->
  val root=onboard(s,c,clock,"root",true)
  val second=s.createAdministrator(root,json("username" to "second","displayName" to "Second","password" to "initial test password","roleIds" to listOf(SUPER_ROLE)),UUID.randomUUID().toString(),"t")
  val token2=onboard(s,c,clock,"second",false)
  val rootId=s.me(root).getValue("administrator").jsonObject.text("id")
  val outcomes=coroutineScope {listOf(async{runCatching{s.assignRoles(root,rootId,json("roleIds" to emptyList<String>(),"version" to 2),"t")}},async{runCatching{s.assignRoles(token2,second.text("id"),json("roleIds" to emptyList<String>(),"version" to 2),"t")}}).awaitAll()}
  assertEquals(1,outcomes.count{it.isSuccess});assertEquals(1,outcomes.count{it.isFailure})
 }
 @Test fun `password change invalidates all devices`()=fixture {s,c,clock->
  val root=onboard(s,c,clock,"root",true)
  s.changePassword(root,"replacement test password","another replacement password","trace")
  assertFailsWith<BusinessException>{s.me(root)}
 }
 @Test fun `self password reset cannot destroy the only administrator recovery path`()=fixture {s,c,clock->
  val root=onboard(s,c,clock,"root",true)
  val id=s.me(root).getValue("administrator").jsonObject.text("id")
  assertFailsWith<BusinessException>{s.resetPassword(root,id,UUID.randomUUID().toString(),"trace")}
  assertEquals("root",s.me(root).getValue("administrator").jsonObject.text("username"))
 }
 @Test fun `expired reset account cannot stand in for the last usable superadministrator`()=fixture {s,c,clock->
  val root=onboard(s,c,clock,"root",true)
  val second=s.createAdministrator(root,json("username" to "second","displayName" to "Second","password" to "initial test password","roleIds" to listOf(SUPER_ROLE)),UUID.randomUUID().toString(),"t")
  onboard(s,c,clock,"second",false)
  s.resetPassword(root,second.text("id"),UUID.randomUUID().toString(),"t")
  clock.addAndGet(900001)
  val rootAccount=s.me(root).getValue("administrator").jsonObject
  assertFailsWith<BusinessException>{s.assignRoles(root,rootAccount.text("id"),json("roleIds" to emptyList<String>(),"version" to rootAccount.number("version")),"t")}
 }
 @Test fun `invalid challenge is rejected before consuming scarce password workers`()=fixture {s,_,_->
  val latch=java.util.concurrent.CountDownLatch(1)
  coroutineScope {
   val busy=(1..8).map{async(start=CoroutineStart.UNDISPATCHED){s.passwordWork{latch.await(10,java.util.concurrent.TimeUnit.SECONDS)}}}
   try {assertEquals("UNAUTHENTICATED",assertFailsWith<BusinessException>{s.initialPassword("invalid","new password value","t","browser")}.code)}
   finally {latch.countDown();busy.awaitAll()}
  }
 }
 @Test fun `recovery codes are single use even across different login challenges`()=fixture {s,c,clock->
  s.bootstrap("root","initial test password")
  val first=s.login("root","initial test password","t","local","browser")
  val changed=s.initialPassword(first.challenge!!,"replacement test password","t","browser")
  val enrollment=s.enroll(null,changed.challenge)
  val confirmed=s.confirmMfa(null,changed.challenge,c.totp(enrollment.text("secret"),clock.get()),UUID.randomUUID().toString(),"t","browser")
  val recovery=confirmed.body.getValue("codes").jsonArray.first().jsonPrimitive.content
  val one=s.login("root","replacement test password","t","local","browser")
  val two=s.login("root","replacement test password","t","local","browser")
  val result=coroutineScope {listOf(one,two).map{challenge->async{runCatching{s.verifyMfa(challenge.challenge!!,recovery,true,"t","browser")}}}.awaitAll()}
  assertEquals(1,result.count{it.isSuccess});assertEquals(1,result.count{it.isFailure})
 }
 @Test fun `reauthentication mistakes preserve session and use a durable failure limit`()=fixture {s,c,clock->
  val root=onboard(s,c,clock,"root",true)
  repeat(5){assertEquals(com.kuikly.server.application.FailureKind.FORBIDDEN,assertFailsWith<BusinessException>{s.reauthenticate(root,"wrong",null)}.kind)}
  assertEquals("LOGIN_THROTTLED",assertFailsWith<BusinessException>{s.reauthenticate(root,"wrong",null)}.code)
  assertNotNull(s.me(root))
 }
 private suspend fun onboard(s:AdminService,c:AdminCrypto,clock:AtomicLong,username:String,bootstrap:Boolean):String {
  if(bootstrap)s.bootstrap(username,"initial test password")
  val login=s.login(username,"initial test password","t",username,"browser")
  val change=s.initialPassword(login.challenge!!,"replacement test password","t","browser")
  val enrollment=s.enroll(null,change.challenge)
  return s.confirmMfa(null,change.challenge,c.totp(enrollment.text("secret"),clock.get()),UUID.randomUUID().toString(),"t","browser").cookie!!
 }
 private fun fixture(block:suspend(AdminService,AdminCrypto,AtomicLong)->Unit)=runBlocking {
  val name="security_"+UUID.randomUUID().toString().replace("-","")
  DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).use{it.createStatement().use{s->s.execute("CREATE DATABASE $name")}}
  val settings=DatabaseSettings("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$name",postgres.username,postgres.password)
  DatabaseRuntime.migrate(settings)
  DatabaseRuntime.open(settings).use{db->val clock=AtomicLong(1_800_000_000_000);val c=AdminCrypto(ByteArray(32){9});block(AdminService(db.transactions,c,clock::get),c,clock)}
 }
}
