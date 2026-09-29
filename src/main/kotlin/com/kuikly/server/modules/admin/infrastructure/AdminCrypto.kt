package com.kuikly.server.modules.admin.infrastructure

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import com.bastiaanjansen.otp.TOTPGenerator
import com.bastiaanjansen.otp.SecretGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AdminCrypto(private val key:ByteArray) {
 init { require(key.size==32) }
 private val random=SecureRandom()
 private val encoder=Base64.getUrlEncoder().withoutPadding()
 private val decoder=Base64.getUrlDecoder()
 fun randomToken():String=encoder.encodeToString(ByteArray(32).also(random::nextBytes))
 fun digest(value:String):String=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
 private fun derive(password:String,salt:ByteArray):ByteArray {
  val generator=Argon2BytesGenerator()
  generator.init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(19456).withIterations(2).withParallelism(1).withSalt(salt).build())
  val chars=password.toCharArray()
  return try { ByteArray(32).also { generator.generateBytes(chars,it) } } finally { chars.fill('\u0000') }
 }
 fun hash(password:String):String {
  require(password.length in 12..128)
  val salt=ByteArray(16).also(random::nextBytes)
  return "argon2id-v19-m19456-t2-p1:${encoder.encodeToString(salt)}:${encoder.encodeToString(derive(password,salt))}"
 }
 fun verify(password:String,encoded:String?):Boolean {
  val p=encoded?.split(':')
  val valid=p?.size==3 && p[0]=="argon2id-v19-m19456-t2-p1"
  val salt=if(valid)decoder.decode(p[1]) else ByteArray(16)
  val expected=if(valid)decoder.decode(p[2]) else ByteArray(32)
  val actual=derive(password.take(128),salt)
  return MessageDigest.isEqual(actual,expected) && valid && password.length<=128
 }
 fun encrypt(value:String,aad:String):String {
  val iv=ByteArray(12).also(random::nextBytes)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));cipher.updateAAD(aad.toByteArray())
  return "v1."+encoder.encodeToString(iv+cipher.doFinal(value.toByteArray()))
 }
 fun decrypt(value:String,aad:String):String {
  require(value.startsWith("v1."));val bytes=decoder.decode(value.removePrefix("v1."))
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)));cipher.updateAAD(aad.toByteArray())
  return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)))
 }
 fun secret():String=String(SecretGenerator.generate())
 fun totp(secret:String,now:Long):String=TOTPGenerator.Builder(secret).build().at(now/1000)
 fun matchingStep(secret:String,code:String,now:Long,lastStep:Long):Long? {
  if(!code.matches(Regex("[0-9]{6}")))return null
  return ((now/30000-1)..(now/30000+1)).firstOrNull { it>lastStep && MessageDigest.isEqual(totp(secret,it*30000).toByteArray(),code.toByteArray()) }
 }
}
