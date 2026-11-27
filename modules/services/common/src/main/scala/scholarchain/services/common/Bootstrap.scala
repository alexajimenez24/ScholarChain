package scholarchain.services.common

import cats.effect.{ExitCode, IO, Resource}
import com.comcast.ip4s.{Port, ipv4}
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import scala.concurrent.duration.*
import scholarchain.core.{ConfigLoader, JwtConfig, ScholarChainConfig}

/** A wired deployable before it listens: its name, port and wrapped routes, plus the work that runs
  * beside the server (it never ends the process by completing).
  */
final case class ServiceApp(
    name: String,
    port: Int,
    routes: HttpRoutes[IO],
    background: IO[Unit] = IO.unit
)

/** What every deployable's `main` shares: configuration, token verification and the server. */
object Bootstrap:
  /** The core configuration, in `aws` mode only: a deployable has AWS adapters and no local ones,
    * and `aws` mode makes the loader refuse any empty bucket, table, endpoint or identity value.
    */
  def config(env: Map[String, String]): Either[String, ScholarChainConfig] =
    ConfigLoader
      .load(env)
      .flatMap(c =>
        Either.cond(
          c.application.mode == "aws",
          c,
          "SCHOLARCHAIN_MODE must be aws: a deployable has no local mode"
        )
      )

  /** The Cognito guard. `fetch` defaults to the HTTPS JWKS fetch; no call is made until the first
    * token arrives.
    */
  def guard(auth: JwtConfig, fetch: Option[IO[String]] = None): Either[String, Guard] =
    if !auth.jwksUrl.startsWith("https://") then Left("services.auth.jwksUrl must be https")
    else
      val source =
        fetch.getOrElse(Jwks.httpFetch(auth.jwksUrl, auth.requestTimeoutMs.millis))
      Right(
        new Guard(
          new JwtVerifier(AuthConfig(auth.issuer, auth.audience), new JwksKeyProvider(source))
        )
      )

  /** One startup or failure line on stderr. Callers pass names and reasons, never credentials. */
  def log(service: String, message: String): IO[Unit] =
    IO.blocking(Console.err.println(s"$service: $message"))

  def server(app: ServiceApp): Resource[IO, Server] =
    Resource
      .eval(
        IO.fromOption(Port.fromInt(app.port))(new IllegalArgumentException("invalid port"))
      )
      .flatMap(port =>
        EmberServerBuilder
          .default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(port)
          .withHttpApp(app.routes.orNotFound)
          .withShutdownTimeout(20.seconds) // inside the 30 s ECS stopTimeout
          .build
      )

  /** Serves until cancelled (SIGTERM). A wiring error exits with code 1 before anything listens. */
  def run(service: String, wired: IO[Either[String, ServiceApp]]): IO[ExitCode] =
    wired.flatMap {
      case Left(why)  => log(service, s"cannot start: $why").as(ExitCode.Error)
      case Right(app) =>
        server(app).use(_ =>
          log(service, s"listening on port ${app.port}") *>
            app.background.start.flatMap(fiber => IO.never[ExitCode].guarantee(fiber.cancel))
        )
    }
