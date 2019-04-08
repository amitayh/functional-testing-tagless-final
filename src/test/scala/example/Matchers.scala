package example

import org.specs2.matcher.Matcher
import org.specs2.matcher.Matchers._

object Matchers {

  def haveUserName(userName: String): Matcher[UserInformation] =
    equalTo(userName) ^^ ((_: UserInformation).userName)

  def haveOrders(orderIds: OrderId*): Matcher[UserInformation] =
    containAllOf(orderIds) ^^ ((_: UserInformation).orders.map(_.orderId))

  def containLoggedError[T <: Throwable](error: T): Matcher[TestEnv] =
    contain[Throwable](equalTo(error)) ^^ ((_: TestEnv).loggedErrors)

}
