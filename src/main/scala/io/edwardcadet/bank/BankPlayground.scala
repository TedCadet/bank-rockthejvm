package io.edwardcadet.bank

import akka.NotUsed
import akka.actor.typed.{ActorSystem, Behavior, Scheduler}
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import io.edwardcadet.bank.actors.Bank
import io.edwardcadet.bank.actors.PersistentBankAccount.Commands.GetBankAccount
import io.edwardcadet.bank.actors.PersistentBankAccount.Response
import io.edwardcadet.bank.actors.PersistentBankAccount.Responses.{BankAccountCreatedResponse, GetBankAccountResponse}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object BankPlayground extends App {
  val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>

    val logger = context.log
    val bank = context.spawn(Bank(), "bank")
    val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
      message =>
        message match {
          case BankAccountCreatedResponse(id) =>
            logger.info(s"successfully created bank account $id")
            Behaviors.same
          case GetBankAccountResponse(maybeBankAccount) =>
            logger.info(s"Account details: ${maybeBankAccount.get}")
            Behaviors.empty
        }

    }, "responseHandler")

    // ask pattern
    implicit val timeout: Timeout = Timeout(2.seconds)
    implicit val scheduler: Scheduler = context.system.scheduler
    implicit val ec: ExecutionContext = context.executionContext

    //    bank ! CreateBankAccount("daniel", "USD", 10, responseHandler)
    bank ! GetBankAccount("f0d495c4-29a3-4b0c-9a95-b39d7d91bcf0", responseHandler)
    Behaviors.empty
  }

  val system = ActorSystem(rootBehavior, "bankDemo")
}
