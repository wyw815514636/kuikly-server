package com.kuikly.server.config
import io.ktor.server.config.ApplicationConfig
import java.net.URI
import java.util.Base64
internal class AdminSettings(val origins:Set<String>,val secretKey:ByteArray,val secure:Boolean,val idleMillis:Long=1_800_000,val sessionMillis:Long=43_200_000) {
 override fun toString()="AdminSettings(secretKey=<redacted>,secure=$secure)"
 companion object {
  fun load(config:ApplicationConfig):AdminSettings? {
   val enabled=config.propertyOrNull("admin.enabled")?.getString()?:"false"
   require(enabled in setOf("true","false")){"admin.enabled must be true or false"};if(enabled=="false")return null
   val secure=config.propertyOrNull("app.environment")?.getString() in setOf("staging","prod")
   val origins=(config.propertyOrNull("admin.origins")?.getString()?:"http://localhost:5173,http://127.0.0.1:5173").split(',').map{it.trim()}.toSet()
   require(origins.isNotEmpty()&&origins.all { origin->runCatching {val u=URI(origin);u.host!=null&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null&&u.rawPath.orEmpty().isEmpty()&&u.scheme in (if(secure)setOf("https") else setOf("http","https"))}.getOrDefault(false) }){"Invalid admin.origins"}
   val key=runCatching{Base64.getDecoder().decode(config.property("admin.secretKey").getString())}.getOrNull()
   require(key?.size==32){"admin.secretKey must be an independent Base64 encoded 32-byte key"}
   for (name in listOf("auth.signingKey", "auth.retryKey")) {
    val clientKey=config.propertyOrNull(name)?.getString()?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    require(clientKey==null || !java.security.MessageDigest.isEqual(key,clientKey)){"Admin and client keys must be independent"}
   }
   fun seconds(name:String,default:Long,range:LongRange):Long {
    val raw=config.propertyOrNull(name)?.getString()
    val value=if(raw==null)default else requireNotNull(raw.toLongOrNull()){ "$name must be an integer" }
    require(value in range){"$name is outside the permitted range"};return value
   }
   val absolute=seconds("admin.sessionSeconds",43_200,600L..86_400L)
   val idle=seconds("admin.idleSeconds",1_800,60L..absolute)
   return AdminSettings(origins,key,secure,idle*1000,absolute*1000)
  }
 }
}
