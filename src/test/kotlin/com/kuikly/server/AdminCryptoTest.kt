package com.kuikly.server
import com.kuikly.server.modules.admin.infrastructure.AdminCrypto
import org.junit.jupiter.api.Test
import kotlin.test.*
class AdminCryptoTest {
 @Test fun `password hashes are salted and reject wrong credentials`() {
  val c=AdminCrypto(ByteArray(32){7})
  val a=c.hash("a twelve character password")
  assertNotEquals(a,c.hash("a twelve character password"))
  assertTrue(c.verify("a twelve character password",a))
  assertFalse(c.verify("wrong",a))
  assertFailsWith<IllegalArgumentException>{c.hash("short")}
 }
 @Test fun `ciphertext binds account and TOTP matches RFC vector`() {
  val c=AdminCrypto(ByteArray(32){7})
  val encrypted=c.encrypt("secret-value","alice")
  assertFalse(encrypted.contains("secret-value"))
  assertEquals("secret-value",c.decrypt(encrypted,"alice"))
  assertFails{c.decrypt(encrypted,"bob")}
  assertEquals("287082",c.totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",59_000))
 }
}
