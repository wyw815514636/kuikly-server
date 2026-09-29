package com.kuikly.server.modules.admin.domain

import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import kotlinx.serialization.json.*
import java.time.Instant

internal const val SUPER_ROLE="00000000-0000-0000-0000-000000000001"
internal val PERMISSIONS=setOf("admin:read","admin:create","admin:update","admin:status","admin:reset-password","admin:revoke-session","admin:assign-role","role:read","role:create","role:update","role:delete","audit:read")
internal fun fail(kind:FailureKind,code:String,message:String):Nothing=throw BusinessException(kind,code,message)
internal fun bad(message:String="请求参数不合法"):Nothing=fail(FailureKind.BAD_REQUEST,"INVALID_REQUEST",message)
internal fun denied():Nothing=fail(FailureKind.FORBIDDEN,"FORBIDDEN","无权执行此操作")
internal fun unauthorized():Nothing=fail(FailureKind.UNAUTHENTICATED,"UNAUTHENTICATED","登录已失效，请重新登录")
internal fun conflict(message:String="数据已变化，请刷新后重试"):Nothing=fail(FailureKind.CONFLICT,"CONFLICT",message)
internal fun missing():Nothing=fail(FailureKind.NOT_FOUND,"NOT_FOUND","记录不存在")
internal fun json(vararg pairs:Pair<String,Any?>):JsonObject=buildJsonObject { pairs.forEach { (k,v)->put(k,element(v)) } }
internal fun element(v:Any?):JsonElement=when(v){null->JsonNull;is JsonElement->v;is String->JsonPrimitive(v);is Boolean->JsonPrimitive(v);is Number->JsonPrimitive(v);is Iterable<*>->JsonArray(v.map(::element));else->error("Unsupported JSON type")}
internal fun iso(millis:Long)=Instant.ofEpochMilli(millis).toString()
internal fun JsonObject.text(key:String):String=(get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?:bad()
internal fun JsonObject.number(key:String):Long=(get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it>=0 }?:bad()
internal fun JsonObject.flag(key:String):Boolean=(get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?:bad()
internal fun JsonObject.strings(key:String):List<String> =(get(key) as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p->p.isString }?.content?:bad() }?.distinct()?:bad()
internal fun JsonObject.fields(vararg allowed:String) { if(keys.any { it !in allowed })bad() }
internal fun validId(id:String):String=id.takeIf { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }?:bad()
internal fun validName(name:String):String=name.trim().takeIf { it.isNotEmpty()&&it.length<=80 }?:bad("名称长度应为 1–80 个字符")
internal data class AdminAccount(val id:String,val username:String,val displayName:String,val hash:String,val enabled:Boolean,val version:Long,val mustChange:Boolean,val secret:String?,val step:Long,val resetDigest:String?,val resetExpires:Long?)
internal data class AdminSession(val id:String,val accountId:String,val csrf:String,val created:Long,val active:Long,val expires:Long,val recent:Long,val revoked:Boolean)
internal data class AdminActor(val account:AdminAccount,val session:AdminSession,val permissions:Set<String>,val superAdmin:Boolean)
internal data class AdminOutcome(val body:JsonObject,val cookie:String?=null,val challenge:String?=null) {
 override fun toString()="AdminOutcome(redacted)"
}
