package io.edwardcadet.bank.app

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import io.edwardcadet.bank.actors.Bank
import io.edwardcadet.bank.actors.PersistentBankAccount.Command
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.Http
import akka.util.Timeout
import io.edwardcadet.bank.http.BankRouter

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Try, Success, Failure}

object BankApp extends App {

  def startHttpServer(bank: ActorRef[Command])(implicit system: ActorSystem[_]): Unit = {
    val router = new BankRouter(bank)
    val routes = router.routes

    val httpBindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt("localhost", 8080).bind(routes)

    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(s"Server online at http://${address.getAddress}:${address.getPort}")
      case Failure(ex) =>
        system.log.info(s"Failed to bind HTTP server, cause: $ex")
        system.terminate()
    }
  }


  trait RootCommand

  case class RetrieveBankActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand

  val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context =>
    val bankActor = context.spawn(Bank(), "bank")

    Behaviors.receiveMessage {
      case RetrieveBankActor(replyTo) =>
        replyTo ! bankActor
        Behaviors.same
    }
  }

  implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "BankSystem")
  implicit val ec: ExecutionContext = system.executionContext
  implicit val timeout: Timeout = Timeout(5.seconds)

  val bankActorFuture: Future[ActorRef[Command]] =
    system.ask(replyTo => RetrieveBankActor(replyTo))

  bankActorFuture.foreach(startHttpServer)
}
