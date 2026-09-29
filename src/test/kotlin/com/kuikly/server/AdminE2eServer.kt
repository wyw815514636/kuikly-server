package com.kuikly.server
import com.kuikly.server.config.AdminSettings
import com.kuikly.server.config.ServerConfig
import com.kuikly.server.infrastructure.database.DatabaseRuntime
import com.kuikly.server.modules.admin.application.AdminService
import com.kuikly.server.modules.admin.infrastructure.AdminCrypto
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.runBlocking

/** Test source only; never packaged in the production distribution. */
object AdminE2eServer {
 @JvmStatic fun main(args:Array<String>) {
  val config=ApplicationConfig("application.yaml")
  val server=ServerConfig.load(config)
  require(server.environment=="test"&&server.database.jdbcUrl.matches(Regex("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/kuikly_admin_e2e"))) {"E2E accepts only its disposable local database"}
  val admin=requireNotNull(AdminSettings.load(config))
  DatabaseRuntime.migrate(server.database)
  DatabaseRuntime.open(server.database).use {db->runBlocking {
   AdminService(db.transactions,AdminCrypto(admin.secretKey)).bootstrap("root",requireNotNull(System.getenv("ADMIN_E2E_PASSWORD")))
  }}
  EngineMain.main(args)
 }
}
