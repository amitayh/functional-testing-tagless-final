package example

import cats.data.ReaderT
import cats.effect.{ContextShift, IO}
import example.Matchers._
import example.UserInformationSpecV3._
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import scala.concurrent.ExecutionContext.global

class UserInformationSpecV3 extends Specification {

  trait Context extends Scope {
    val userId = UserId("user-1234")
    val userInformation = FetchUserInformationConcurrent[Test](userId)
  }

  "fetch user name and orders by ID" in new Context {
    val result = ConcurrentTestEnv.empty.flatMap { emptyEnv =>
      val env = emptyEnv
        .withProfile(UserProfile(userId, "John Doe"))
        .withOrder(Order(userId, OrderId("order-1")))
        .withOrder(Order(userId, OrderId("order-2")))

      userInformation.run(env)
    }

    result.unsafeRunSync() must
      haveUserName("John Doe") and
      haveOrders(OrderId("order-1"), OrderId("order-2"))
  }

  "log an error if user does not exists" in new Context {
    val result = for {
      env <- ConcurrentTestEnv.empty
      // We need to use `attempt` here, so we won't fail-fast
      // and "break out" of the for comprehension
      _ <- userInformation.run(env).attempt
      errors <- env.loggedErrors.get
    } yield errors

    result.unsafeRunSync() must contain(UserNotFound(userId))
  }

}

object UserInformationSpecV3 {

  type Test[A] = ReaderT[IO, ConcurrentTestEnv, A]

  implicit val contextShift: ContextShift[IO] =
    IO.contextShift(global)

  implicit val usersTest: Users[Test] = new Users[Test] {
    override def profileFor(userId: UserId): Test[UserProfile] =
      ReaderT { env =>
        env.profiles.get(userId) match {
          case Some(profile) => IO.pure(profile)
          case None => IO.raiseError(UserNotFound(userId))
        }
      }
  }

  implicit val ordersTest: Orders[Test] = new Orders[Test] {
    override def ordersFor(userId: UserId): Test[List[Order]] =
      ReaderT(env => IO.pure(env.userOrders(userId)))
  }

  implicit val loggingTest: Logging[Test] = new Logging[Test] {
    override def error(e: Throwable): Test[Unit] =
      ReaderT(_.logError(e))
  }

}
