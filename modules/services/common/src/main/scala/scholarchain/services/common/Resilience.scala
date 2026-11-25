package scholarchain.services.common

import cats.effect.{IO, Ref}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Limits for calls to one downstream: a per-attempt timeout, a bounded number of attempts with a
  * linear backoff, and a circuit breaker that opens after `failureThreshold` consecutive failed
  * calls and stays open for `openFor`.
  */
final case class ResiliencePolicy(
    timeout: FiniteDuration = 2.seconds,
    maxAttempts: Int = 2,
    backoff: FiniteDuration = 100.millis,
    failureThreshold: Int = 5,
    openFor: FiniteDuration = 30.seconds
):
  require(maxAttempts >= 1 && maxAttempts <= 5, "maxAttempts must be 1..5")
  require(failureThreshold >= 1, "failureThreshold must be at least 1")

/** A guarded downstream (a store port). `call` runs a blocking port method with the policy:
  * timeout, bounded retries, breaker. Outcomes: the value; 504 when every attempt timed out; 502
  * when the port failed; 503 while the breaker is open. A `Left` from a port is a transient
  * failure; "not found" must be a `Right(None)`, so it never trips the breaker.
  */
final class Downstream(
    val name: String,
    policy: ResiliencePolicy = ResiliencePolicy(),
    nowMillis: IO[Long] = IO.monotonic.map(_.toMillis)
):
  private val state = Ref.unsafe[IO, (Int, Long)]((0, 0L)) // consecutive failures, open-until

  def breakerOpen: IO[Boolean] =
    for
      now <- nowMillis
      st <- state.get
      (fails, until) = st
    yield fails >= policy.failureThreshold && now < until

  private def attempt[A](op: => Either[String, A]): IO[Either[ErrorCode, A]] =
    IO.interruptible(
      try op.left.map(_ => ErrorCode.DependencyFailed)
      catch case NonFatal(_) => Left(ErrorCode.DependencyFailed)
    ).timeout(policy.timeout)
      .handleError(_ => Left(ErrorCode.Timeout))

  private def retrying[A](op: => Either[String, A], n: Int): IO[Either[ErrorCode, A]] =
    attempt(op).flatMap {
      case Left(_) if n < policy.maxAttempts =>
        IO.sleep(policy.backoff * n.toLong) *> retrying(op, n + 1)
      case other => IO.pure(other)
    }

  def call[A](op: => Either[String, A]): IO[A] =
    breakerOpen.flatMap {
      case true =>
        ApiException.raise(
          ApiError(
            ErrorCode.Unavailable,
            "a downstream dependency is unavailable",
            Map("dependency" -> name)
          )
        )
      case false =>
        retrying(op, 1).flatMap {
          case Right(a)   => state.set((0, 0L)).as(a)
          case Left(code) =>
            for
              now <- nowMillis
              _ <- state.update((f, u) =>
                val nf = f + 1
                (nf, if nf >= policy.failureThreshold then now + policy.openFor.toMillis else u)
              )
              r <- ApiException.raise[A](
                ApiError(
                  code,
                  if code == ErrorCode.Timeout then "a downstream dependency timed out"
                  else "a downstream dependency failed",
                  Map("dependency" -> name)
                )
              )
            yield r
        }
    }
