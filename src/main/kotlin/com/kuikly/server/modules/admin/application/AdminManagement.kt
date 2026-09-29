package com.kuikly.server.modules.admin.application
import com.kuikly.server.modules.admin.domain.*
import com.kuikly.server.modules.admin.infrastructure.AdminSql
import kotlinx.serialization.json.*
import java.util.UUID

internal fun AdminService.checkVersion(current:Long,input:JsonObject){if(input.number("version")!=current)conflict()}
internal fun AdminService.checkRoles(actor:AdminActor,roles:List<String>) {
 if(roles.size>50)bad()
 roles.forEach { id->validId(id);val role=repository.role(id)
  if(role.flag("system")&&!actor.superAdmin)denied()
  if(!actor.superAdmin&&role.strings("permissions").any{it !in actor.permissions})denied()
 }
}
internal fun AdminService.target(actor:AdminActor,id:String):AdminAccount {
 validId(id);val a=repository.account(id)?:missing()
 if(!actor.superAdmin&&SUPER_ROLE in repository.roleIds(a.id))denied()
 return a
}
internal fun AdminService.protectLast(id:String) {
 if(SUPER_ROLE in repository.roleIds(id)&&repository.count("SELECT count(*) FROM app.admin_accounts a JOIN app.admin_account_roles r ON r.account_id=a.id WHERE a.enabled=true AND a.password_hash<>'' AND a.must_change=false AND a.mfa_secret IS NOT NULL AND a.id<>? AND r.role_id=?",id,SUPER_ROLE)<1)conflict("必须保留至少一个启用的超级管理员")
}
internal fun pagination(page:Int,size:Int){if(page<1||page>100000||size !in 1..100)bad()}
internal suspend fun AdminService.listAdministrators(token:String,page:Int,size:Int,search:String,enabled:Boolean?):JsonObject=asActor(token,"admin:read") {
 pagination(page,size);if(search.length>80)bad()
 val where="WHERE (username ILIKE ? OR display_name ILIKE ?)"+(if(enabled==null)"" else " AND enabled=?")
 val args=listOf<Any?>("%$search%","%$search%")+(if(enabled==null)emptyList() else listOf(enabled))
 val ids=AdminSql.query("SELECT id FROM app.admin_accounts $where ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",*(args+listOf(size,(page-1)*size)).toTypedArray()){it.getString(1)}
 json("items" to ids.map{repository.summary(repository.account(it)!!)},"total" to repository.count("SELECT count(*) FROM app.admin_accounts $where",*args.toTypedArray()),"page" to page,"pageSize" to size)
}
internal suspend fun AdminService.getAdministrator(token:String,id:String):JsonObject=asActor(token,"admin:read"){validId(id);repository.summary(repository.account(id)?:missing())}
internal suspend fun AdminService.createAdministrator(token:String,request:JsonObject,key:String,trace:String):JsonObject {
 request.fields("username","displayName","password","roleIds")
 val username=request.text("username");val name=validName(request.text("displayName"));val password=request.text("password");val roles=request.strings("roleIds")
 if(!username.matches(Regex("[a-z0-9][a-z0-9_.-]{2,63}"))||password.length !in 12..128)bad()
 asActor(token,"admin:create"){checkRoles(it,roles);if(roles.isNotEmpty()&&"admin:assign-role" !in it.permissions)denied()}
 val hash=passwordWork { crypto.hash(password) }
 return asActor(token,"admin:create") { actor->
  checkRoles(actor,roles);if(roles.isNotEmpty()&&"admin:assign-role" !in actor.permissions)denied()
  idempotent(actor.account.id,"admin-create",key,request.toString()) {
   if(repository.byUsername(username)!=null)conflict("账号已存在")
   val id=UUID.randomUUID().toString()
   AdminSql.update("INSERT INTO app.admin_accounts(id,username,display_name,password_hash,created_at) VALUES (?,?,?,?,?)",id,username,name,hash,now())
   roles.forEach {AdminSql.update("INSERT INTO app.admin_account_roles(account_id,role_id) VALUES (?,?)",id,it)}
   repository.audit(actor.account.id,"admin.create",id,"success",trace,now());repository.summary(repository.account(id)!!)
  }.jsonObject
 }
}
internal suspend fun AdminService.updateAdministrator(token:String,id:String,request:JsonObject,trace:String):JsonObject=asActor(token,"admin:update") { actor->
 request.fields("displayName","version");val a=target(actor,id);checkVersion(a.version,request)
 AdminSql.update("UPDATE app.admin_accounts SET display_name=?,version=version+1 WHERE id=?",validName(request.text("displayName")),id)
 repository.audit(actor.account.id,"admin.update",id,"success",trace,now());repository.summary(repository.account(id)!!)
}
internal suspend fun AdminService.setAdministratorStatus(token:String,id:String,request:JsonObject,trace:String):JsonObject=asActor(token,"admin:status") {actor->
 request.fields("enabled","version");val a=target(actor,id);checkVersion(a.version,request);val enabled=request.flag("enabled")
 if(!enabled){if(id==actor.account.id)conflict("不能停用当前账号");protectLast(id);repository.revoke(id)}
 AdminSql.update("UPDATE app.admin_accounts SET enabled=?,version=version+1 WHERE id=?",enabled,id)
 repository.audit(actor.account.id,"admin.status",id,"success",trace,now());repository.summary(repository.account(id)!!)
}
internal suspend fun AdminService.assignRoles(token:String,id:String,request:JsonObject,trace:String):JsonObject=asActor(token,"admin:assign-role") {actor->
 request.fields("roleIds","version");val a=target(actor,id);checkVersion(a.version,request)
 val roles=request.strings("roleIds");checkRoles(actor,roles);checkRoles(actor,repository.roleIds(id))
 if(SUPER_ROLE !in roles)protectLast(id)
 AdminSql.update("DELETE FROM app.admin_account_roles WHERE account_id=?",id)
 roles.forEach{AdminSql.update("INSERT INTO app.admin_account_roles(account_id,role_id) VALUES (?,?)",id,it)}
 AdminSql.update("UPDATE app.admin_accounts SET version=version+1 WHERE id=?",id)
 repository.audit(actor.account.id,"admin.roles",id,"success",trace,now());repository.summary(repository.account(id)!!)
}
internal suspend fun AdminService.resetPassword(token:String,id:String,key:String,trace:String):JsonObject=asActor(token,"admin:reset-password") { actor->
 recent(actor);target(actor,id);if(id==actor.account.id)conflict("请使用个人安全中心修改自己的密码")
 idempotent(actor.account.id,"reset-password",key,id) {
  val credential=crypto.randomToken();val expires=now()+900000
  repository.revoke(id)
  AdminSql.update("UPDATE app.admin_accounts SET reset_digest=?,reset_expires=?,must_change=true,password_hash='',version=version+1 WHERE id=?",crypto.digest(credential),expires,id)
  repository.audit(actor.account.id,"admin.reset-password",id,"success",trace,now())
  json("credential" to credential,"expiresAt" to iso(expires))
 }.jsonObject
}
internal suspend fun AdminService.revokeAdministratorSessions(token:String,id:String,trace:String){asActor(token,"admin:revoke-session"){actor->recent(actor);target(actor,id);repository.revoke(id);repository.audit(actor.account.id,"admin.revoke-sessions",id,"success",trace,now())}}
internal suspend fun AdminService.listRoles(token:String,page:Int,size:Int):JsonObject=asActor(token,"role:read"){pagination(page,size);json("items" to AdminSql.query("SELECT id FROM app.admin_roles ORDER BY system DESC,name,id LIMIT ? OFFSET ?",size,(page-1)*size){repository.role(it.getString(1))},"total" to repository.count("SELECT count(*) FROM app.admin_roles"),"page" to page,"pageSize" to size)}
internal fun AdminService.validatePermissions(actor:AdminActor,permissions:List<String>){if(permissions.any{it !in PERMISSIONS})bad();if(!actor.superAdmin&&permissions.any{it !in actor.permissions})denied()}
internal suspend fun AdminService.createRole(token:String,request:JsonObject,trace:String):JsonObject=asActor(token,"role:create"){actor->
 request.fields("name","permissions");val name=validName(request.text("name"));val permissions=request.strings("permissions");validatePermissions(actor,permissions)
 if(repository.count("SELECT count(*) FROM app.admin_roles WHERE name=?",name)>0)conflict("角色名称已存在")
 val id=UUID.randomUUID().toString();AdminSql.update("INSERT INTO app.admin_roles(id,name) VALUES (?,?)",id,name)
 permissions.forEach{AdminSql.update("INSERT INTO app.admin_role_permissions(role_id,permission) VALUES (?,?)",id,it)}
 repository.audit(actor.account.id,"role.create",id,"success",trace,now());repository.role(id)
}
internal suspend fun AdminService.updateRole(token:String,id:String,request:JsonObject,trace:String):JsonObject=asActor(token,"role:update"){actor->
 request.fields("name","permissions","version");validId(id);val before=repository.role(id);if(before.flag("system"))denied();checkVersion(before.number("version"),request)
 val permissions=request.strings("permissions");validatePermissions(actor,permissions);validatePermissions(actor,before.strings("permissions"));val name=validName(request.text("name"))
 if(repository.count("SELECT count(*) FROM app.admin_roles WHERE name=? AND id<>?",name,id)>0)conflict("角色名称已存在")
 AdminSql.update("UPDATE app.admin_roles SET name=?,version=version+1 WHERE id=?",name,id);AdminSql.update("DELETE FROM app.admin_role_permissions WHERE role_id=?",id)
 permissions.forEach{AdminSql.update("INSERT INTO app.admin_role_permissions(role_id,permission) VALUES (?,?)",id,it)}
 repository.audit(actor.account.id,"role.update",id,"success",trace,now());repository.role(id)
}
internal suspend fun AdminService.deleteRole(token:String,id:String,version:Long,trace:String){asActor(token,"role:delete"){actor->
 validId(id);val role=repository.role(id);if(role.flag("system"))denied();if(role.number("version")!=version)conflict();validatePermissions(actor,role.strings("permissions"))
 if(role.number("memberCount")>0)conflict("请先移除此角色关联的账号")
 AdminSql.update("DELETE FROM app.admin_role_permissions WHERE role_id=?",id);AdminSql.update("DELETE FROM app.admin_roles WHERE id=?",id);repository.audit(actor.account.id,"role.delete",id,"success",trace,now())
}}
