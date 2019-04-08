package example

import cats.data.{EitherT, State}
import example.Matchers._
import example.UserInformationSpecV2._
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

class UserInformationSpecV2 extends Specification {

  trait Context extends Scope {
    val userId = UserId("user-1234")
    val result = FetchUserInformation[Test](userId)
  }

  "fetch user name and orders by ID" in new Context {
    val env = TestEnv.Empty
      .withProfile(UserProfile(userId, "John Doe"))
      .withOrder(Order(userId, OrderId("order-1")))
      .withOrder(Order(userId, OrderId("order-2")))

    result.value.runA(env).value must beRight(
      haveUserName("John Doe") and
        haveOrders(OrderId("order-1"), OrderId("order-2")))
  }

  "log an error if user does not exists" in new Context {
    val env = TestEnv.Empty // No users here

    result.value.runS(env).value must
      containLoggedError(UserNotFound(userId))
  }

}

object UserInformationSpecV2 {

  type TestEffect[A] = State[TestEnv, A]
  type Test[A] = EitherT[TestEffect, Throwable, A]

  implicit val usersTest: Users[Test] = new Users[Test] {
    override def profileFor(userId: UserId): Test[UserProfile] =
      EitherT[TestEffect, Throwable, UserProfile] {
        State.inspect { env =>
          env.profiles.get(userId) match {
            case Some(profile) => Right(profile)
            case None => Left(UserNotFound(userId))
          }
        }
      }
  }

  implicit val ordersTest: Orders[Test] = new Orders[Test] {
    override def ordersFor(userId: UserId): Test[List[Order]] =
      EitherT.liftF[TestEffect, Throwable, List[Order]] {
        State.inspect(_.userOrders(userId))
      }
  }

  implicit val loggingTest: Logging[Test] = new Logging[Test] {
    override def error(e: Throwable): Test[Unit] =
      EitherT[TestEffect, Throwable, Unit] {
        State(env => (env.logError(e), Right(())))
      }
  }

}
