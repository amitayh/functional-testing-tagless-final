package example

import scala.language.higherKinds

trait Users[F[_]] {
  def profileFor(userId: UserId): F[UserProfile]
}

case class UserNotFound(userId: UserId)
  extends RuntimeException(s"User with ID $userId does not exist")

object Users {
  def apply[F[_]](implicit F: Users[F]): Users[F] = F
}

trait Orders[F[_]] {
  def ordersFor(userId: UserId): F[List[Order]]
}

object Orders {
  def apply[F[_]](implicit F: Orders[F]): Orders[F] = F
}

trait Logging[F[_]] {
  def error(e: Throwable): F[Unit]
}

object Logging {
  def apply[F[_]](implicit F: Logging[F]): Logging[F] = F
}
