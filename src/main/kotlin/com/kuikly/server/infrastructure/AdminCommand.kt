package com.kuikly.server.infrastructure
import com.kuikly.server.config.AdminSettings
import com.kuikly.server.config.ServerConfig
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import com.kuikly.server.modules.admin.application.AdminService
import com.kuikly.server.modules.admin.infrastructure.AdminCrypto
import com.kuikly.server.modules.admin.infrastructure.AdminSql
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.runBlocking

internal fun adminCommand(args:Array<String>)=runBlocking {
 require(args.size==2){"Usage: create-admin <username> | recover-admin-mfa <username> | prune-admin-audit <retention-days>"}
 val config=ApplicationConfig("application.yaml")
 val settings=requireNotNull(AdminSettings.load(config)){"Set ADMIN_ENABLED=true and configure ADMIN_SECRET_KEY"}
 val console=requireNotNull(System.console()){"Run the distribution in an interactive terminal"}
 DatabaseRuntime.open(ServerConfig.load(config).database).use { db->
  val service=AdminService(db.transactions,AdminCrypto(settings.secretKey))
  when(args[0]){
   "create-admin"->{val first=console.readPassword("Initial password: ");val second=console.readPassword("Confirm password: ")
    try {require(first.contentEquals(second)){"Passwords do not match"};service.bootstrap(args[1],String(first));console.printf("Administrator created. First login requires password change and MFA enrollment.%n")}finally{first.fill('\u0000');second.fill('\u0000')}
   }
   "recover-admin-mfa"->{require(console.readLine("Type the account name to confirm recovery: ")==args[1]){"Cancelled"}
    service.locked {val a=requireNotNull(service.repository.byUsername(args[1])){"Unknown administrator"};service.repository.revoke(a.id)
     AdminSql.update("UPDATE app.admin_accounts SET mfa_secret=NULL,last_totp_step=-1,must_change=true,version=version+1 WHERE id=?",a.id)
     AdminSql.update("DELETE FROM app.admin_mfa_recovery WHERE account_id=?",a.id)
     service.repository.audit(a.id,"mfa.emergency-recovery",a.id,"success","cli",service.now())}
    console.printf("Sessions revoked. Account must change password and re-enroll MFA.%n")
   }
   "prune-admin-audit"->{val days=args[1].toInt();require(days>=180){"Retention must be at least 180 days"};require(console.readLine("Type PRUNE to remove expired audit events: ")=="PRUNE"){"Cancelled"}
    val count=service.locked {AdminSql.update("DELETE FROM app.admin_audit_events WHERE occurred_at<?",service.now()-days.toLong()*86_400_000)};console.printf("Removed %d expired events.%n",count)
   }
  }
 }
}
