# Functional Testing with Tagless-Final ([read on Medium](https://medium.com/wix-engineering/functional-testing-with-tagless-final-50eeacf5df6))

Although it was [recently pronounced dead](https://skillsmatter.com/skillscasts/13247-scala-matters) by
[John De Goes](https://twitter.com/jdegoes), [tagless-final](https://typelevel.org/blog/2017/12/27/optimizing-final-tagless.html)
is still a popular technique for writing purely functional programs in Scala. It allows us to explicitly describe which effects are needed by each part of the program, giving us the ability to reason about what a function can, and more importantly, can't do. Another benefit of using tagless-final is the fact we abstract over our effect type, which means we can relatively easily swap it later for a different implementation that might have different characteristics, for example, better performance or an async execution model (given we have all the type-class instances needed by our program).

This allows us to have our program run using Scala's built-in `Future` and over time migrate to [Cats-Effect IO](https://typelevel.org/cats-effect/), or the shiny new [Scalaz ZIO](https://scalaz.github.io/scalaz-zio/) - all without changing our code.

However, in practice we don't often change the effect we use in our production environment. What I have found useful about using tagless-final in a real-world project, is the ability to test my application in a purely functional setting. The advantages of this approach are very compelling:

 * I can easily write tests that exercise the entire system. I hardly write unit tests anymore, which means I don't need to mock out anything - and I get very high level of confidence in my code and the integration between the different components.
 * Since my tests are more high-level, I get more refactoring opportunities. Having many, fragmented unit tests across the codebase usually couple our tests with implementation details making it harder to refactor.
 * These tests run entirely in memory, without any I/O or shared state. This means they run fast .VERY FAST. And because they're totally independent, running tests in parallel is trivial.
 * Having an isolated environment for tests to run, in which you can control anything from time to randomness, means that the tests are very predictable. I can't remember having a single flaky test since starting to use this pattern.

I would like to share some of the lessons learned on how to do testing using tagless-final, which hopefully you'll find useful as well.

Before we begin, we need some realistic, yet simplified example to work with. So let's imagine we're asked to create an endpoint in our web application, which given a user ID will fetch user profile information and a list of user's orders. If some error occurs, we want to log it before failing. We will need three algebras to work with:

```scala
trait Users[F[_]] {
  def profileFor(userId: UserId): F[UserProfile]
}

trait Orders[F[_]] {
  def ordersFor(userId: UserId): F[List[Order]]
}

trait Logging[F[_]] {
  def error(e: Throwable): F[Unit]
}
```

In production, `Users` and `Orders` could either use an internal database to fetch the information, or call out to a different micro-service. This makes no difference for our piece of business logic, and these implementation details are irrelevant for testing our code.

A straight-forward implementation might look something like this (using the Cats library for all the FP goodness):

```scala
import cats.implicits._

type MonadThrowable[F[_]] = MonadError[F, Throwable]

def fetchUserInformation
  [F[_]: MonadThrowable: Users: Orders: Logging]
  (userId: UserId): F[UserInformation] = {
  val result = for {
    profile <- Users[F].profileFor(userId)
    orders <- Orders[F].ordersFor(userId)
  } yield UserInformation.from(profile, orders)
  
  result.onError {
    case e => Logging[F].error(e)
  }
}
```

## First attempt

So how would we go about testing this function? We must first decide on what will be our effect type F[_]. To do that, we need to understand the capabilities we need from our effect. We see that we need to read some "external" data about the user profiles and orders. The obvious FP solution for this sort of problem is using the Reader monad. This might look something like this:

```scala
import cats.data._

case class TestEnv(
  profiles: Map[UserId, UserProfile],
  orders: Map[UserId, List[Order]])

type Test[A] = Reader[TestEnv, A]
```

By doing so, we are now able to provide type-class instances for `Users` and `Orders`. However, we don't only need to read from our environment, but also to write to it (with the `Logging` algebra).

## Adding some state

FP offers an elegant solution for this problem as well, with the State monad. The State monad allows us to read information from some environment `S`, and also modify it before producing a value of type `A`. So, in essence, it's a function `S => (S, A)`. We'll modify our `TestEnv` and `Test` effect to accommodate for these changes. We'll also add a few helpers to `TestEnv` which will make our tests cleaner later on:

```scala
case class TestEnv(
  profiles: Map[UserId, UserProfile],
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

type Test[A] = State[TestEnv, A]
```

We are now able to provide type-class instances, or interpreters, for `Users`, `Orders` and `Logging` like so:

```scala
implicit val usersTest: Users[Test] = new Users[Test] {
  override def profileFor(userId: UserId): Test[UserProfile] =
    State.inspect(_.profiles(userId))
}

implicit val ordersTest: Orders[Test] = new Orders[Test] {
  override def ordersFor(userId: UserId): Test[List[Order]] =
    State.inspect(_.userOrders(userId))
}

implicit val loggingTest: Logging[Test] = new Logging[Test] {
  override def error(e: Throwable): Test[Unit] =
    State.modify(_.logError(e))
}
```

## Handling errors

Cats library provides us with a `Monad` instance for `State` out of the box, however we are still missing the instance for `MonadError[Test, Throwable]`. Our chosen effect type is not suitable for dealing with errors, so we need to tweak it a bit. `State[S, A]` is actually a type alias for `StateT[Eval, S, A]`. The most basic wrapper for dealing with errors is `Either`. So we can redefine our test effect like so:

```scala
type EitherThrowableOr[A] = Either[Throwable, A]
type Test[A] = StateT[EitherThrowableOr, TestEnv, A]

// Or with kind-projector compiler plugin:
// type Test[A] = StateT[Either[Throwable, ?], TestEnv, A]
```

This simple change gives us 2 important things:

 1. Cats can now derive a type-class instance for `MonadError[Test, Throwable]`.
 2. We are able refine our implementation of `Users[Test]` to handle the case where a user for requested ID is missing:

```scala
case class UserNotFound(userId: UserId)
  extends RuntimeException(s"User with ID $userId does not exist")

implicit val usersTest: Users[Test] = new Users[Test] {
  override def profileFor(userId: UserId): Test[UserProfile] =
    StateT.inspectF { env =>
      env.profiles.get(userId) match {
        case Some(profile) => Right(profile)
        case None => Left(UserNotFound(userId))
      }
    }
}
```

Finally, we can write a test to check the `fetchUserInformation` function. These code examples will use the [Specs²](https://etorreborre.github.io/specs2/) testing library, but can be written with other tools easily as well.

```scala
import org.specs2.mutable.Specification

class UserInformationSpec extends Specification {

  "fetch user name and orders by ID" in {
    val userId = UserId("user-1234")
    val env = TestEnv.Empty
      .withProfile(UserProfile(userId, "John Doe"))
      .withOrder(Order(userId, OrderId("order-1")))
      .withOrder(Order(userId, OrderId("order-2")))
      
    val result = fetchUserInformation[Test](userId)
    
    result.runA(env) must beRight(
      haveUserName("John Doe") and
        haveOrders(OrderId("order-1"), OrderId("order-2")))
  }
  
}
```

That's pretty cool - we can set up the exact conditions we want in our test, by modifying the environment we run in. However, we find ourselves in a problem when trying to test the error reporting we included in our function:

```scala
class UserInformationSpec extends Specification {

  "fetch user name and orders by ID" in { ... }
  
  "log an error if user does not exists" in {
    val userId = UserId("user-1234")
    val env = TestEnv.Empty // No users here
    
    val result = fetchUserInformation[Test](userId)
    
    result.run(env) must beLeft(UserNotFound(userId))
  }
  
}
```

We are able to get out our error, wrapped in a `Left`, as expected. But we are unable to examine our `TestEnv` in case of failures - so we can't check that our error was in fact logged correctly. We can understand why that's the case when we expand the definition of our `Test` effect:

```
Test[A]                                    -->
StateT[Either[Throwable, ?], TestEnv, A]   -->
TestEnv => Either[Throwable, (TestEnv, A)]
```

As we can see, when we raise an error, we will get it back in the left part of the Either but we lose the resulting environment. We can only check what happened with our environment in the right part of the `Either` - namely, in the success case.

## Reordering our effects

In order solve this, we must tweak our effect type once again. What we want to do, is replace `TestEnv => Either[Throwable, (TestEnv, A)]` with something like `TestEnv => (TestEnv, Either[Throwable, A])`. We still preserve our ability to raise errors - but are now able to examine the `TestEnv` in both success and failure cases. We can achieve this by turning our effects stack inside-out, using the `EitherT` monad transformer:

```scala
type Test[A] = EitherT[State[TestEnv, ?], Throwable, A]
```

This will require us to re-implement our type-class instances once again to fit the new structure:

```scala
implicit val usersTest: Users[Test] = new Users[Test] {
  override def profileFor(userId: UserId): Test[UserProfile] =
    EitherT {
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
    EitherT.liftF(State.inspect(_.userOrders(userId)))
}

implicit val loggingTest: Logging[Test] = new Logging[Test] {
  override def error(e: Throwable): Test[Unit] =
    EitherT(State(env => (env.logError(e), Right(()))))
}
```

Luckily for us - Cats can still derive an instance for
`MonadError[Test, Throwable]` completely automagically!

After getting to this final form of effect type for testing, we can easily write tests that check both success and failure cases, and assert on what happened to our environment in both:

```scala
class UserInformationSpec extends Specification {

  "fetch user name and orders by ID" in { ... }

  "log an error if user does not exists" in {
    val userId = UserId("user-1234")
    val env = TestEnv.Empty // No users here

    val result = fetchUserInformation[Test](userId)

    result.value.runS(env).value must
      containLoggedError(UserNotFound(userId))
  }

}
```

# Taking this idea further

I have found this technique extremely useful for testing anything from simple functions to complex business flows in my applications. However, we sometimes require more from our effect types. If we revisit our implementation of `fetchUserInformation` we realise that fetching user's profile and list of orders are actually independent operations, and sequencing them like this is wasteful. We can run these actions concurrently, combining the results afterwards, like so:

```scala
def fetchUserInformation
  [F[_]: Concurrent: Users: Orders: Logging]
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
```

This is where it gets tricky - we don't get automatic type-class derivation for `Concurrent` for data types that are able to "write" to some shared environment (like `WriterT` or `StateT`). The reason for this is that operations can run concurrently, causing different states to be generated in an indeterministic order, unlike the sequential threading of state in the State monad. Because of that, we can't necessarily know which output state is the "right" one to keep. Perhaps this could have been solved for [Semilattice](https://en.wikipedia.org/wiki/Semilattice) states (that is - states that can be merged together in a way that is commutative, associative and idempotent). However, we can still find ways around this problem.

## Option #1 - custom instance for Concurrent

We can give up on automatic derivation, and implement our own instance of `Concurrent[Test]`, which is not actually concurrent. We can still ruse many of the auto-derived instances to delegate the actions from `MonadError`, such as `flatMap`, `tailRecM` or `raiseError`. But the fibers themselves will still run sequentially. This will allow us to keep all the code we wrote so far.

## Option #2 - a different approach

Instead of using the State monad, we can go back to use a variation on the Reader monad. We redefine `Test[A]` as `ReaderT[F, TestEnv, A]`, and as long we have a `Concurrent` instance for `F` we'll get an auto-derived instance for `Test` like we need. The most obvious `F` we can choose is probably `IO` which ships with Cats-Effect and has all the type-class instances we need. This will require us to make some more modifications, since we can't capture outputs like we did with `StateT` when using `ReaderT` - so instead of that, we'll use a concurrent `Ref` from Cats-Effect. We'll make this changes to our `TestEnv`:

```scala
case class TestEnv(
  profiles: Map[UserId, UserProfile],
  orders: Map[UserId, List[Order]],
  loggedErrors: Ref[IO, List[Throwable]]) {

  def withProfile(profile: UserProfile): TestEnv =
    copy(profiles = profiles + (profile.userId -> profile))

  def withOrder(order: Order): TestEnv = {
    val updatedUserOrders = order :: userOrders(order.userId)
    copy(orders = orders + (order.userId -> updatedUserOrders))
  }

  def logError(e: Throwable): IO[Unit] =
    loggedErrors.update(e :: _)

  def userOrders(userId: UserId): List[Order] =
    orders.getOrElse(userId, Nil)

}

object TestEnv {
  final def empty: IO[TestEnv] =
    Ref.of[IO, List[Throwable]](Nil).map { loggedErrors =>
      TestEnv(Map.empty, Map.empty, loggedErrors)
    }
}
```

Reimplement our instances for the `Users`, `Orders` and `Logging` algebras:

```scala
type Test[A] = ReaderT[IO, TestEnv, A]

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
```

And finally, apply the necessary changes in our tests:

```scala
class UserInformationSpec extends Specification {

  val userId = UserId("user-1234")

  val userInformation = fetchUserInformation[Test](userId)

  "fetch user name and orders by ID" in {
    val result = TestEnv.empty.flatMap { emptyEnv =>
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

  "log an error if user does not exists" in {
    val result = for {
      emptyEnv <- TestEnv.empty
      // We need to use `attempt` here, so won't fail-fast
      // and "break out" of the for comprehension
      _ <- userInformation.run(emptyEnv).attempt
      errors <- emptyEnv.loggedErrors.get
    } yield errors

    result.unsafeRunSync() must contain(UserNotFound(userId))
  }

}
```

# Conclusion

We've explored several methods of testing "real-world" applications that are written in the tagless-final style. We played with a contrived example, but these exact techniques are used in my day-to-day job, building real production systems. We evolved our definition of `Test[A]` given our needs and constraints:

 * We started out with `Reader[TestEnv, A]`. This didn't satisfy our needs of "writing" logged errors.
 * We moved to `State[TestEnv, A]` which let us capture state changes, like logged errors. However we could not raise errors in this context, which is an ability almost every real application needs.
 * We introduced error handling capabilities by switching to `StateT[Either[Throwable, ?], TestEnv, A]`. This looked very prominent, however, we could not inspect our environment in failure cases.
 * We re-ordered our effects stack with
`EitherT[State[TestEnv, ?], Throwable, A]` which satisfied all our needs except for testing concurrent code.
   We explored 2 alternatives for  handling with the need  for the Concurrent type-class capabilities:
   
   1) implement a pseudo-concurrent instance for testing purposes
   2) use ReaderT[IO, TestEnv, A] which has the needed instances, but require our tests to run in the context of IO.

I hope you found any of this useful / interesting, would love to get your comments on how you test your functional code.
