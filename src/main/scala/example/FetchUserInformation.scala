package example

import cats.MonadError
import cats.implicits._

import scala.language.higherKinds

object FetchUserInformation {

  type MonadThrowable[F[_]] = MonadError[F, Throwable]

  def apply[F[_]: MonadThrowable: Users: Orders: Logging]
    (userId: UserId): F[UserInformation] = {
    val result = for {
      profile <- Users[F].profileFor(userId)
      orders <- Orders[F].ordersFor(userId)
    } yield UserInformation.from(profile, orders)

    result.onError {
      case e => Logging[F].error(e)
    }
  }

}
