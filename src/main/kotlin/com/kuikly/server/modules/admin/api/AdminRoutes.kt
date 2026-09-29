package com.kuikly.server.modules.admin.api
import com.kuikly.server.config.AdminSettings
import com.kuikly.server.application.BusinessException
import com.kuikly.server.application.FailureKind
import com.kuikly.server.modules.admin.application.*
import com.kuikly.server.modules.admin.domain.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

internal fun Application.configureAdminRoutes(service:AdminService,settings:AdminSettings) {
 val prefix=if(settings.secure)"__Host-" else ""
 val sessionName=prefix+"kuikly-admin";val challengeName=prefix+"kuikly-challenge";val csrfName=prefix+"kuikly-csrf"
 fun ApplicationCall.session()=request.cookies[sessionName]?:unauthorized()
 fun ApplicationCall.challenge()=request.cookies[challengeName]?:unauthorized()
 fun ApplicationCall.trace()=callId?:"unknown"
 fun ApplicationCall.id()=parameters["id"]?:bad()
 fun ApplicationCall.key()=request.headers["Idempotency-Key"]?:bad("缺少操作幂等键")
 fun ApplicationCall.device()=request.headers[HttpHeaders.UserAgent].orEmpty()
 fun ApplicationCall.cookie(name:String,value:String,age:Int){response.cookies.append(Cookie(name,value,encoding=CookieEncoding.RAW,maxAge=age,path="/",secure=settings.secure,httpOnly=true,extensions=mapOf("SameSite" to "Lax")))}
 fun ApplicationCall.clear(){cookie(sessionName,"",0);cookie(challengeName,"",0);cookie(csrfName,"",0)}
 fun ApplicationCall.outcome(result:AdminOutcome):JsonElement {
  result.cookie?.let{cookie(sessionName,it,(settings.sessionMillis/1000).toInt());cookie(challengeName,"",0)}
  result.challenge?.let{cookie(challengeName,it,300);cookie(sessionName,"",0)}
  return result.body
 }
 suspend fun ApplicationCall.body()=receive<JsonObject>()
 fun ApplicationCall.page()=request.queryParameters["page"]?.toIntOrNull()?:if(request.queryParameters.contains("page"))bad() else 1
 fun ApplicationCall.size()=request.queryParameters["pageSize"]?.toIntOrNull()?:if(request.queryParameters.contains("pageSize"))bad() else 20
 suspend fun ApplicationCall.csrfCheck(){
  if(request.headers[HttpHeaders.Origin] !in settings.origins)denied()
  val provided=request.headers["X-CSRF-Token"]?:denied()
  val token=request.cookies[sessionName]
  val expected=if(token!=null)service.csrf(token) else request.cookies[csrfName]?:denied()
  if(!MessageDigest.isEqual(provided.toByteArray(),expected.toByteArray()))denied()
 }
 fun Route.endpoint(method:String,path:String,block:suspend (ApplicationCall)->JsonElement?) {
  route(path,HttpMethod.parse(method)){handle {
   call.response.header(HttpHeaders.CacheControl,"no-store")
   try {
    if(method!="GET")call.csrfCheck()
    val result=block(call)
    if(result==null)call.respond(HttpStatusCode.NoContent) else call.respond(result)
   }catch(failure:BusinessException){
    if(failure.kind==FailureKind.TOO_MANY_REQUESTS)call.response.header(HttpHeaders.RetryAfter,"60")
    if(failure.kind in setOf(FailureKind.FORBIDDEN,FailureKind.UNAUTHENTICATED))service.deniedAudit(call.trace(),"http.$method.${path.take(60)}")
    throw failure
   }
  }}
 }
 routing {route("/api/admin/v1") {
  install(RequestBodyLimit){bodyLimit{16_384}}
  endpoint("GET","/auth/csrf"){c->
   val current=c.request.cookies[sessionName]
   val token=if(current!=null)try {service.csrf(current)}catch(e:BusinessException){if(e.kind!=FailureKind.UNAUTHENTICATED)throw e;c.cookie(sessionName,"",0);service.crypto.randomToken()} else c.request.cookies[csrfName]?.takeIf{it.matches(Regex("[A-Za-z0-9_-]{43}"))}?:service.crypto.randomToken()
   c.cookie(csrfName,token,(settings.sessionMillis/1000).toInt());json("csrfToken" to token)
  }
  endpoint("POST","/auth/login"){c->val b=c.body();b.fields("username","password");c.outcome(service.login(b.text("username"),b.text("password"),c.trace(),c.request.local.remoteHost,c.device()))}
  endpoint("POST","/auth/challenge/password"){c->val b=c.body();b.fields("password");c.outcome(service.initialPassword(c.challenge(),b.text("password"),c.trace(),c.device()))}
  endpoint("POST","/auth/challenge/mfa"){c->val b=c.body();b.fields("code");c.outcome(service.verifyMfa(c.challenge(),b.text("code"),false,c.trace(),c.device()))}
  endpoint("POST","/auth/challenge/recovery"){c->val b=c.body();b.fields("recoveryCode");c.outcome(service.verifyMfa(c.challenge(),b.text("recoveryCode"),true,c.trace(),c.device()))}
  endpoint("POST","/auth/logout"){c->service.logout(c.session());c.clear();null}
  endpoint("GET","/me"){c->service.me(c.session())}
  endpoint("PATCH","/me"){c->service.updateProfile(c.session(),c.body(),c.trace())}
  endpoint("POST","/me/password"){c->val b=c.body();b.fields("currentPassword","newPassword");service.changePassword(c.session(),b.text("currentPassword"),b.text("newPassword"),c.trace());c.clear();null}
  endpoint("POST","/me/reauthenticate"){c->val b=c.body();b.fields("password","code");service.reauthenticate(c.session(),b.text("password"),if("code" in b)b.text("code") else null);null}
  endpoint("GET","/me/sessions"){c->service.sessions(c.session())}
  endpoint("DELETE","/me/sessions/{id}"){c->service.revokeSession(c.session(),c.id(),false,c.trace());null}
  endpoint("POST","/me/sessions/revoke-others"){c->service.revokeSession(c.session(),null,true,c.trace());null}
  endpoint("POST","/me/mfa/enrollment"){c->val raw=c.request.cookies[challengeName]?:service.enrollmentChallenge(c.session());val result=service.enroll(null,raw);c.cookie(challengeName,raw,300);result}
  endpoint("POST","/me/mfa/confirm"){c->val b=c.body();b.fields("code");c.outcome(service.confirmMfa(c.request.cookies[sessionName],c.request.cookies[challengeName],b.text("code"),c.key(),c.trace(),c.device()))}
  endpoint("DELETE","/me/mfa"){c->service.disableMfa(c.session(),c.trace());c.clear();null}
  endpoint("GET","/administrators"){c->val enabled=c.request.queryParameters["enabled"]?.let{it.toBooleanStrictOrNull()?:bad()};service.listAdministrators(c.session(),c.page(),c.size(),c.request.queryParameters["search"].orEmpty(),enabled)}
  endpoint("POST","/administrators"){c->service.createAdministrator(c.session(),c.body(),c.key(),c.trace())}
  endpoint("GET","/administrators/{id}"){c->service.getAdministrator(c.session(),c.id())}
  endpoint("PATCH","/administrators/{id}"){c->service.updateAdministrator(c.session(),c.id(),c.body(),c.trace())}
  endpoint("POST","/administrators/{id}/status"){c->service.setAdministratorStatus(c.session(),c.id(),c.body(),c.trace())}
  endpoint("POST","/administrators/{id}/roles"){c->service.assignRoles(c.session(),c.id(),c.body(),c.trace())}
  endpoint("POST","/administrators/{id}/reset-password"){c->service.resetPassword(c.session(),c.id(),c.key(),c.trace())}
  endpoint("POST","/administrators/{id}/revoke-sessions"){c->service.revokeAdministratorSessions(c.session(),c.id(),c.trace());null}
  endpoint("GET","/roles"){c->service.listRoles(c.session(),c.page(),c.size())}
  endpoint("POST","/roles"){c->service.createRole(c.session(),c.body(),c.trace())}
  endpoint("PATCH","/roles/{id}"){c->service.updateRole(c.session(),c.id(),c.body(),c.trace())}
  endpoint("DELETE","/roles/{id}"){c->val b=c.body();b.fields("version");service.deleteRole(c.session(),c.id(),b.number("version"),c.trace());null}
  endpoint("GET","/permissions"){c->service.asActor(c.session(),"role:read"){JsonArray(PERMISSIONS.sorted().map(::JsonPrimitive))}}
  endpoint("GET","/audit-events"){c->service.listAudit(c.session(),c.page(),c.size(),c.request.queryParameters.entries().associate{it.key to it.value.first()})}
  endpoint("GET","/audit-events/{id}"){c->service.getAudit(c.session(),c.id())}
 }}
}
