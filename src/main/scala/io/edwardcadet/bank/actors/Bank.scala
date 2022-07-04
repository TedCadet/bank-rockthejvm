package io.edwardcadet.bank.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import java.util.UUID
import scala.util.Failure

object Bank {

  import PersistentBankAccount.Command
  import PersistentBankAccount.Commands._
  import PersistentBankAccount.Responses._

  // events | duplication dans les objets bank et PersistentBankAccount
  sealed trait Event

  case class BankAccountCreated(id: String) extends Event

  //  case class BalanceUpdated(id: String) extends Event

  case class AccountNotFound(id: String) extends Event

  // state
  case class State(accounts: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] =
    (state, command) =>
      command match {
        // TODO: rajouter d'autre case pour les cas d'exception
        case createCommand@CreateBankAccount(_, _, _, _) =>
          val id = UUID.randomUUID().toString
          val newBankAccount = context.spawn(PersistentBankAccount(id),
            id)
          Effect
            .persist(BankAccountCreated(id))
            .thenReply(newBankAccount)(_ => createCommand)

        case updateCommand@UpdateBalance(id, _, amount, replyTo) =>
          state.accounts.get(id) match {
            case Some(account) =>
              Effect
                .reply(account)(updateCommand)
            case None =>
              // failed account search
              Effect
                .persist(AccountNotFound(id))
                .thenReply(replyTo)(_ => BankAccountBalanceUpdatedResponse(Failure(new RuntimeException(("Bank account cannot be found")))))
          }

        case getCmd@GetBankAccount(id, replyTo) =>
          state.accounts.get(id) match {
            case Some(account) =>
              Effect.reply(account)(getCmd)
            case None =>
              // failed search
              Effect.reply(replyTo)(GetBankAccountResponse(None))
          }
      }

  //event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) =>
    event match {
      case BankAccountCreated(id) =>
        context.log.info(s"account $id created")
        // will be None in the recovery mode so we need to create it if None
        val account = context.child(id)
          .getOrElse(context.spawn(PersistentBankAccount(id), id))
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.accounts + (id -> account))

      case AccountNotFound(id) =>
        context.log.info(s"account $id not found")
        state

    }

  // behavior
  def apply(): Behavior[Command] = Behaviors.setup {
    context =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId("bank"),
        emptyState = State(Map()),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler(context)
      )
  }
}