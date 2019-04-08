package object example {

  case class UserId(id: String) extends AnyVal

  case class OrderId(id: String) extends AnyVal

  case class UserProfile(userId: UserId, userName: String)

  case class Order(userId: UserId, orderId: OrderId)

  case class UserInformation(userName: String, orders: List[Order])

  object UserInformation {
    def from(profile: UserProfile, orders: List[Order]): UserInformation =
      UserInformation(profile.userName, orders)
  }

}
