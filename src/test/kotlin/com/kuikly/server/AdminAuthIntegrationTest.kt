package com.kuikly.server

import com.kuikly.server.config.DatabaseSettings
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import com.kuikly.server.modules.admin.application.*
import com.kuikly.server.modules.admin.infrastructure.AdminCrypto
import com.kuikly.server.modules.admin.domain.*
import com.kuikly.server.application.BusinessException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
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
class AdminAuthIntegrationTest {
 companion object { @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:17.11-alpine") }
 @Test fun `first password and MFA gates precede full session and expiry is enforced`()=fixture { s,c,clock ->
  s.bootstrap("root","initial long password")
  val login=s.login("root","initial long password","test","local","browser")
  assertEquals("password-change",login.body.text("next"));assertNull(login.cookie)
  val changed=s.initialPassword(login.challenge!!,"replacement long password","trace","browser")
  assertEquals("mfa-enroll",changed.body.text("next"));assertNull(changed.cookie)
  val enrollment=s.enroll(null,changed.challenge!!)
  val result=s.confirmMfa(null,changed.challenge,c.totp(enrollment.text("secret"),clock.get()),UUID.randomUUID().toString(),"trace","browser")
  val cookie=assertNotNull(result.cookie)
  assertEquals("authenticated",result.body.getValue("auth").jsonObject.text("next"))
  assertEquals("root",s.me(cookie).getValue("administrator").jsonObject.text("username"))
  clock.addAndGet(1_800_001)
  assertFailsWith<BusinessException>{s.me(cookie)}
 }
 @Test fun `login errors are generic and five attempts are enforced across service instances`()=fixture { s,_,clock ->
  s.bootstrap("root","initial long password")
  val known=assertFailsWith<BusinessException>{s.login("root","wrong","t","local","browser")}
  val unknown=assertFailsWith<BusinessException>{s.login("unknown","wrong","t","local","browser")}
  assertEquals(known.code,unknown.code)
  repeat(4){assertFailsWith<BusinessException>{s.login("root","wrong","t","local","browser")}}
  assertEquals("LOGIN_THROTTLED",assertFailsWith<BusinessException>{s.login("root","initial long password","t","local","browser")}.code)
  clock.addAndGet(60_001)
  assertNotNull(s.login("root","initial long password","t","local","browser").challenge)
 }
 @Test fun `management enforces grants optimistic versions revocation and audit`()=fixture { s,c,clock ->
  s.bootstrap("root","initial long password")
  val first=s.login("root","initial long password","t","local","browser")
  val changed=s.initialPassword(first.challenge!!,"replacement long password","t","browser")
  val enrollment=s.enroll(null,changed.challenge)
  val root=s.confirmMfa(null,changed.challenge,c.totp(enrollment.text("secret"),clock.get()),UUID.randomUUID().toString(),"t","browser").cookie!!
  val role=s.createRole(root,json("name" to "只读管理员","permissions" to listOf("admin:read")),"t")
  val request=json("username" to "reader","displayName" to "只读用户","password" to "reader long password","roleIds" to listOf(role.text("id")))
  val key=UUID.randomUUID().toString()
  val created=s.createAdministrator(root,request,key,"t")
  assertEquals(created,s.createAdministrator(root,request,key,"t"))
  val login=s.login("reader","reader long password","t","reader-source","browser")
  val reader=s.initialPassword(login.challenge!!,"reader new password","t","browser").cookie!!
  assertEquals(2L,s.listAdministrators(reader,1,20,"",null).number("total"))
  assertFailsWith<BusinessException>{s.createRole(reader,json("name" to "bad","permissions" to listOf("admin:create")),"t")}
  val updated=s.updateAdministrator(root,created.text("id"),json("displayName" to "新名称","version" to 1),"t")
  assertEquals("新名称",updated.text("displayName"))
  assertFailsWith<BusinessException>{s.updateAdministrator(root,created.text("id"),json("displayName" to "stale","version" to 1),"t")}
  s.setAdministratorStatus(root,created.text("id"),json("enabled" to false,"version" to 2),"t")
  assertFailsWith<BusinessException>{s.me(reader)}
  assertTrue(s.listAudit(root,1,20,emptyMap()).number("total")>0)
 }
 private fun fixture(block:suspend(AdminService,AdminCrypto,AtomicLong)->Unit)=runBlocking {
  val name="admin_"+UUID.randomUUID().toString().replace("-","")
  DriverManager.getConnection(postgres.jdbcUrl,postgres.username,postgres.password).use { it.createStatement().use { s->s.execute("CREATE DATABASE $name") } }
  val db=DatabaseSettings("jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$name",postgres.username,postgres.password)
  DatabaseRuntime.migrate(db)
  DatabaseRuntime.open(db).use { runtime->val c=AdminCrypto(ByteArray(32){3});val clock=AtomicLong(1_800_000_000_000);block(AdminService(runtime.transactions,c,clock::get),c,clock) }
 }
}
