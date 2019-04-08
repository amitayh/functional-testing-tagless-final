package example

import cats.effect.Concurrent
import cats.effect.implicits._
import cats.implicits._

import scala.language.higherKinds

object FetchUserInformationConcurrent {

  def apply[F[_]: Concurrent: Users: Orders: Logging]
    (userId: UserId): F[UserInformation] = {
    val result = for {
      profileFiber <- Users[F].profileFor(userId).start
      ordersFiber <- Orders[F].ordersFor(userId).start
      profile <- profileFiber.join
      orders <- ordersFiber.join
    } yield UserInformation.from(profile, orders)

    result.onError {
      case e => Logging[F].error(e)
    }
  }

}
