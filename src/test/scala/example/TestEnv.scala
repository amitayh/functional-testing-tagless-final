package example

case class TestEnv(profiles: Map[UserId, UserProfile],
                   orders: Map[UserId, List[Order]],
                   loggedErrors: List[Throwable]) {

  def withProfile(profile: UserProfile): TestEnv =
    copy(profiles = profiles + (profile.userId -> profile))

  def withOrder(order: Order): TestEnv = {
    val updatedUserOrders = order :: userOrders(order.userId)
    copy(orders = orders + (order.userId -> updatedUserOrders))
  }

  def logError(e: Throwable): TestEnv =
    copy(loggedErrors = e :: loggedErrors)

  def userOrders(userId: UserId): List[Order] =
    orders.getOrElse(userId, Nil)

}

object TestEnv {
  final val Empty = TestEnv(Map.empty, Map.empty, Nil)
}
