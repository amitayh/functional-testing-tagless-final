package example

import cats.data.StateT
import cats.implicits._
import example.Matchers._
import example.UserInformationSpecV1._
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

class UserInformationSpecV1 extends Specification {

  trait Context extends Scope {
    val userId = UserId("user-1234")
    val result = FetchUserInformation[Test](userId)
  }

  "fetch user name and orders by ID" in new Context {
    val env = TestEnv.Empty
      .withProfile(UserProfile(userId, "John Doe"))
      .withOrder(Order(userId, OrderId("order-1")))
      .withOrder(Order(userId, OrderId("order-2")))

    result.runA(env) must beRight(
      haveUserName("John Doe") and
        haveOrders(OrderId("order-1"), OrderId("order-2")))
  }

  "log an error if user does not exists" in new Context {
    val env = TestEnv.Empty // No users here

    result.run(env) must beLeft(UserNotFound(userId))
  }

}

object UserInformationSpecV1 {

  type EitherThrowableOr[A] = Either[Throwable, A]
  type Test[A] = StateT[EitherThrowableOr, TestEnv, A]

  implicit val usersTest: Users[Test] = new Users[Test] {
    override def profileFor(userId: UserId): Test[UserProfile] =
      StateT.inspectF[EitherThrowableOr, TestEnv, UserProfile] { env =>
        env.profiles.get(userId) match {
          case Some(profile) => Right(profile)
          case None => Left(UserNotFound(userId))
        }
      }
  }

  implicit val ordersTest: Orders[Test] = new Orders[Test] {
    override def ordersFor(userId: UserId): Test[List[Order]] =
      StateT.inspect[EitherThrowableOr, TestEnv, List[Order]](_.userOrders(userId))
  }

  implicit val loggingTest: Logging[Test] = new Logging[Test] {
    override def error(e: Throwable): Test[Unit] =
      StateT.modify[EitherThrowableOr, TestEnv](_.logError(e))
  }

}
