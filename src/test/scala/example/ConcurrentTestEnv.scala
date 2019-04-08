package example

import cats.effect.IO
import cats.effect.concurrent.Ref

case class ConcurrentTestEnv(profiles: Map[UserId, UserProfile],
                             orders: Map[UserId, List[Order]],
                             loggedErrors: Ref[IO, List[Throwable]]) {

  def withProfile(profile: UserProfile): ConcurrentTestEnv =
    copy(profiles = profiles + (profile.userId -> profile))

  def withOrder(order: Order): ConcurrentTestEnv = {
    val updatedUserOrders = order :: userOrders(order.userId)
    copy(orders = orders + (order.userId -> updatedUserOrders))
  }

  def logError(e: Throwable): IO[Unit] =
    loggedErrors.update(e :: _)

  def userOrders(userId: UserId): List[Order] =
    orders.getOrElse(userId, Nil)

}

object ConcurrentTestEnv {
  final def empty: IO[ConcurrentTestEnv] =
    Ref.of[IO, List[Throwable]](Nil).map { loggedErrors =>
      ConcurrentTestEnv(Map.empty, Map.empty, loggedErrors)
    }
}
